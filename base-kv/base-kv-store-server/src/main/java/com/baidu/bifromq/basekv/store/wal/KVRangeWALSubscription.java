/*
 * Copyright (c) 2023. The BifroMQ Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package com.baidu.bifromq.basekv.store.wal;

import com.baidu.bifromq.basekv.proto.KVRangeSnapshot;
import com.baidu.bifromq.basekv.raft.proto.LogEntry;
import com.baidu.bifromq.basekv.store.util.AsyncRunner;
import com.baidu.bifromq.basekv.utils.KVRangeIdUtil;
import com.baidu.bifromq.logger.SiftLogger;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;

class KVRangeWALSubscription implements IKVRangeWALSubscription {
    private final Logger log;
    private final long maxFetchBytes;
    private final IKVRangeWAL wal;
    private final Executor executor;
    private final AsyncRunner fetchRunner;
    private final AsyncRunner applyRunner;
    private final IKVRangeWALSubscriber subscriber;
    private final CompositeDisposable disposables = new CompositeDisposable();
    private final AtomicBoolean fetching = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicLong lastFetchedIdx = new AtomicLong();
    private final AtomicLong commitIdx = new AtomicLong(-1);

    KVRangeWALSubscription(long maxFetchBytes,
                           IKVRangeWAL wal,
                           Observable<Long> commitIndex,
                           long lastFetchedIndex,
                           IKVRangeWALSubscriber subscriber,
                           Executor executor,
                           String... tags) {
        this.log = SiftLogger.getLogger(KVRangeWALSubscription.class, tags);
        this.maxFetchBytes = maxFetchBytes;
        this.wal = wal;
        this.executor = executor;
        this.fetchRunner = new AsyncRunner("basekv.runner.walfetch", executor,
            "rangeId", KVRangeIdUtil.toString(wal.rangeId()));
        this.applyRunner = new AsyncRunner("basekv.runner.fsmapply", executor,
            "rangeId", KVRangeIdUtil.toString(wal.rangeId()));
        this.subscriber = subscriber;
        this.lastFetchedIdx.set(lastFetchedIndex);
        this.subscriber.onSubscribe(this);
        disposables.add(wal.snapshotInstallTask()
            .subscribe(task -> fetchRunner.add(() -> {
                applyRunner.cancelAll();
                applyRunner.add(installSnapshot(task.snapshot, task.leader, task.onDone))
                    .thenAccept(snap -> fetchRunner.add(() -> {
                        log.debug(
                            "Snapshot installed: range={}, ver={}, state={}, checkpoint={}, lastAppliedIndex={}",
                            KVRangeIdUtil.toString(snap.getId()),
                            snap.getVer(), snap.getState(), snap.getCheckpointId(), snap.getLastAppliedIndex());
                        lastFetchedIdx.set(snap.getLastAppliedIndex());
                        commitIdx.set(-1);
                    }));
            })));
        disposables.add(commitIndex
            .subscribe(c -> fetchRunner.add(() -> {
                commitIdx.set(c);
                scheduleFetchWAL();
            })));
    }

    @Override
    public void stop() {
        if (stopped.compareAndSet(false, true)) {
            disposables.dispose();
            fetchRunner.cancelAll();
            applyRunner.cancelAll();
        }
    }

    private void scheduleFetchWAL() {
        if (!stopped.get() && fetching.compareAndSet(false, true)) {
            fetchRunner.add(this::fetchWAL);
        }
    }

    private CompletableFuture<Void> fetchWAL() {
        if (lastFetchedIdx.get() < commitIdx.get()) {
            return wal.retrieveCommitted(lastFetchedIdx.get() + 1, maxFetchBytes)
                .handleAsync((logEntries, e) -> {
                    if (e != null) {
                        log.error("Failed to retrieve log from wal from index[{}]", lastFetchedIdx.get() + 1, e);
                        fetching.set(false);
                        if (!(e instanceof IndexOutOfBoundsException)) {
                            scheduleFetchWAL();
                        }
                    } else {
                        fetchRunner.add(() -> {
                            LogEntry entry = null;
                            while (logEntries.hasNext()) {
                                // no restore task interrupted
                                entry = logEntries.next();
                                applyRunner.add(applyLog(entry));
                            }
                            if (entry != null) {
                                lastFetchedIdx.set(Math.max(entry.getIndex(), lastFetchedIdx.get()));
                            }
                            fetching.set(false);
                            if (lastFetchedIdx.get() < commitIdx.get()) {
                                scheduleFetchWAL();
                            }
                        });
                    }
                    return null;
                }, executor);
        } else {
            fetching.set(false);
            if (lastFetchedIdx.get() < commitIdx.get()) {
                scheduleFetchWAL();
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    private Supplier<CompletableFuture<Void>> applyLog(LogEntry logEntry) {
        return () -> {
            CompletableFuture<Void> onDone = new CompletableFuture<>();
            CompletableFuture<Void> applyFuture = subscriber.apply(logEntry);
            onDone.whenComplete((v, e) -> {
                if (onDone.isCancelled()) {
                    applyFuture.cancel(true);
                }
            });
            applyFuture.whenCompleteAsync((v, e) -> fetchRunner.add(() -> {
                // always examine state and submit application task sequentially
                if (!onDone.isCancelled()) {
                    if (e != null) {
                        // reapply
                        applyRunner.addFirst(applyLog(logEntry));
                    }
                }
                onDone.complete(null);
            }), executor);
            return onDone;
        };
    }

    private Supplier<CompletableFuture<KVRangeSnapshot>> installSnapshot(KVRangeSnapshot snapshot,
                                                                         String leader,
                                                                         CompletableFuture<KVRangeSnapshot> onDone) {
        return () -> {
            if (onDone.isCancelled()) {
                return CompletableFuture.completedFuture(null);
            }
            // start async installation
            CompletableFuture<KVRangeSnapshot> installFuture = subscriber.install(snapshot, leader);
            onDone.whenComplete((v, e) -> {
                if (onDone.isCancelled()) {
                    // cancel installation if possible
                    installFuture.cancel(true);
                }
            });
            installFuture.whenCompleteAsync((installed, e) -> {
                if (e != null) {
                    onDone.completeExceptionally(e);
                } else {
                    onDone.complete(installed);
                }
            }, executor);
            return onDone;
        };
    }
}

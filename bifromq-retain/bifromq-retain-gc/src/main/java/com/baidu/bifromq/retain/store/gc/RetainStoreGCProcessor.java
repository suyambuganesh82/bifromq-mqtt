/*
 * Copyright (c) 2024. The BifroMQ Authors. All Rights Reserved.
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

package com.baidu.bifromq.retain.store.gc;

import static com.baidu.bifromq.basekv.utils.BoundaryUtil.FULL_BOUNDARY;
import static com.baidu.bifromq.retain.utils.KeyUtil.tenantNS;
import static com.baidu.bifromq.retain.utils.MessageUtil.buildGCRequest;

import com.baidu.bifromq.basekv.client.KVRangeSetting;
import com.baidu.bifromq.basekv.client.IBaseKVStoreClient;
import com.baidu.bifromq.basekv.store.proto.KVRangeRWRequest;
import com.baidu.bifromq.basekv.store.proto.RWCoProcInput;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RetainStoreGCProcessor implements IRetainStoreGCProcessor {
    private final IBaseKVStoreClient storeClient;
    private final String localServerId;

    public RetainStoreGCProcessor(IBaseKVStoreClient storeClient, String localServerId) {
        this.storeClient = storeClient;
        this.localServerId = localServerId;
    }

    @Override
    public CompletableFuture<Result> gc(long reqId,
                                        @Nullable String tenantId,
                                        @Nullable Integer expirySeconds,
                                        long now) {
        if (tenantId != null) {
            Optional<KVRangeSetting> rangeSettingOpt = storeClient.findByKey(tenantNS(tenantId));
            if (rangeSettingOpt.isEmpty()) {
                return CompletableFuture.completedFuture(Result.ERROR);
            }
            KVRangeSetting rangeSetting = rangeSettingOpt.get();
            return gcRange(reqId, rangeSetting, tenantId, expirySeconds, now);
        } else {
            CompletableFuture<?>[] gcFutures = storeClient.findByBoundary(FULL_BOUNDARY)
                .stream()
                .filter(k -> localServerId == null || k.leader.equals(localServerId))
                .map(rangeSetting -> gcRange(reqId, rangeSetting, null, expirySeconds, now))
                .toArray(CompletableFuture[]::new);
            return CompletableFuture.allOf(gcFutures)
                .thenApply(v -> Arrays.stream(gcFutures).map(CompletableFuture::join).toList())
                .thenApply(v -> {
                    log.debug("All range gc succeed");
                    return v.stream().anyMatch(r -> r != Result.OK) ? Result.ERROR : Result.OK;
                })
                .exceptionally(e -> {
                    log.error("Some range gc failed");
                    return Result.ERROR;
                });
        }
    }

    private CompletableFuture<Result> gcRange(long reqId,
                                              KVRangeSetting rangeSetting,
                                              @Nullable String tenantId,
                                              @Nullable Integer expirySeconds,
                                              long now) {
        return storeClient.execute(rangeSetting.leader, KVRangeRWRequest.newBuilder()
                .setReqId(reqId)
                .setKvRangeId(rangeSetting.id)
                .setVer(rangeSetting.ver)
                .setRwCoProc(RWCoProcInput.newBuilder()
                    .setRetainService(buildGCRequest(reqId, now, tenantId, expirySeconds))
                    .build())
                .build())
            .thenApply(reply -> {
                log.debug("Range gc succeed: serverId={}, rangeId={}, ver={}",
                    rangeSetting.leader, rangeSetting.id, rangeSetting.ver);
                return Result.OK;
            })
            .exceptionally(e -> {
                log.error("Range gc failed: serverId={}, rangeId={}, ver={}",
                    rangeSetting.leader, rangeSetting.id, rangeSetting.ver);
                return Result.ERROR;
            });
    }
}

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

package com.baidu.bifromq.dist.server;

import static com.baidu.bifromq.baserpc.UnaryResponse.response;

import com.baidu.bifromq.basecrdt.service.ICRDTService;
import com.baidu.bifromq.basekv.client.IBaseKVStoreClient;
import com.baidu.bifromq.basescheduler.ICallScheduler;
import com.baidu.bifromq.dist.rpc.proto.DistReply;
import com.baidu.bifromq.dist.rpc.proto.DistRequest;
import com.baidu.bifromq.dist.rpc.proto.DistServiceGrpc;
import com.baidu.bifromq.dist.rpc.proto.MatchReply;
import com.baidu.bifromq.dist.rpc.proto.MatchRequest;
import com.baidu.bifromq.dist.rpc.proto.UnmatchReply;
import com.baidu.bifromq.dist.rpc.proto.UnmatchRequest;
import com.baidu.bifromq.dist.server.handler.MatchReqHandler;
import com.baidu.bifromq.dist.server.handler.UnmatchReqHandler;
import com.baidu.bifromq.dist.server.scheduler.DistCallScheduler;
import com.baidu.bifromq.dist.server.scheduler.DistWorkerCall;
import com.baidu.bifromq.dist.server.scheduler.IDistCallScheduler;
import com.baidu.bifromq.dist.server.scheduler.IGlobalDistCallRateSchedulerFactory;
import com.baidu.bifromq.dist.server.scheduler.IMatchCallScheduler;
import com.baidu.bifromq.dist.server.scheduler.IUnmatchCallScheduler;
import com.baidu.bifromq.dist.server.scheduler.MatchCallScheduler;
import com.baidu.bifromq.dist.server.scheduler.UnmatchCallScheduler;
import com.baidu.bifromq.plugin.eventcollector.IEventCollector;
import com.baidu.bifromq.plugin.settingprovider.ISettingProvider;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DistService extends DistServiceGrpc.DistServiceImplBase {
    private final IEventCollector eventCollector;
    private final ICallScheduler<DistWorkerCall> distCallRateScheduler;
    private final IDistCallScheduler distCallScheduler;
    private final MatchReqHandler matchReqHandler;
    private final UnmatchReqHandler unmatchReqHandler;
    private final LoadingCache<String, RunningAverage> tenantFanouts;

    DistService(IBaseKVStoreClient distWorkerClient,
                ISettingProvider settingProvider,
                IEventCollector eventCollector,
                ICRDTService crdtService,
                IGlobalDistCallRateSchedulerFactory distCallRateScheduler) {
        this.eventCollector = eventCollector;
        this.distCallRateScheduler = distCallRateScheduler.createScheduler(settingProvider, crdtService);

        IMatchCallScheduler matchCallScheduler = new MatchCallScheduler(distWorkerClient, settingProvider);
        matchReqHandler = new MatchReqHandler(eventCollector, matchCallScheduler);

        IUnmatchCallScheduler unmatchCallScheduler = new UnmatchCallScheduler(distWorkerClient);
        unmatchReqHandler = new UnmatchReqHandler(eventCollector, unmatchCallScheduler);

        tenantFanouts = Caffeine.newBuilder()
            .expireAfterAccess(120, TimeUnit.SECONDS)
            .build(k -> new RunningAverage(5));
        this.distCallScheduler = new DistCallScheduler(this.distCallRateScheduler, distWorkerClient, settingProvider);
    }

    @Override
    public void match(MatchRequest request, StreamObserver<MatchReply> responseObserver) {
        response(tenantId -> matchReqHandler.handle(request), responseObserver);
    }

    public void unmatch(UnmatchRequest request, StreamObserver<UnmatchReply> responseObserver) {
        response(tenantId -> unmatchReqHandler.handle(request), responseObserver);
    }

    @Override
    public StreamObserver<DistRequest> dist(StreamObserver<DistReply> responseObserver) {
        return new DistResponsePipeline(distCallScheduler, responseObserver, eventCollector, tenantFanouts);
    }

    public void stop() {
        log.debug("stop dist call scheduler");
        distCallScheduler.close();
        log.debug("stop dist call pre-scheduler");
        distCallRateScheduler.close();
        log.debug("Stop match req handler");
        matchReqHandler.close();
        log.debug("Stop unmatch req handler");
        unmatchReqHandler.close();
    }
}

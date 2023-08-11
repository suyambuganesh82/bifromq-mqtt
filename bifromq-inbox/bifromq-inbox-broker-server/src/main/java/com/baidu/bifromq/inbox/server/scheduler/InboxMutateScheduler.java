/*
 * Copyright (c) 2023. Baidu, Inc. All Rights Reserved.
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

package com.baidu.bifromq.inbox.server.scheduler;

import com.baidu.bifromq.basekv.KVRangeSetting;
import com.baidu.bifromq.basekv.client.IBaseKVStoreClient;
import com.baidu.bifromq.basescheduler.BatchCallScheduler;
import com.google.protobuf.ByteString;
import java.time.Duration;
import java.util.Optional;

public abstract class InboxMutateScheduler<Req, Resp> extends BatchCallScheduler<Req, Resp, KVRangeSetting> {
    protected final IBaseKVStoreClient inboxStoreClient;

    public InboxMutateScheduler(IBaseKVStoreClient inboxStoreClient, String name) {
        super(name, Duration.ofMillis(50L), Duration.ofSeconds(1));
        this.inboxStoreClient = inboxStoreClient;
    }

    @Override
    protected Optional<KVRangeSetting> find(Req req) {
        return inboxStoreClient.findByKey(rangeKey(req));
    }

    protected abstract ByteString rangeKey(Req request);
}
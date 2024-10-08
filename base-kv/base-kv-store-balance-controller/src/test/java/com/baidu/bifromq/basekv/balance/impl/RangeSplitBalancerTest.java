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

package com.baidu.bifromq.basekv.balance.impl;

import static com.baidu.bifromq.basekv.balance.impl.RangeSplitBalancer.LOAD_TYPE_AVG_LATENCY_NANOS;
import static com.baidu.bifromq.basekv.balance.impl.RangeSplitBalancer.LOAD_TYPE_IO_DENSITY;
import static com.baidu.bifromq.basekv.balance.impl.RangeSplitBalancer.LOAD_TYPE_IO_LATENCY_NANOS;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import com.baidu.bifromq.basekv.balance.command.SplitCommand;
import com.baidu.bifromq.basekv.proto.KVRangeDescriptor;
import com.baidu.bifromq.basekv.proto.KVRangeId;
import com.baidu.bifromq.basekv.proto.KVRangeStoreDescriptor;
import com.baidu.bifromq.basekv.proto.SplitHint;
import com.baidu.bifromq.basekv.proto.State;
import com.baidu.bifromq.basekv.raft.proto.RaftNodeStatus;
import com.baidu.bifromq.basekv.utils.KVRangeIdUtil;
import com.google.protobuf.ByteString;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import org.testng.annotations.Test;

public class RangeSplitBalancerTest {
    private static final String HintType = "kv_io_mutation";
    private final String clusterId = "clusterId";

    @Test
    public void noLocalDesc() {
        RangeSplitBalancer balancer = new RangeSplitBalancer(clusterId, "local", HintType);
        assertFalse(balancer.balance().isPresent());
    }

    @Test
    public void cpuUsageExceedLimit() {
        RangeSplitBalancer balancer = new RangeSplitBalancer(clusterId, "local", HintType);
        balancer.update(Collections.singleton(KVRangeStoreDescriptor
            .newBuilder()
            .setId("local")
            .putStatistics("cpu.usage", 0.75)
            .build()
        ));
        assertFalse(balancer.balance().isPresent());
    }

    @Test
    public void splitHintPreference() {
        KVRangeId rangeId = KVRangeIdUtil.generate();
        Set<KVRangeStoreDescriptor> descriptors = Collections.singleton(KVRangeStoreDescriptor
            .newBuilder()
            .setId("local")
            .putStatistics("cpu.usage", 0.65)
            .addRanges(KVRangeDescriptor.newBuilder()
                .setId(rangeId)
                .setRole(RaftNodeStatus.Leader)
                .setState(State.StateType.Normal)
                .addHints(SplitHint.newBuilder()
                    .setType(HintType)
                    .putLoad(LOAD_TYPE_IO_DENSITY, 10)
                    .putLoad(LOAD_TYPE_IO_LATENCY_NANOS, 15)
                    .putLoad(LOAD_TYPE_AVG_LATENCY_NANOS, 100)
                    .setSplitKey(ByteString.copyFromUtf8("splitMutationLoadKey"))
                    .build())
                .build())
            .build()
        );
        RangeSplitBalancer balancer = new RangeSplitBalancer(clusterId, "local", HintType, 10, 0.8, 5, 20);
        balancer.update(descriptors);
        Optional<SplitCommand> command = balancer.balance();
        assertTrue(command.isPresent());
        assertEquals(command.get().getKvRangeId(), rangeId);
        assertEquals(command.get().getToStore(), "local");
        assertEquals(command.get().getExpectedVer(), 0);
        assertEquals(((SplitCommand) command.get()).getSplitKey(), ByteString.copyFromUtf8("splitMutationLoadKey"));
    }

    @Test
    public void hintNoSplitKey() {
        KVRangeId rangeId = KVRangeIdUtil.generate();
        Set<KVRangeStoreDescriptor> descriptors = Collections.singleton(KVRangeStoreDescriptor
            .newBuilder()
            .setId("local")
            .putStatistics("cpu.usage", 0.65)
            .addRanges(KVRangeDescriptor.newBuilder()
                .setId(rangeId)
                .setRole(RaftNodeStatus.Leader)
                .setState(State.StateType.Normal)
                .addHints(SplitHint.newBuilder()
                    .setType(HintType)
                    .putLoad(LOAD_TYPE_IO_DENSITY, 1)
                    .putLoad(LOAD_TYPE_IO_LATENCY_NANOS, 1)
                    .putLoad(LOAD_TYPE_AVG_LATENCY_NANOS, 1)
                    .build())
                .build())
            .build()
        );
        RangeSplitBalancer balancer = new RangeSplitBalancer(clusterId, "local", HintType);
        balancer.update(descriptors);
        Optional<SplitCommand> command = balancer.balance();
        assertFalse(command.isPresent());
    }

    @Test
    public void noRoomPauseSplit() {
        KVRangeId rangeId = KVRangeIdUtil.generate();
        Set<KVRangeStoreDescriptor> descriptors = Collections.singleton(KVRangeStoreDescriptor
            .newBuilder()
            .setId("local")
            .putStatistics("cpu.usage", 0.65)
            .addRanges(KVRangeDescriptor.newBuilder()
                .setId(rangeId)
                .setRole(RaftNodeStatus.Leader)
                .setState(State.StateType.Normal)
                .addHints(SplitHint.newBuilder()
                    .setType(HintType)
                    .putLoad(LOAD_TYPE_IO_DENSITY, 10)
                    .putLoad(LOAD_TYPE_IO_LATENCY_NANOS, 15)
                    .putLoad(LOAD_TYPE_AVG_LATENCY_NANOS, 100)
                    .setSplitKey(ByteString.copyFromUtf8("splitMutationLoadKey"))
                    .build())
                .build())
            .build()
        );
        RangeSplitBalancer balancer = new RangeSplitBalancer(clusterId, "local", HintType, 1, 0.8, 5, 20);
        balancer.update(descriptors);
        Optional<SplitCommand> command = balancer.balance();
        assertFalse(command.isPresent());
    }
}

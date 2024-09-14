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

package com.baidu.bifromq.basekv.client;

import static com.baidu.bifromq.basekv.utils.BoundaryUtil.compareEndKeys;
import static com.baidu.bifromq.basekv.utils.BoundaryUtil.endKey;

import com.baidu.bifromq.basekv.proto.Boundary;
import com.baidu.bifromq.basekv.utils.BoundaryUtil;
import com.google.common.collect.Lists;
import com.google.protobuf.ByteString;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;

public class KVRangeRouterUtil {

    public static Optional<KVRangeSetting> findByKey(ByteString key,
                                                     NavigableMap<Boundary, KVRangeSetting> effectiveRouter) {
        Map.Entry<Boundary, KVRangeSetting> entry =
            effectiveRouter.floorEntry(Boundary.newBuilder().setStartKey(key).build());
        if (entry != null) {
            KVRangeSetting setting = entry.getValue();
            if (BoundaryUtil.inRange(key, entry.getKey())) {
                return Optional.of(setting);
            }
        }
        return Optional.empty();
    }

    public static List<KVRangeSetting> findByBoundary(Boundary boundary,
                                                      NavigableMap<Boundary, KVRangeSetting> effectiveRouter) {
        if (effectiveRouter.isEmpty()) {
            return Collections.emptyList();
        }
        if (!boundary.hasStartKey() && !boundary.hasEndKey()) {
            return Lists.newArrayList(effectiveRouter.values());
        }
        if (!boundary.hasStartKey()) {
            Boundary boundaryEnd = Boundary.newBuilder()
                .setStartKey(boundary.getEndKey())
                .setEndKey(boundary.getEndKey()).build();
            return Lists.newArrayList(effectiveRouter.headMap(boundaryEnd, false).values());
        }
        if (!boundary.hasEndKey()) {
            Boundary boundaryStart = Boundary.newBuilder()
                .setStartKey(boundary.getStartKey())
                .setEndKey(boundary.getStartKey()).build();
            Boundary floorBoundary = effectiveRouter.floorKey(boundaryStart);
            return Lists.newArrayList(
                effectiveRouter.tailMap(floorBoundary,
                        compareEndKeys(endKey(floorBoundary), boundary.getStartKey()) > 0)
                    .values());
        }
        Boundary boundaryStart = Boundary.newBuilder()
            .setStartKey(boundary.getStartKey())
            .setEndKey(boundary.getStartKey()).build();
        Boundary boundaryEnd = Boundary.newBuilder()
            .setStartKey(boundary.getEndKey())
            .setEndKey(boundary.getEndKey()).build();
        Boundary floorBoundary = effectiveRouter.floorKey(boundaryStart);

        return Lists.newArrayList(
            effectiveRouter.subMap(floorBoundary, compareEndKeys(endKey(floorBoundary), boundary.getStartKey()) > 0,
                boundaryEnd, false).values());
    }
}
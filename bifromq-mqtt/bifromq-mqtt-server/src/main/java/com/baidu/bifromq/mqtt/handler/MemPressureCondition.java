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

package com.baidu.bifromq.mqtt.handler;

import java.util.function.Supplier;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class MemPressureCondition implements Supplier<Boolean> {
    public static final MemPressureCondition INSTANCE = new MemPressureCondition();

    private final DirectMemPressureCondition directMemPressureCondition = DirectMemPressureCondition.INSTANCE;
    private final HeapMemPressureCondition heapMemPressureCondition = HeapMemPressureCondition.INSTANCE;

    @Override
    public Boolean get() {
        return directMemPressureCondition.get() || heapMemPressureCondition.get();
    }
}

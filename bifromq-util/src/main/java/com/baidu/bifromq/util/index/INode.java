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

package com.baidu.bifromq.util.index;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

public class INode<V> {
    private static final AtomicReferenceFieldUpdater<INode, MainNode>
        MAIN_UPDATER = AtomicReferenceFieldUpdater.newUpdater(INode.class, MainNode.class, "main");

    private volatile MainNode<V> main = null;

    INode(MainNode<V> main) {
        cas(null, main);
    }

    @SuppressWarnings("unchecked")
    public MainNode<V> main() {
        return MAIN_UPDATER.get(this);
    }

    public boolean cas(MainNode<V> expect, MainNode<V> update) {
        return MAIN_UPDATER.compareAndSet(this, expect, update);
    }
}

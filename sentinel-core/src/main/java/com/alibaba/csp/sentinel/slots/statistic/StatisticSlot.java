/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.csp.sentinel.slots.statistic;

import java.util.Collection;

import com.alibaba.csp.sentinel.slotchain.ProcessorSlotEntryCallback;
import com.alibaba.csp.sentinel.slotchain.ProcessorSlotExitCallback;
import com.alibaba.csp.sentinel.slots.block.flow.PriorityWaitException;
import com.alibaba.csp.sentinel.util.TimeUtil;
import com.alibaba.csp.sentinel.Constants;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.node.ClusterNode;
import com.alibaba.csp.sentinel.node.DefaultNode;
import com.alibaba.csp.sentinel.slotchain.AbstractLinkedProcessorSlot;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.alibaba.csp.sentinel.slots.block.BlockException;

/**
 * <p>
 * A processor slot that dedicates to real time statistics.
 * When entering this slot, we need to separately count the following
 * information:
 * <ul>
 * <li>{@link ClusterNode}: total statistics of a cluster node of the resource ID.</li>
 * <li>Origin node: statistics of a cluster node from different callers/origins.</li>
 * <li>{@link DefaultNode}: statistics for specific resource name in the specific context.</li>
 * <li>Finally, the sum statistics of all entrances.</li>
 * </ul>
 * </p>
 *
 * @author jialiang.linjl
 * @author Eric Zhao
 */
public class StatisticSlot extends AbstractLinkedProcessorSlot<DefaultNode> {

    @Override
    public void entry(Context context, ResourceWrapper resourceWrapper, DefaultNode node, int count,
                      boolean prioritized, Object... args) throws Throwable {
        try {
            // Do some checking. 触发下一个Slot的entry方法，进行限流、降级等检测
            fireEntry(context, resourceWrapper, node, count, prioritized, args);

            // 如果能通过SlotChain中后面的Slot的entry方法，说明没有被限流或降级
            // 统计信息
            // Request passed, add thread count and pass count.
            node.increaseThreadNum();
            node.addPassRequest(count);

            // 源节点StatisticNode计数
            if (context.getCurEntry().getOriginNode() != null) {
                // Add count for origin node. 源节点StatisticNode计数
                context.getCurEntry().getOriginNode().increaseThreadNum();
                context.getCurEntry().getOriginNode().addPassRequest(count);
            }

            // 全局入流量统计
            if (resourceWrapper.getType() == EntryType.IN) {
                // Add count for global inbound entry node for global statistics.
                Constants.ENTRY_NODE.increaseThreadNum();
                Constants.ENTRY_NODE.addPassRequest(count);
            }

            // Handle pass event with registered entry callback handlers. 回调处理
            for (ProcessorSlotEntryCallback<DefaultNode> handler : StatisticSlotCallbackRegistry.getEntryCallbacks()) {
                handler.onPass(context, resourceWrapper, node, count, args);
            }
        } catch (PriorityWaitException ex) {
            node.increaseThreadNum();
            if (context.getCurEntry().getOriginNode() != null) {
                // Add count for origin node.
                context.getCurEntry().getOriginNode().increaseThreadNum();
            }

            if (resourceWrapper.getType() == EntryType.IN) {
                // Add count for global inbound entry node for global statistics.
                Constants.ENTRY_NODE.increaseThreadNum();
            }
            // Handle pass event with registered entry callback handlers.
            for (ProcessorSlotEntryCallback<DefaultNode> handler : StatisticSlotCallbackRegistry.getEntryCallbacks()) {
                handler.onPass(context, resourceWrapper, node, count, args);
            }
        } catch (BlockException e) {
            // Blocked, set block exception to current entry.
            context.getCurEntry().setError(e); // 设置异常

            // Add block count. Block计数
            node.increaseBlockQps(count);
            if (context.getCurEntry().getOriginNode() != null) {
                context.getCurEntry().getOriginNode().increaseBlockQps(count);
            }

            // 全局入流量统计
            if (resourceWrapper.getType() == EntryType.IN) {
                // Add count for global inbound entry node for global statistics.
                Constants.ENTRY_NODE.increaseBlockQps(count);
            }

            // Handle block event with registered entry callback handlers.
            for (ProcessorSlotEntryCallback<DefaultNode> handler : StatisticSlotCallbackRegistry.getEntryCallbacks()) {
                handler.onBlocked(e, context, resourceWrapper, node, count, args);
            }

            throw e;
        } catch (Throwable e) {
            // Unexpected error, set error to current entry. 未知异常
            context.getCurEntry().setError(e);

            // This should not happen.
            node.increaseExceptionQps(count);
            if (context.getCurEntry().getOriginNode() != null) {
                context.getCurEntry().getOriginNode().increaseExceptionQps(count);
            }

            if (resourceWrapper.getType() == EntryType.IN) {
                Constants.ENTRY_NODE.increaseExceptionQps(count);
            }
            throw e;
        }
    }

    @Override
    public void exit(Context context, ResourceWrapper resourceWrapper, int count, Object... args) {
        DefaultNode node = (DefaultNode)context.getCurNode();

        if (context.getCurEntry().getError() == null) {
            // Calculate response time (max RT is TIME_DROP_VALVE).
            long rt = TimeUtil.currentTimeMillis() - context.getCurEntry().getCreateTime(); // 响应时间统计
            if (rt > Constants.TIME_DROP_VALVE) { // 超过4900ms，则为4900ms
                rt = Constants.TIME_DROP_VALVE;
            }

            // Record response time and success count. 当前节点统计rt和success count
            node.addRtAndSuccess(rt, count);
            if (context.getCurEntry().getOriginNode() != null) {
                context.getCurEntry().getOriginNode().addRtAndSuccess(rt, count);
            }

            node.decreaseThreadNum(); // 线程数减1

            if (context.getCurEntry().getOriginNode() != null) {
                context.getCurEntry().getOriginNode().decreaseThreadNum();
            }

            if (resourceWrapper.getType() == EntryType.IN) {
                Constants.ENTRY_NODE.addRtAndSuccess(rt, count); // 全局入流量统计
                Constants.ENTRY_NODE.decreaseThreadNum();
            }
        } else {
            // Error may happen.
        }

        // Handle exit event with registered exit callback handlers.
        Collection<ProcessorSlotExitCallback> exitCallbacks = StatisticSlotCallbackRegistry.getExitCallbacks();
        for (ProcessorSlotExitCallback handler : exitCallbacks) {
            handler.onExit(context, resourceWrapper, count, args);
        }

        fireExit(context, resourceWrapper, count);
    }
}

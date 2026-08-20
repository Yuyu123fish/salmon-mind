package com.yuyu.salmonmind.agent.infrastructure.reactagent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 把一轮 Assistant Tool Calls 切成连续的并行组与屏障组。
 *
 * <p>Spring AI Alibaba 当前公开 Builder 只有整批并行/整批顺序开关，没有混合组 API。
 * 本类只利用 ToolInterceptor 已公开的执行上下文读取当前 Assistant 消息，并在 Handler
 * 之前实现组间顺序：相邻的只读工具共享一个并行组，未知或明确禁止并行的工具成为
 * 屏障，屏障之前的组完全结束后才允许它开始。模型看到的 Tool Response 顺序仍由框架
 * 按原始 Tool Call 顺序组装。</p>
 */
final class ToolExecutionBatchCoordinator {

    static final String METADATA_KEY = "salmon:agent:tool-batch-coordinator";

    private final Map<String, Boolean> parallelAllowed;
    private final int maxConcurrentTools;
    private final Duration admissionTimeout;
    private final Object initializationLock = new Object();
    private volatile BatchPlan plan;

    ToolExecutionBatchCoordinator(Map<String, Boolean> parallelAllowed, int maxConcurrentTools) {
        this(parallelAllowed, maxConcurrentTools, Duration.ofSeconds(60));
    }

    ToolExecutionBatchCoordinator(
            Map<String, Boolean> parallelAllowed,
            int maxConcurrentTools,
            Duration admissionTimeout
    ) {
        this.parallelAllowed = parallelAllowed == null
                ? Map.of() : Map.copyOf(parallelAllowed);
        this.maxConcurrentTools = Math.max(1, maxConcurrentTools);
        if (admissionTimeout == null || admissionTimeout.isZero() || admissionTimeout.isNegative()) {
            throw new IllegalArgumentException("工具批次等待上限必须为正数");
        }
        this.admissionTimeout = admissionTimeout;
    }

    /**
     * 领取当前 Tool Call 的组内执行槽；调用方必须在 Handler 结束后关闭返回值。
     */
    Permit acquire(ToolCallRequest request) throws InterruptedException, TimeoutException {
        BatchPlan current = planFor(request);
        Slot slot = current.slot(request.getToolCallId());
        if (slot == null) {
            // 没有 Assistant 批次状态时，保守地把调用视为独立屏障，不阻塞已有组。
            return Permit.noop();
        }
        long deadline = System.nanoTime() + admissionTimeout.toNanos();
        try {
            long remaining = remainingNanos(deadline);
            slot.group().previous().get(remaining, java.util.concurrent.TimeUnit.NANOSECONDS);
            remaining = remainingNanos(deadline);
            if (!slot.group().slots().tryAcquire(remaining, java.util.concurrent.TimeUnit.NANOSECONDS)) {
                throw new TimeoutException("工具批次并发许可等待超时");
            }
        } catch (java.util.concurrent.ExecutionException ex) {
            slot.group().completeOne(false);
            throw new IllegalStateException("工具批次前序执行异常", ex.getCause());
        } catch (InterruptedException | TimeoutException ex) {
            slot.group().completeOne(false);
            throw ex;
        }
        return new Permit(slot.group());
    }

    private static long remainingNanos(long deadline) throws TimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0L) {
            throw new TimeoutException("工具批次屏障等待超时");
        }
        return remaining;
    }

    private BatchPlan planFor(ToolCallRequest request) {
        BatchPlan current = plan;
        if (current != null && current.slot(request.getToolCallId()) != null) {
            return current;
        }
        synchronized (initializationLock) {
            current = plan;
            if (current == null || current.slot(request.getToolCallId()) == null) {
                // 一个 ReactAgent Run 可以经历多轮 Assistant -> Tool。上一轮全部结束后，
                // 下一轮会带着新的 callId 进入同一个 coordinator，必须按最新 Assistant
                // 消息重建批次；否则后续调用会退化成 noop 并绕过屏障。
                plan = BatchPlan.from(request, parallelAllowed, maxConcurrentTools);
            }
            return plan;
        }
    }

    static final class Permit implements AutoCloseable {
        private static final Permit NOOP = new Permit(null);
        private final Group group;
        private boolean closed;

        private Permit(Group group) {
            this.group = group;
        }

        static Permit noop() {
            return NOOP;
        }

        @Override
        public synchronized void close() {
            if (closed || group == null) {
                return;
            }
            closed = true;
            group.completeOne(true);
        }
    }

    private record Slot(Group group) {
    }

    private static final class Group {
        private final boolean parallel;
        private final CompletableFuture<Void> previous;
        private final Semaphore slots;
        private final AtomicInteger remaining;
        private final CompletableFuture<Void> completed = new CompletableFuture<>();

        private Group(boolean parallel, CompletableFuture<Void> previous, int count, int maximum) {
            this.parallel = parallel;
            this.previous = previous;
            this.slots = new Semaphore(parallel ? maximum : 1, true);
            this.remaining = new AtomicInteger(count);
        }

        boolean parallel() {
            return parallel;
        }

        CompletableFuture<Void> previous() {
            return previous;
        }

        Semaphore slots() {
            return slots;
        }

        synchronized void completeOne(boolean slotAcquired) {
            if (slotAcquired) {
                slots.release();
            }
            if (remaining.decrementAndGet() == 0) {
                previous.whenComplete((ignored, failure) -> {
                    if (failure == null) {
                        completed.complete(null);
                    } else {
                        completed.completeExceptionally(failure);
                    }
                });
            }
        }
    }

    private static final class BatchPlan {
        private final Map<String, Slot> slots;

        private BatchPlan(Map<String, Slot> slots) {
            this.slots = slots;
        }

        Slot slot(String callId) {
            return slots.get(callId);
        }

        static BatchPlan from(
                ToolCallRequest request,
                Map<String, Boolean> parallelAllowed,
                int maxConcurrentTools
        ) {
            List<AssistantMessage.ToolCall> calls = toolCallsOf(request);
            if (calls.isEmpty()) {
                return new BatchPlan(Map.of());
            }
            Map<String, Slot> slots = new LinkedHashMap<>();
            CompletableFuture<Void> previous = CompletableFuture.completedFuture(null);
            int index = 0;
            while (index < calls.size()) {
                AssistantMessage.ToolCall first = calls.get(index);
                boolean parallel = Boolean.TRUE.equals(parallelAllowed.get(first.name()));
                int end = index + 1;
                while (parallel && end < calls.size()) {
                    AssistantMessage.ToolCall next = calls.get(end);
                    boolean nextParallel = Boolean.TRUE.equals(parallelAllowed.get(next.name()));
                    if (!nextParallel) {
                        break;
                    }
                    end++;
                }
                Group group = new Group(parallel, previous, end - index, maxConcurrentTools);
                for (int item = index; item < end; item++) {
                    slots.put(calls.get(item).id(), new Slot(group));
                }
                previous = group.completed;
                index = end;
            }
            return new BatchPlan(slots);
        }

        private static List<AssistantMessage.ToolCall> toolCallsOf(ToolCallRequest request) {
            if (request.getExecutionContext().isEmpty()) {
                return List.of();
            }
            Object value = request.getExecutionContext().get().state().data().get("messages");
            List<Message> messages = messagesOf(value);
            for (int index = messages.size() - 1; index >= 0; index--) {
                if (messages.get(index) instanceof AssistantMessage assistant) {
                    return assistant.getToolCalls();
                }
            }
            return List.of();
        }

        private static List<Message> messagesOf(Object value) {
            if (!(value instanceof List<?> values)) {
                return List.of();
            }
            List<Message> messages = new ArrayList<>();
            for (Object item : values) {
                if (item instanceof Message message) {
                    messages.add(message);
                }
            }
            return messages;
        }
    }
}

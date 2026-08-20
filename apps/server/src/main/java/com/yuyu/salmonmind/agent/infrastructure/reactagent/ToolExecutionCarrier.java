package com.yuyu.salmonmind.agent.infrastructure.reactagent;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 工具调度的内部执行载体。
 *
 * <p>框架负责把同一模型响应中的 Tool Call 投递到 executor，但框架 timeout 从投递时刻
 * 开始计时，无法表达“屏障之后才开始执行”的语义。该载体把框架投递线程与真正调用
 * Handler 的线程分开：前者负责等待批次顺序，后者在获得批次和跨 Run 许可后才开始计时。</p>
 */
final class ToolExecutionCarrier implements AutoCloseable {

    private static final int MAX_FRAMEWORK_TASKS = 32;

    private final ExecutorService frameworkExecutor;
    private final ExecutorService handlerExecutor;
    private final Duration timeout;

    private ToolExecutionCarrier(
            ExecutorService frameworkExecutor,
            ExecutorService handlerExecutor,
            Duration timeout
    ) {
        this.frameworkExecutor = frameworkExecutor;
        this.handlerExecutor = handlerExecutor;
        this.timeout = timeout;
    }

    static ToolExecutionCarrier create(
            boolean virtualThreadsEnabled,
            int maxConcurrentTools,
            Duration timeout
    ) {
        if (virtualThreadsEnabled) {
            ThreadFactory factory = Thread.ofVirtual()
                    .name("salmon-agent-tool-vt-", 0)
                    .factory();
            ExecutorService executor = Executors.newThreadPerTaskExecutor(factory);
            return new ToolExecutionCarrier(executor, executor, timeout);
        }
        ExecutorService framework = Executors.newFixedThreadPool(
                MAX_FRAMEWORK_TASKS, namedFactory("salmon-agent-tool"));
        ExecutorService handler = Executors.newFixedThreadPool(
                maxConcurrentTools, namedFactory("salmon-agent-tool-handler"));
        return new ToolExecutionCarrier(framework, handler, timeout);
    }

    private static java.util.concurrent.ThreadFactory namedFactory(String prefix) {
        return runnable -> {
            Thread thread = new Thread(runnable, prefix);
            thread.setDaemon(true);
            return thread;
        };
    }

    ExecutorService frameworkExecutor() {
        return frameworkExecutor;
    }

    Duration timeout() {
        return timeout;
    }

    /**
     * 仅对已经通过批次调度和 Governor 的 Handler 开始实际执行计时。
     */
    ToolCallResponse call(ToolCallRequest request, ToolCallHandler handler)
            throws InterruptedException, TimeoutException {
        Future<ToolCallResponse> future = handlerExecutor.submit(() -> handler.call(request));
        try {
            return future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(cause);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw ex;
        }
    }

    @Override
    public void close() {
        if (handlerExecutor != frameworkExecutor) {
            handlerExecutor.shutdownNow();
        }
        frameworkExecutor.shutdownNow();
    }
}

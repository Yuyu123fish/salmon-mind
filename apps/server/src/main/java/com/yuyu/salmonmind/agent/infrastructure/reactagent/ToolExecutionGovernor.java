package com.yuyu.salmonmind.agent.infrastructure.reactagent;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Agent 进程内的工具调用许可器。它只负责领取和释放全局/Provider 名额，
 * 不拥有任务线程或 Future；实际任务执行由独立 carrier 负责。
 * Governor 允许跨 Run 的工具在达到全局/Provider 上限时排队，避免把正常等待误报成
 * {@code TOOL_CONCURRENCY_LIMIT_REACHED}。
 */
final class ToolExecutionGovernor {

    static final String SEARCH_API = "SEARCH_API";

    private final Semaphore global;
    private final Map<String, Semaphore> webProviders;

    ToolExecutionGovernor(int maxConcurrentTools, int maxConcurrentPerWebProvider) {
        if (maxConcurrentTools < 1 || maxConcurrentTools > 4) {
            throw new IllegalArgumentException("全局工具并发上限必须在 1 到 4 之间");
        }
        if (maxConcurrentPerWebProvider < 1 || maxConcurrentPerWebProvider > maxConcurrentTools) {
            throw new IllegalArgumentException("单网页 Provider 并发上限不能超过全局工具并发上限");
        }
        this.global = new Semaphore(maxConcurrentTools, true);
        this.webProviders = Map.of(SEARCH_API,
                new Semaphore(maxConcurrentPerWebProvider, true));
    }

    /**
     * 非阻塞领取一次工具执行许可；任一层失败都不会访问外部 Handler。
     */
    Permit tryAcquire(String toolName) {
        if (!global.tryAcquire()) {
            return null;
        }
        String provider = providerOf(toolName);
        Semaphore providerSemaphore = provider == null ? null : webProviders.get(provider);
        if (providerSemaphore != null && !providerSemaphore.tryAcquire()) {
            global.release();
            return null;
        }
        return new Permit(providerSemaphore);
    }

    /**
     * 在给定时间内排队领取许可；等待从工具真正进入 Handler 前发生。
     *
     * @return 成功领取的许可，超时返回 null
     */
    Permit acquire(String toolName, Duration timeout) throws InterruptedException {
        long waitNanos = timeout == null ? 0L : Math.max(0L, timeout.toNanos());
        long deadline = System.nanoTime() + waitNanos;
        if (!global.tryAcquire(waitNanos, TimeUnit.NANOSECONDS)) {
            return null;
        }
        String provider = providerOf(toolName);
        Semaphore providerSemaphore = provider == null ? null : webProviders.get(provider);
        if (providerSemaphore == null) {
            return new Permit(null);
        }
        long remaining = Math.max(0L, deadline - System.nanoTime());
        if (!providerSemaphore.tryAcquire(remaining, TimeUnit.NANOSECONDS)) {
            global.release();
            return null;
        }
        return new Permit(providerSemaphore);
    }

    private static String providerOf(String toolName) {
        return switch (toolName) {
            case "search_web_searchapi" -> SEARCH_API;
            default -> null;
        };
    }

    final class Permit implements AutoCloseable {
        private final Semaphore provider;
        private boolean released;

        private Permit(Semaphore provider) {
            this.provider = provider;
        }

        @Override
        public synchronized void close() {
            if (released) {
                return;
            }
            released = true;
            if (provider != null) {
                provider.release();
            }
            global.release();
        }
    }
}

package com.yuyu.salmonmind.conversation.application;

import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 按 Conversation ID 分片的进程内串行队列：同一 Conversation 的打开、恢复、发送与重试互斥执行，
 * 不同 Conversation 使用各自独立的锁，不被全局锁串行化。锁不区分读写，保证队列内操作
 * 看到上一次操作已提交的 JSONL 与 PostgreSQL 状态。仅适用于单 Server 进程；
 * 数据库的 partial unique index 仍负责阻止绕过队列产生多个 RUNNING Run。
 */
@Component
class ConversationExecutionQueue {

    // 锁按 Conversation 惰性创建并长期保留；Conversation 数量受产品规模约束，不做淘汰
    private final ConcurrentMap<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();

    /** 在指定 Conversation 的队列中执行任务并返回结果；排队线程阻塞等待前序操作完成。 */
    <T> T execute(UUID conversationId, Supplier<T> task) {
        ReentrantLock lock = locks.computeIfAbsent(conversationId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            return task.get();
        } finally {
            lock.unlock();
        }
    }
}

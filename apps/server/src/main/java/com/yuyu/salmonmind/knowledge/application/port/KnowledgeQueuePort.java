package com.yuyu.salmonmind.knowledge.application.port;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Knowledge 专属 Redis Stream Adapter；队列业务身份不越过 knowledge 模块。
 * message 的 deliveryAttempt 只记录同一 Job 在消息层的自动重试次数，
 * 不会创建新的 Source Revision 或公开为用户处理次数。
 */
public interface KnowledgeQueuePort {

    /** 首次投递一个处理 Job。 */
    default String dispatch(UUID jobId, int attemptNumber) {
        return dispatch(jobId, attemptNumber, 1);
    }

    /** 投递同一处理 Job 的指定消息重试次数。 */
    String dispatch(UUID jobId, int attemptNumber, int deliveryAttempt);

    /** 读取尚未投递给 Consumer Group 的消息；超时只表示本轮没有新消息。 */
    List<QueueMessage> read(String consumer, int count, Duration timeout);

    /** 接管超过 idle 的 Pending；返回的 deliveryAttempt 必须体现本次 reclaim。 */
    List<QueueMessage> reclaim(String consumer, Duration idle, int count);

    /** 仅确认已经完成或已收束的消息；处理中的异常不得提前 ACK。 */
    void acknowledge(String messageId);

    /**
     * 业务终态提交后的消息收束：必须先 XACK，再尝试精确 XDEL。
     * XACK 失败会抛出队列不可用；XDEL 失败只返回待清理结果，不能回滚已提交业务。
     */
    default Settlement settle(String messageId) {
        acknowledge(messageId);
        return new Settlement(true, false);
    }

    /**
     * 读取有界的 ACK 后待删除候选；候选只代表曾经登记过清理意图，不能绕过 Worker 的
     * Pending 与 PostgreSQL 终态校验。
     */
    default List<CleanupCandidate> cleanupCandidates(int limit) {
        return List.of();
    }

    /** 只对 Worker 已证明安全的消息执行精确 XDEL，并清除对应的持久清理标记。 */
    default List<String> cleanupAcked(Collection<String> messageIds) {
        return List.of();
    }

    /** 有界清理已 ACK 但尚未 XDEL 的消息；保留旧适配器的兼容入口。 */
    default List<String> cleanupAcked(int limit) {
        return List.of();
    }

    /** Redis Stream 中的轻量业务消息，不包含正文、凭据或物理存储身份。 */
    record QueueMessage(String messageId, UUID jobId, int attemptNumber, int deliveryAttempt) {
    }

    /** 清理标记及其从 Stream 读取的业务身份；缺少身份表示消息已不存在或是坏消息。 */
    record CleanupCandidate(String messageId, UUID jobId, int attemptNumber) {
    }

    /**
     * 消息收束结果：acknowledged 表示 XACK 已成立，deleted 表示本轮 XDEL 已成功或幂等完成。
     * acknowledged=false 时消息仍可能 Pending；acknowledged=true 且 deleted=false 只允许进入
     * 删除 janitor，不得重新进入文档业务失败路径。
     */
    record Settlement(boolean acknowledged, boolean deleted) {
    }
}

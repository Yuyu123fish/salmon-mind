package com.yuyu.salmonmind.knowledge.application.port;

import java.time.Duration;
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

    /** Redis Stream 中的轻量业务消息，不包含正文、凭据或物理存储身份。 */
    record QueueMessage(String messageId, UUID jobId, int attemptNumber, int deliveryAttempt) {
    }
}

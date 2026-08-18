package com.yuyu.salmonmind.knowledge.infrastructure.redis;

import com.yuyu.salmonmind.knowledge.api.KnowledgeException;
import com.yuyu.salmonmind.knowledge.application.port.KnowledgeQueuePort;
import com.yuyu.salmonmind.persistence.redis.RedisClientProvider;
import com.yuyu.salmonmind.persistence.redis.RedisClientUnavailableException;
import org.redisson.api.AutoClaimResult;
import org.redisson.api.PendingEntry;
import org.redisson.api.RStream;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.redisson.client.RedisException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Knowledge 专属 Redis Stream Consumer Group。正常消费只读 Stream；数据库补投器
 * 负责 PENDING_DISPATCH 双写缝隙，Pending reclaim 只接管超时未 ACK 的消息。
 */
@Component
class RedisKnowledgeQueue implements KnowledgeQueuePort {

    static final String STREAM_KEY = "salmon:knowledge:ingestion";
    static final String GROUP = "salmon-knowledge-workers";

    private final RedisClientProvider redisClientProvider;
    private final String configuredStream;
    private final String configuredGroup;

    private volatile RStream<String, String> stream;
    private volatile boolean groupReady;

    RedisKnowledgeQueue(
            RedisClientProvider redisClientProvider,
            @Value("${salmon.knowledge.queue.stream:" + STREAM_KEY + "}") String configuredStream,
            @Value("${salmon.knowledge.queue.group:" + GROUP + "}") String configuredGroup
    ) {
        this.redisClientProvider = redisClientProvider;
        this.configuredStream = configuredStream;
        this.configuredGroup = configuredGroup;
    }

    @Override
    public String dispatch(UUID jobId, int attemptNumber) {
        return dispatch(jobId, attemptNumber, 1);
    }

    @Override
    public String dispatch(UUID jobId, int attemptNumber, int deliveryAttempt) {
        try {
            ensureGroup();
            StreamMessageId id = stream().add(StreamAddArgs.entries(
                    "jobId", jobId.toString(),
                    "attempt", Integer.toString(attemptNumber),
                    "deliveryAttempt", Integer.toString(Math.max(1, deliveryAttempt))));
            return id.toString();
        } catch (RedisClientUnavailableException | RedisException ex) {
            resetConnectionState();
            throw unavailable(ex);
        }
    }

    @Override
    public List<QueueMessage> read(String consumer, int count, Duration timeout) {
        try {
            ensureGroup();
            Map<StreamMessageId, Map<String, String>> messages = stream().readGroup(
                    configuredGroup, consumer,
                    StreamReadGroupArgs.neverDelivered().count(Math.max(1, count)).timeout(timeout));
            return convert(messages, false);
        } catch (RedisClientUnavailableException | RedisException ex) {
            resetConnectionState();
            throw unavailable(ex);
        }
    }

    @Override
    public List<QueueMessage> reclaim(String consumer, Duration idle, int count) {
        try {
            ensureGroup();
            AutoClaimResult<String, String> result = stream().autoClaim(
                    configuredGroup, consumer, Math.max(1, idle.toMillis()), TimeUnit.MILLISECONDS,
                    StreamMessageId.MIN, Math.max(1, count));
            return convert(result == null ? Map.of() : result.getMessages(), true);
        } catch (RedisClientUnavailableException | RedisException ex) {
            resetConnectionState();
            throw unavailable(ex);
        }
    }

    @Override
    public void acknowledge(String messageId) {
        try {
            stream().ack(configuredGroup, parseMessageId(messageId));
        } catch (RedisClientUnavailableException | RedisException ex) {
            resetConnectionState();
            throw unavailable(ex);
        }
    }

    private synchronized void ensureGroup() {
        if (groupReady) {
            return;
        }
        RStream<String, String> current = stream();
        try {
            if (current.size() == 0) {
                StreamMessageId bootstrap = current.add(StreamAddArgs.entry("bootstrap", "1"));
                try {
                    current.createGroup(configuredGroup, StreamMessageId.NEWEST);
                } finally {
                    current.remove(bootstrap);
                }
            } else {
                current.createGroup(configuredGroup, StreamMessageId.NEWEST);
            }
        } catch (RedisException ex) {
            // Group 已存在是正常的进程重启路径；下一次 read 会验证真实连接。
            String message = ex.getMessage();
            if (message == null || !message.toLowerCase().contains("busygroup")) {
                throw ex;
            }
        }
        groupReady = true;
    }

    private RStream<String, String> stream() {
        if (stream == null) {
            stream = redisClientProvider.client().getStream(configuredStream);
        }
        return stream;
    }

    private synchronized void resetConnectionState() {
        groupReady = false;
        stream = null;
    }

    private List<QueueMessage> convert(Map<StreamMessageId, Map<String, String>> messages, boolean reclaimed) {
        List<QueueMessage> result = new ArrayList<>();
        for (Map.Entry<StreamMessageId, Map<String, String>> entry : messages.entrySet()) {
            Map<String, String> values = entry.getValue();
            try {
                String messageId = entry.getKey().toString();
                int deliveryAttempt = parseDeliveryAttempt(values.get("deliveryAttempt"));
                if (reclaimed) {
                    // Redis Pending Entry 的 delivery count 与 Stream 同寿命，不能用
                    // 有过期时间的旁路 key，否则长期 Pending 会把自动重试次数重置。
                    long reclaimedCount = deliveryCount(entry.getKey());
                    deliveryAttempt = Math.max(deliveryAttempt,
                            reclaimedCount > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) reclaimedCount);
                }
                result.add(new QueueMessage(
                        messageId,
                        UUID.fromString(values.get("jobId")),
                        Integer.parseInt(values.get("attempt")),
                        deliveryAttempt));
            } catch (RedisClientUnavailableException | RedisException ex) {
                // 读取持久投递次数失败是队列不可用，不是坏消息；必须让外层保留 Pending。
                throw ex;
            } catch (RuntimeException ex) {
                // 非业务消息无法安全处理，仍由 Worker ACK，避免 poison message 阻塞整个 Group。
                result.add(new QueueMessage(entry.getKey().toString(), new UUID(0, 0), -1, -1));
            }
        }
        return result;
    }

    private static int parseDeliveryAttempt(String value) {
        return value == null ? 1 : Math.max(1, Integer.parseInt(value));
    }

    private long deliveryCount(StreamMessageId messageId) {
        List<PendingEntry> entries = stream().listPending(
                configuredGroup, messageId, messageId, 1);
        if (entries.isEmpty()) {
            return 1;
        }
        return Math.max(1, entries.get(0).getLastTimeDelivered());
    }

    private static StreamMessageId parseMessageId(String value) {
        String[] parts = value.split("-", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Redis Stream message id 无效");
        }
        return new StreamMessageId(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
    }

    private static KnowledgeException unavailable(Throwable ex) {
        return new KnowledgeException(KnowledgeException.Code.KNOWLEDGE_QUEUE_UNAVAILABLE,
                "Knowledge 队列暂时不可用", ex);
    }
}

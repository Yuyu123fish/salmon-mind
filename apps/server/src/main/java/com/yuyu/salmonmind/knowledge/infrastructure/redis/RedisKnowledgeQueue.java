package com.yuyu.salmonmind.knowledge.infrastructure.redis;

import com.yuyu.salmonmind.knowledge.api.KnowledgeException;
import com.yuyu.salmonmind.knowledge.application.port.KnowledgeQueuePort;
import com.yuyu.salmonmind.persistence.redis.RedisClientProvider;
import com.yuyu.salmonmind.persistence.redis.RedisClientUnavailableException;
import org.redisson.api.AutoClaimResult;
import org.redisson.api.PendingEntry;
import org.redisson.api.RSet;
import org.redisson.api.RStream;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.redisson.client.RedisException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/**
 * Knowledge 专属 Redis Stream Consumer Group。正常消费只读 Stream；数据库补投器
 * 负责 PENDING_DISPATCH 双写缝隙，Pending reclaim 只接管超时未 ACK 的消息。
 */
@Component
class RedisKnowledgeQueue implements KnowledgeQueuePort {

    static final String STREAM_KEY = "salmon:knowledge:ingestion";
    static final String GROUP = "salmon-knowledge-workers";
    private static final String CLEANUP_PENDING_SUFFIX = ":cleanup-pending";

    private final RedisClientProvider redisClientProvider;
    private final String configuredStream;
    private final String configuredGroup;
    private final int cleanupMaxAttempts;
    private final ConcurrentLinkedQueue<String> cleanupPending = new ConcurrentLinkedQueue<>();

    private volatile RStream<String, String> stream;
    private volatile RSet<String> cleanupPendingSet;
    private volatile boolean groupReady;

    RedisKnowledgeQueue(
            RedisClientProvider redisClientProvider,
            @Value("${salmon.knowledge.queue.stream:" + STREAM_KEY + "}") String configuredStream,
            @Value("${salmon.knowledge.queue.group:" + GROUP + "}") String configuredGroup,
            @Value("${salmon.knowledge.worker.cleanup-max-attempts:3}") int cleanupMaxAttempts
    ) {
        this.redisClientProvider = redisClientProvider;
        this.configuredStream = configuredStream;
        this.configuredGroup = configuredGroup;
        if (cleanupMaxAttempts < 1 || cleanupMaxAttempts > 5) {
            throw new IllegalArgumentException("Knowledge 消息清理重试次数必须在 1 到 5 之间");
        }
        this.cleanupMaxAttempts = cleanupMaxAttempts;
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

    @Override
    public Settlement settle(String messageId) {
        // 先把清理意图写入 Redis 持久集合，覆盖 XACK 成功后进程在 XDEL 前崩溃的窗口。
        // 标记写不进去时不执行 XACK，让消息继续保持 Pending 并由下一轮恢复。
        if (!rememberCleanup(messageId)) {
            throw unavailable(new IllegalStateException("无法持久化 Knowledge 清理标记"));
        }
        // XACK 是业务提交点之后的第一步；失败时必须保留 Pending，不能继续 XDEL。
        acknowledge(messageId);
        boolean deleted = false;
        for (int attempt = 0; attempt < cleanupMaxAttempts; attempt++) {
            try {
                stream().remove(parseMessageId(messageId));
                deleted = true;
                break;
            } catch (RedisClientUnavailableException | RedisException ex) {
                resetConnectionState();
            }
        }
        if (!deleted) {
            // XACK 已完成，不能把消息重新当作业务失败；持久标记供后续 janitor 重试 XDEL。
        } else {
            forgetCleanup(messageId);
        }
        return new Settlement(true, deleted);
    }

    @Override
    public List<CleanupCandidate> cleanupCandidates(int limit) {
        int maximum = Math.max(1, Math.min(limit, 256));
        Set<String> candidates = new LinkedHashSet<>();
        for (int i = 0; i < maximum; i++) {
            String messageId = cleanupPending.poll();
            if (messageId == null) {
                break;
            }
            candidates.add(messageId);
        }
        if (candidates.size() < maximum) {
            try {
                Iterator<String> iterator = cleanupPendingSet().iterator(maximum - candidates.size());
                while (iterator.hasNext() && candidates.size() < maximum) {
                    candidates.add(iterator.next());
                }
            } catch (RuntimeException ex) {
                // Redis 暂时不可用时仍先处理本地兜底队列；持久集合会在下一轮重试。
                resetConnectionState();
            }
        }
        List<CleanupCandidate> result = new ArrayList<>();
        for (String messageId : candidates) {
            result.add(readCleanupCandidate(messageId));
        }
        return List.copyOf(result);
    }

    @Override
    public List<String> cleanupAcked(Collection<String> messageIds) {
        Set<String> candidates = new LinkedHashSet<>(messageIds);
        if (candidates.size() > 256) {
            candidates = new LinkedHashSet<>(candidates.stream().limit(256).toList());
        }
        List<String> remaining = new ArrayList<>();
        for (String messageId : candidates) {
            boolean deleted = false;
            boolean pending = false;
            for (int attempt = 0; attempt < cleanupMaxAttempts; attempt++) {
                try {
                    if (!stream().listPending(configuredGroup, parseMessageId(messageId),
                            parseMessageId(messageId), 1).isEmpty()) {
                        pending = true;
                        break;
                    }
                    stream().remove(parseMessageId(messageId));
                    deleted = true;
                    break;
                } catch (RedisClientUnavailableException | RedisException ex) {
                    resetConnectionState();
                }
            }
            if (pending || !deleted) {
                remaining.add(messageId);
            } else {
                forgetCleanup(messageId);
            }
        }
        remaining.forEach(cleanupPending::offer);
        return List.copyOf(remaining);
    }

    @Override
    public List<String> cleanupAcked(int limit) {
        return cleanupAcked(cleanupCandidates(limit).stream()
                .map(CleanupCandidate::messageId)
                .toList());
    }

    /**
     * 在 XACK 前登记清理意图。持久集合是进程重启后的权威候选来源，本地队列只用于减少当前
     * 进程的等待；因此 Redis 集合写失败时返回 false，调用方不得继续 XACK。
     */
    private boolean rememberCleanup(String messageId) {
        try {
            cleanupPendingSet().add(messageId);
            cleanupPending.offer(messageId);
            return true;
        } catch (RuntimeException ex) {
            resetConnectionState();
            return false;
        }
    }

    /** 删除已经完成的清理标记；删除失败只留下可再次处理的幂等候选。 */
    private void forgetCleanup(String messageId) {
        cleanupPending.remove(messageId);
        try {
            cleanupPendingSet().remove(messageId);
        } catch (RuntimeException ex) {
            resetConnectionState();
        }
    }

    private CleanupCandidate readCleanupCandidate(String messageId) {
        Map<StreamMessageId, Map<String, String>> entries = stream().range(
                parseMessageId(messageId), parseMessageId(messageId));
        if (entries.isEmpty()) {
            return new CleanupCandidate(messageId, null, -1);
        }
        Map<String, String> values = entries.values().iterator().next();
        try {
            return new CleanupCandidate(messageId,
                    UUID.fromString(values.get("jobId")),
                    Integer.parseInt(values.get("attempt")));
        } catch (RuntimeException ex) {
            // 只能由已登记过的坏消息进入这里；没有可验证的 Job 身份时按不存在 Job 处理，
            // 但仍需先通过 Pending 检查，避免误删尚未 ACK 的消息。
            return new CleanupCandidate(messageId, null, -1);
        }
    }

    private RSet<String> cleanupPendingSet() {
        if (cleanupPendingSet == null) {
            cleanupPendingSet = redisClientProvider.client().getSet(configuredStream + CLEANUP_PENDING_SUFFIX);
        }
        return cleanupPendingSet;
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
        cleanupPendingSet = null;
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

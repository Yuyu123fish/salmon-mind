package com.yuyu.salmonmind.agent.infrastructure.reactagent;

import org.redisson.api.RBucket;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;

import java.time.Duration;
import java.time.Instant;

/**
 * Agent-owned ReactAgent Checkpoint Lease 管理器。
 *
 * <p>RedisSaver 的四类可恢复状态（meta、reverse、content、leaf）共享同一个绝对过期窗口。
 * 执行期 lock 不参与业务 Lease。复用前必须检查身份链、内容、叶子和 TTL；失败时只缩短
 * 残留 Key 的寿命，不触碰 JSONL 或 PostgreSQL，因为后两者仍是 Conversation 的权威恢复源。
 */
final class CheckpointLeaseManager {

    static final String THREAD_ID_FIELD = "thread_id";
    static final String RELEASED_FIELD = "is_released";
    static final String THREAD_NAME_FIELD = "thread_name";

    private static final Duration CLEANUP_GRACE = Duration.ofMinutes(5);

    private final RedissonClient redisson;
    private final Duration ttl;
    private final int cleanupMaxAttempts;

    CheckpointLeaseManager(RedissonClient redisson, Duration ttl, int cleanupMaxAttempts) {
        if (ttl.compareTo(Duration.ofMinutes(5)) < 0 || ttl.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException("Checkpoint Lease TTL 必须在 5 分钟到 7 天之间");
        }
        if (cleanupMaxAttempts < 1 || cleanupMaxAttempts > 5) {
            throw new IllegalArgumentException("Checkpoint 残留清理次数必须在 1 到 5 之间");
        }
        this.redisson = redisson;
        this.ttl = ttl;
        this.cleanupMaxAttempts = cleanupMaxAttempts;
    }

    /**
     * 校验可恢复状态的双向身份、内容、叶子和四类 Key 的正 TTL；成功后整体滑动 Lease。
     * 任一检查失败只返回不可复用诊断，不删除 JSONL 或数据库状态。
     */
    LeaseInspection inspect(String externalThreadId, String expectedLeaf) {
        try {
            RMap<String, String> meta = meta(externalThreadId);
            String internalThreadId = meta.get(THREAD_ID_FIELD);
            if (internalThreadId == null || internalThreadId.isBlank()) {
                return LeaseInspection.invalid("META_MISSING");
            }
            if ("true".equalsIgnoreCase(meta.get(RELEASED_FIELD))) {
                return LeaseInspection.invalid("META_RELEASED");
            }
            RMap<String, String> reverse = reverse(internalThreadId);
            if (!externalThreadId.equals(reverse.get(THREAD_NAME_FIELD))) {
                return LeaseInspection.invalid("REVERSE_MISMATCH");
            }
            RBucket<String> content = content(internalThreadId);
            if (!content.isExists() || content.get() == null) {
                return LeaseInspection.invalid("CONTENT_MISSING");
            }
            String actualLeaf = leaf(externalThreadId).get();
            if (expectedLeaf == null || !expectedLeaf.equals(actualLeaf)) {
                return LeaseInspection.invalid("LEAF_MISMATCH");
            }
            if (!positiveTtl(meta) || !positiveTtl(reverse)
                    || !positiveTtl(content) || !positiveTtl(leaf(externalThreadId))) {
                return LeaseInspection.invalid("LEASE_MISSING");
            }
            refresh(externalThreadId, internalThreadId, true);
            return new LeaseInspection(true, internalThreadId, "OK");
        } catch (RedisException ex) {
            throw ex;
        }
    }

    /** 读取 SalmonMind 叶子标记；存在活动内部线程时同时刷新完整 Lease。 */
    String readLeaf(String externalThreadId) {
        try {
            String value = leaf(externalThreadId).get();
            String internalThreadId = activeInternalThreadId(externalThreadId);
            if (internalThreadId != null) {
                refresh(externalThreadId, internalThreadId, false);
            }
            return value;
        } catch (RedisException ex) {
            throw ex;
        }
    }

    /** 写入新的 JSONL 叶子标记，并让它与 meta/reverse/content 使用同一过期时刻。 */
    void writeLeaf(String externalThreadId, String leafId) {
        String internalThreadId = activeInternalThreadId(externalThreadId);
        if (internalThreadId == null) {
            throw new IllegalStateException("Checkpoint 尚未建立，无法写入叶子标记");
        }
        Instant deadline = Instant.now().plus(ttl);
        leaf(externalThreadId).set(leafId);
        refreshAt(externalThreadId, internalThreadId, deadline, true);
    }

    /** 在 RedisSaver 成功读写后刷新现有 Lease；不因刷新而创建缺失状态。 */
    void refreshExisting(String externalThreadId) {
        String internalThreadId = activeInternalThreadId(externalThreadId);
        if (internalThreadId != null) {
            refresh(externalThreadId, internalThreadId, false);
        }
    }

    /** release 后只缩短残留寿命；不删除仍可能被 JSONL 恢复流程需要的业务历史。 */
    void shortenResidual(String externalThreadId, String knownInternalThreadId) {
        if (knownInternalThreadId == null || knownInternalThreadId.isBlank()) {
            return;
        }
        Instant deadline = Instant.now().plus(CLEANUP_GRACE);
        RuntimeException last = null;
        for (int attempt = 0; attempt < cleanupMaxAttempts; attempt++) {
            try {
                refreshAt(externalThreadId, knownInternalThreadId, deadline, false);
                return;
            } catch (RuntimeException ex) {
                last = ex;
            }
        }
        if (last != null) {
            throw last;
        }
    }

    /** 读取未 release 的内部线程身份，供释放后的残留缩短使用。 */
    String activeInternalThreadId(String externalThreadId) {
        String internal = meta(externalThreadId).get(THREAD_ID_FIELD);
        if (internal == null || internal.isBlank()
                || "true".equalsIgnoreCase(meta(externalThreadId).get(RELEASED_FIELD))) {
            return null;
        }
        return internal;
    }

    private void refresh(String externalThreadId, String internalThreadId, boolean requireLeaf) {
        refreshAt(externalThreadId, internalThreadId, Instant.now().plus(ttl), requireLeaf);
    }

    private void refreshAt(
            String externalThreadId, String internalThreadId, Instant deadline, boolean requireLeaf
    ) {
        RMap<String, String> meta = meta(externalThreadId);
        RMap<String, String> reverse = reverse(internalThreadId);
        RBucket<String> content = content(internalThreadId);
        RBucket<String> leaf = leaf(externalThreadId);
        long deadlineMillis = deadline.toEpochMilli();
        meta.expireAt(deadlineMillis);
        reverse.expireAt(deadlineMillis);
        content.expireAt(deadlineMillis);
        if (requireLeaf || leaf.isExists()) {
            leaf.expireAt(deadlineMillis);
        }
    }

    private static boolean positiveTtl(org.redisson.api.RExpirable value) {
        return value.remainTimeToLive() > 0;
    }

    @SuppressWarnings("unchecked")
    private RMap<String, String> meta(String externalThreadId) {
        return (RMap<String, String>) (RMap<?, ?>) redisson.getMap(CheckpointKeyspace.meta(externalThreadId));
    }

    @SuppressWarnings("unchecked")
    private RMap<String, String> reverse(String internalThreadId) {
        return (RMap<String, String>) (RMap<?, ?>) redisson.getMap(CheckpointKeyspace.reverse(internalThreadId));
    }

    @SuppressWarnings("unchecked")
    private RBucket<String> content(String internalThreadId) {
        return (RBucket<String>) (RBucket<?>) redisson.getBucket(CheckpointKeyspace.content(internalThreadId));
    }

    @SuppressWarnings("unchecked")
    private RBucket<String> leaf(String externalThreadId) {
        return (RBucket<String>) (RBucket<?>) redisson.getBucket(CheckpointKeyspace.leaf(externalThreadId));
    }

    record LeaseInspection(boolean reusable, String internalThreadId, String reason) {
        static LeaseInspection invalid(String reason) {
            return new LeaseInspection(false, null, reason);
        }
    }
}

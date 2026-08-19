package com.yuyu.salmonmind.conversation.infrastructure.jsonl;

import com.yuyu.salmonmind.conversation.api.Entry;
import com.yuyu.salmonmind.conversation.application.port.ConversationHistoryRepository;
import com.yuyu.salmonmind.conversation.domain.ConversationHistory;
import com.yuyu.salmonmind.persistence.redis.RedisClientProvider;
import com.yuyu.salmonmind.conversation.infrastructure.jsonl.ConversationSnapshotCodec.Snapshot;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * JSONL 权威历史的 Redis Snapshot 装饰层。它只缓存完整、已解析的历史，
 * 每次命中前都读取 JSONL 文件版本；任何 Redis、codec、TTL 或大小问题都退化为权威 JSONL。
 * 应用层仍只注入一个 {@link ConversationHistoryRepository}，Compaction 偏移校验始终直达 JSONL。
 */
@Component
@Primary
class ConversationSnapshotCache implements ConversationHistoryRepository {

    private static final Logger log = LoggerFactory.getLogger(ConversationSnapshotCache.class);

    private final JsonlConversationHistoryRepository delegate;
    private final RedisClientProvider redisClientProvider;
    private final ConversationSnapshotCodec codec;
    private final ConversationCacheProperties properties;

    ConversationSnapshotCache(
            JsonlConversationHistoryRepository delegate,
            RedisClientProvider redisClientProvider,
            ConversationSnapshotCodec codec,
            ConversationCacheProperties properties
    ) {
        this.delegate = delegate;
        this.redisClientProvider = redisClientProvider;
        this.codec = codec;
        this.properties = properties;
    }

    @Override
    public void create(UUID conversationId, java.time.Instant createdAt) {
        delegate.create(conversationId, createdAt);
        evictQuietly(conversationId, "create");
    }

    @Override
    public void append(UUID conversationId, Entry entry) {
        delegate.append(conversationId, entry);
        evictQuietly(conversationId, "append");
    }

    @Override
    public ConversationHistory read(UUID conversationId) {
        if (!properties.enabled()) {
            return delegate.read(conversationId);
        }

        JsonlAuthorityVersion before = authorityVersionOrNull(conversationId);
        if (before != null) {
            ConversationHistory cached = readCached(conversationId, before);
            if (cached != null) {
                return cached;
            }
        }

        ConversationHistory loaded = delegate.read(conversationId);
        JsonlAuthorityVersion after = authorityVersionOrNull(conversationId);
        if (before == null || after == null) {
            return loaded;
        }

        // torn-tail 修复或外部变化会改变文件版本；最多再读一次，绝不把不稳定结果写入缓存。
        if (!before.equals(after)) {
            loaded = delegate.read(conversationId);
            JsonlAuthorityVersion stable = authorityVersionOrNull(conversationId);
            if (stable == null || !after.equals(stable)) {
                return loaded;
            }
            after = stable;
        }
        writeCachedQuietly(conversationId, after, loaded);
        return loaded;
    }

    @Override
    public boolean validateCompaction(UUID conversationId, UUID entryId, long seq, long byteOffset) {
        return delegate.validateCompaction(conversationId, entryId, seq, byteOffset);
    }

    @Override
    public void deleteOrphan(UUID conversationId) {
        try {
            delegate.deleteOrphan(conversationId);
        } finally {
            evictQuietly(conversationId, "delete-orphan");
        }
    }

    private JsonlAuthorityVersion authorityVersionOrNull(UUID conversationId) {
        try {
            return delegate.authorityVersion(conversationId);
        } catch (RuntimeException ex) {
            // 缺失文件等权威错误仍由 delegate.read 给出稳定的 Conversation 错误。
            log.debug("Conversation snapshot authority version unavailable: {}", "history-unavailable");
            return null;
        }
    }

    private ConversationHistory readCached(UUID conversationId, JsonlAuthorityVersion authorityVersion) {
        try {
            byte[] payload = bucket(conversationId).get();
            if (payload == null) {
                return null;
            }
            if (payload.length > properties.maxEntryBytes()) {
                evictQuietly(conversationId, "payload-too-large");
                return null;
            }
            Snapshot snapshot = codec.decode(payload);
            if (!conversationId.equals(snapshot.conversationId())
                    || !authorityVersion.equals(snapshot.authorityVersion())) {
                evictQuietly(conversationId, "version-or-identity-mismatch");
                return null;
            }
            return snapshot.history();
        } catch (RuntimeException ex) {
            log.debug("Conversation snapshot cache read degraded: {}", "cache-read-failed");
            evictQuietly(conversationId, "cache-read-failed");
            return null;
        }
    }

    private void writeCachedQuietly(
            UUID conversationId, JsonlAuthorityVersion authorityVersion, ConversationHistory history) {
        try {
            byte[] payload = codec.encode(conversationId, authorityVersion, history);
            if (payload.length > properties.maxEntryBytes()) {
                evictQuietly(conversationId, "payload-too-large");
                return;
            }
            bucket(conversationId).set(payload, properties.ttl().toMillis(), TimeUnit.MILLISECONDS);
        } catch (RuntimeException ex) {
            log.debug("Conversation snapshot cache write degraded: {}", "cache-write-failed");
        }
    }

    private void evictQuietly(UUID conversationId, String reason) {
        if (!properties.enabled()) {
            return;
        }
        try {
            bucket(conversationId).delete();
        } catch (RuntimeException ex) {
            log.debug("Conversation snapshot cache eviction degraded: {}", reason);
        }
    }

    private RBucket<byte[]> bucket(UUID conversationId) {
        RedissonClient client = redisClientProvider.client();
        return client.getBucket(properties.key(conversationId), ByteArrayCodec.INSTANCE);
    }
}

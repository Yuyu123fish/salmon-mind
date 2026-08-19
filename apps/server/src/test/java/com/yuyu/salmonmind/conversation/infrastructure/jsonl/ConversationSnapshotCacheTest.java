package com.yuyu.salmonmind.conversation.infrastructure.jsonl;

import com.yuyu.salmonmind.conversation.api.Entry;
import com.yuyu.salmonmind.conversation.api.UserMessagePayload;
import com.yuyu.salmonmind.persistence.redis.RedisClientUnavailableException;
import com.yuyu.salmonmind.conversation.domain.ConversationHistory;
import com.yuyu.salmonmind.persistence.redis.RedisClientProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Snapshot Cache 单元测试：验证命中前版本检查、失效失败降级、损坏 payload 回源和关闭开关短路。
 * Redis 命令本身留给真实 Redis 集成测试验证。
 */
class ConversationSnapshotCacheTest {

    private final UUID conversationId = UUID.randomUUID();
    private final JsonlConversationHistoryRepository delegate = mock(JsonlConversationHistoryRepository.class);
    private final RedisClientProvider redisClientProvider = mock(RedisClientProvider.class);
    private final RedissonClient redisson = mock(RedissonClient.class);
    @SuppressWarnings("unchecked")
    private final RBucket<byte[]> bucket = mock(RBucket.class);
    private final ConversationSnapshotCodec codec = new ConversationSnapshotCodec(new JsonlCodec());
    private ConversationSnapshotCache cache;
    private byte[] storedPayload;

    @BeforeEach
    void setUp() {
        when(redisClientProvider.client()).thenReturn(redisson);
        when(redisson.<byte[]>getBucket(anyString(), same(org.redisson.client.codec.ByteArrayCodec.INSTANCE)))
                .thenReturn(bucket);
        doAnswer(invocation -> {
            storedPayload = invocation.getArgument(0);
            return null;
        }).when(bucket).set(any(byte[].class), anyLong(), same(TimeUnit.MILLISECONDS));
        when(bucket.get()).thenAnswer(ignored -> storedPayload);
        cache = new ConversationSnapshotCache(
                delegate,
                redisClientProvider,
                codec,
                new ConversationCacheProperties(true, Duration.ofMinutes(10), 1024 * 1024, "test:conversation:"));
    }

    @Test
    void cacheMissReadsAuthorityOnceAndSecondReadHitsSameVersionSnapshot() {
        JsonlAuthorityVersion version = new JsonlAuthorityVersion(128L, 10L, "file-1");
        ConversationHistory history = history("第一问", 100L);
        when(delegate.authorityVersion(conversationId)).thenReturn(version);
        when(delegate.read(conversationId)).thenReturn(history);

        assertThat(cache.read(conversationId)).isEqualTo(history);
        assertThat(cache.read(conversationId)).isEqualTo(history);

        verify(delegate).read(conversationId);
        assertThat(storedPayload).isNotEmpty();
    }

    @Test
    void staleSnapshotIsRejectedWhenEvictionFailsAfterAppend() {
        JsonlAuthorityVersion before = new JsonlAuthorityVersion(128L, 10L, "file-1");
        JsonlAuthorityVersion after = new JsonlAuthorityVersion(256L, 20L, "file-1");
        ConversationHistory oldHistory = history("旧问题", 100L);
        ConversationHistory newHistory = history("新问题", 200L);
        when(delegate.authorityVersion(conversationId)).thenReturn(before, before, after);
        when(delegate.read(conversationId)).thenReturn(oldHistory, newHistory);
        when(bucket.delete()).thenThrow(new RuntimeException("模拟 Redis 失效失败"));

        cache.read(conversationId);
        cache.append(conversationId, entry(1L, "新问题"));

        assertThat(cache.read(conversationId)).isEqualTo(newHistory);
        verify(delegate, org.mockito.Mockito.times(2)).read(conversationId);
    }

    @Test
    void corruptedPayloadFallsBackToAuthorityRead() {
        JsonlAuthorityVersion version = new JsonlAuthorityVersion(128L, 10L, "file-1");
        ConversationHistory history = history("回源", 100L);
        when(delegate.authorityVersion(conversationId)).thenReturn(version);
        when(delegate.read(conversationId)).thenReturn(history);
        storedPayload = "not-a-snapshot".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(cache.read(conversationId)).isEqualTo(history);
        verify(delegate).read(conversationId);
    }

    @Test
    void unavailableRedisFallsBackToAuthorityRead() {
        JsonlAuthorityVersion version = new JsonlAuthorityVersion(128L, 10L, "file-1");
        ConversationHistory history = history("Redis 不可用", 100L);
        when(delegate.authorityVersion(conversationId)).thenReturn(version);
        when(delegate.read(conversationId)).thenReturn(history);
        when(redisClientProvider.client()).thenThrow(new RedisClientUnavailableException("Redis 未配置"));

        assertThat(cache.read(conversationId)).isEqualTo(history);
        verify(delegate).read(conversationId);
    }

    @Test
    void disabledCacheDoesNotTouchRedis() {
        cache = new ConversationSnapshotCache(
                delegate,
                redisClientProvider,
                codec,
                new ConversationCacheProperties(false, Duration.ofMinutes(10), 1024 * 1024, "test:conversation:"));
        ConversationHistory history = history("仅 JSONL", 100L);
        when(delegate.read(conversationId)).thenReturn(history);

        assertThat(cache.read(conversationId)).isEqualTo(history);
        verifyNoInteractions(redisClientProvider);
    }

    private ConversationHistory history(String text, long offset) {
        Entry entry = entry(1L, text);
        return new ConversationHistory(
                new ConversationHistory.Header(1, conversationId, Instant.parse("2026-08-01T00:00:00Z")),
                List.of(entry), List.of(offset));
    }

    private Entry entry(long seq, String text) {
        return new Entry(
                1, conversationId, UUID.randomUUID(), seq, null,
                Entry.EntryType.USER_MESSAGE, Instant.parse("2026-08-01T00:00:00Z"),
                new UserMessagePayload(text, UUID.randomUUID()));
    }
}

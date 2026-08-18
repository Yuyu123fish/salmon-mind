package com.yuyu.salmonmind.conversation.infrastructure.jsonl;

import com.yuyu.salmonmind.conversation.api.Entry;
import com.yuyu.salmonmind.conversation.api.UserMessagePayload;
import com.yuyu.salmonmind.conversation.domain.ConversationHistory;
import com.yuyu.salmonmind.persistence.redis.RedissonClientProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用真实 Redis 验证 Snapshot key、TTL、命中、追加后的版本拒绝、损坏回源和超限旁路。
 * 测试只操作临时 Conversation 目录，不触碰应用或 Agent 的其他 keyspace。
 */
@Testcontainers
class ConversationSnapshotCacheIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    private RedissonClientProvider provider;

    @AfterEach
    void closeProvider() {
        if (provider != null) {
            provider.close();
        }
    }

    @Test
    void reusesUnchangedSnapshotAndRejectsItAfterAppend() throws Exception {
        Path dataDir = Files.createTempDirectory("salmon-mind-cache-test-");
        try {
            CountingJsonlRepository delegate = newDelegate(dataDir);
            ConversationSnapshotCache cache = newCache(delegate, 1024 * 1024);
            UUID conversationId = UUID.randomUUID();
            cache.create(conversationId, Instant.parse("2026-08-01T00:00:00Z"));
            Entry first = entry(conversationId, 1L, null, "第一问");
            cache.append(conversationId, first);

            ConversationHistory firstRead = cache.read(conversationId);
            ConversationHistory secondRead = cache.read(conversationId);
            assertThat(firstRead.entries()).containsExactly(first);
            assertThat(secondRead).isEqualTo(firstRead);
            assertThat(delegate.readCount).isEqualTo(1);
            assertThat(redis().getBucket(key(conversationId), ByteArrayCodec.INSTANCE).remainTimeToLive())
                    .isGreaterThan(0L);

            Entry second = entry(conversationId, 2L, first.id(), "第二问");
            cache.append(conversationId, second);
            ConversationHistory afterAppend = cache.read(conversationId);
            assertThat(afterAppend.entries()).containsExactly(first, second);
            assertThat(delegate.readCount).isEqualTo(2);
        } finally {
            deleteTree(dataDir);
        }
    }

    @Test
    void corruptedRedisPayloadFallsBackWithoutChangingAuthority() throws Exception {
        Path dataDir = Files.createTempDirectory("salmon-mind-cache-corrupt-");
        try {
            CountingJsonlRepository delegate = newDelegate(dataDir);
            ConversationSnapshotCache cache = newCache(delegate, 1024 * 1024);
            UUID conversationId = UUID.randomUUID();
            cache.create(conversationId, Instant.parse("2026-08-01T00:00:00Z"));
            Entry entry = entry(conversationId, 1L, null, "权威内容");
            cache.append(conversationId, entry);
            cache.read(conversationId);

            redis().getBucket(key(conversationId), ByteArrayCodec.INSTANCE)
                    .set("corrupted".getBytes(StandardCharsets.UTF_8), 10, java.util.concurrent.TimeUnit.MINUTES);

            ConversationHistory recovered = cache.read(conversationId);
            assertThat(recovered.entries()).containsExactly(entry);
            assertThat(delegate.readCount).isEqualTo(2);
        } finally {
            deleteTree(dataDir);
        }
    }

    @Test
    void oversizedSnapshotDoesNotGetTruncatedOrCached() throws Exception {
        Path dataDir = Files.createTempDirectory("salmon-mind-cache-size-");
        try {
            CountingJsonlRepository delegate = newDelegate(dataDir);
            ConversationSnapshotCache cache = newCache(delegate, 128);
            UUID conversationId = UUID.randomUUID();
            cache.create(conversationId, Instant.parse("2026-08-01T00:00:00Z"));
            Entry entry = entry(conversationId, 1L, null, "x".repeat(512));
            cache.append(conversationId, entry);

            ConversationHistory firstRead = cache.read(conversationId);
            ConversationHistory secondRead = cache.read(conversationId);
            assertThat(firstRead.entries()).containsExactly(entry);
            assertThat(secondRead.entries()).containsExactly(entry);
            assertThat(delegate.readCount).isEqualTo(2);
            assertThat(redis().getBucket(key(conversationId), ByteArrayCodec.INSTANCE).get()).isNull();
        } finally {
            deleteTree(dataDir);
        }
    }

    @Test
    void reportsBoundedHotReadComparisonWithoutAbsolutePassThreshold() throws Exception {
        Path dataDir = Files.createTempDirectory("salmon-mind-cache-measure-");
        try {
            CountingJsonlRepository delegate = newDelegate(dataDir);
            UUID conversationId = UUID.randomUUID();
            delegate.create(conversationId, Instant.parse("2026-08-01T00:00:00Z"));
            UUID parentId = null;
            for (int i = 1; i <= 2000; i++) {
                Entry entry = entry(conversationId, i, parentId, "消息-" + i + "-" + "x".repeat(80));
                delegate.append(conversationId, entry);
                parentId = entry.id();
            }

            ConversationSnapshotCache enabled = newCache(delegate, 4 * 1024 * 1024);
            ConversationHistory warm = enabled.read(conversationId);
            List<Long> cacheOn = measure(20, () -> enabled.read(conversationId));
            ConversationSnapshotCache disabled = new ConversationSnapshotCache(
                    delegate,
                    provider,
                    new ConversationSnapshotCodec(new JsonlCodec()),
                    new ConversationCacheProperties(false, Duration.ofMinutes(10), 4 * 1024 * 1024,
                            "test:conversation:"));
            List<Long> cacheOff = measure(20, () -> disabled.read(conversationId));

            assertThat(warm.entries()).hasSize(2000);
            assertThat(disabled.read(conversationId).entries()).hasSize(2000);
            System.out.printf(
                    "Conversation cache hot-read sample: entries=%d bytes=%d samples=%d onMedianNanos=%d "
                            + "onRangeNanos=%d..%d offMedianNanos=%d offRangeNanos=%d..%d%n",
                    warm.entries().size(), Files.size(delegate.fileOf(conversationId)), cacheOn.size(),
                    median(cacheOn), cacheOn.stream().mapToLong(Long::longValue).min().orElse(0),
                    cacheOn.stream().mapToLong(Long::longValue).max().orElse(0), median(cacheOff),
                    cacheOff.stream().mapToLong(Long::longValue).min().orElse(0),
                    cacheOff.stream().mapToLong(Long::longValue).max().orElse(0));
        } finally {
            deleteTree(dataDir);
        }
    }

    private static List<Long> measure(int samples, Runnable action) {
        return IntStream.range(0, samples)
                .mapToObj(ignored -> {
                    long started = System.nanoTime();
                    action.run();
                    return System.nanoTime() - started;
                })
                .toList();
    }

    private static long median(List<Long> samples) {
        List<Long> sorted = samples.stream().sorted().toList();
        return sorted.get(sorted.size() / 2);
    }

    private CountingJsonlRepository newDelegate(Path dataDir) {
        provider = new RedissonClientProvider(redisUrl(), "", true);
        return new CountingJsonlRepository(dataDir, new JsonlCodec());
    }

    private ConversationSnapshotCache newCache(CountingJsonlRepository delegate, long maxEntryBytes) {
        return new ConversationSnapshotCache(
                delegate,
                provider,
                new ConversationSnapshotCodec(new JsonlCodec()),
                new ConversationCacheProperties(true, Duration.ofMinutes(10), maxEntryBytes, "test:conversation:"));
    }

    private RedissonClient redis() {
        return provider.client();
    }

    private String redisUrl() {
        return "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379);
    }

    private static String key(UUID conversationId) {
        return "test:conversation:" + conversationId;
    }

    private static Entry entry(UUID conversationId, long seq, UUID parentId, String text) {
        return new Entry(
                1, conversationId, UUID.randomUUID(), seq, parentId,
                Entry.EntryType.USER_MESSAGE, Instant.parse("2026-08-01T00:00:00Z"),
                new UserMessagePayload(text, UUID.randomUUID()));
    }

    private static void deleteTree(Path root) throws Exception {
        if (Files.exists(root)) {
            try (var paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                });
            }
        }
    }

    private static final class CountingJsonlRepository extends JsonlConversationHistoryRepository {
        private int readCount;

        private CountingJsonlRepository(Path dataRoot, JsonlCodec codec) {
            super(dataRoot, codec);
        }

        @Override
        public ConversationHistory read(UUID conversationId) {
            readCount++;
            return super.read(conversationId);
        }
    }
}

package com.yuyu.salmonmind.knowledge.infrastructure.redis;

import com.yuyu.salmonmind.knowledge.application.port.UploadSessionRepository;
import com.yuyu.salmonmind.knowledge.domain.PartReceipt;
import com.yuyu.salmonmind.knowledge.domain.UploadSession;
import com.yuyu.salmonmind.knowledge.domain.UploadSessionStatus;
import com.yuyu.salmonmind.persistence.redis.RedissonClientProvider;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 Redis codec/TTL/Receipt 集成证据；payload 只含会话元数据，不含 part bytes。 */
@Testcontainers
class KnowledgeUploadSessionIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Test
    void receiptIsAtomicAndPayloadContainsNoFileBytes() {
        KnowledgeUploadProperties properties = new KnowledgeUploadProperties(true, 1_000_000, 65_536, 2,
                Duration.ofMinutes(10), Duration.ofHours(2), Duration.ofHours(1), Duration.ofHours(1),
                Duration.ofSeconds(5), 10, "salmon:knowledge:upload:v1:", Duration.ofSeconds(5), Duration.ofSeconds(2));
        RedissonClientProvider provider = new RedissonClientProvider(
                "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379), "", true);
        RedisUploadSessionRepository repository = new RedisUploadSessionRepository(provider, properties);
        UUID workspaceId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Instant now = Instant.now();
        UploadSession session = new UploadSession(sessionId, workspaceId, "large.txt", "text/plain", 3,
                "file|3", 0, 65_536, 1, 2, now, now.plusSeconds(600), now.plusSeconds(3600),
                UploadSessionStatus.UPLOADING, "knowledge/upload-parts/v1/test/", "knowledge/upload-finals/v1/test.bin",
                Map.of(), Map.of(), null, null, null);
        try {
            repository.create(session);
            UploadSessionRepository.PartReservationResult reservation = repository.reservePart(workspaceId, sessionId,
                    1, 3, "a".repeat(64), now, now.plusSeconds(60));
            UploadSession saved = repository.commitReceipt(workspaceId, sessionId, 1, reservation.token(),
                    new PartReceipt(1, "knowledge/upload-parts/v1/test/1-a.part", 3, "a".repeat(64), now), now);

            assertThat(saved.confirmedBytes()).isEqualTo(3);
            assertThat(repository.find(UUID.randomUUID(), sessionId)).isNull();
            String payload = provider.client().<String>getBucket(properties.keyPrefix() + "session:" + sessionId).get();
            assertThat(payload).contains("receipts").doesNotContain("part bytes").doesNotContain("abc");
        } finally {
            provider.close();
        }
    }
}

package com.yuyu.salmonmind.knowledge.application;

import com.yuyu.salmonmind.knowledge.domain.PartReceipt;
import com.yuyu.salmonmind.knowledge.domain.UploadSession;
import com.yuyu.salmonmind.knowledge.domain.UploadSessionStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Upload Session 的长度、Receipt 汇总和 hard expiry 规则。 */
class KnowledgeUploadSessionTest {

    @Test
    void expectedPartLengthAndConfirmedBytesAreDeterministic() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-19T00:00:00Z");
        UploadSession session = new UploadSession(id, UUID.randomUUID(), "file.txt", "text/plain", 10,
                "fingerprint", 1, 4, 3, 2, now, now.plusSeconds(60), now.plusSeconds(600),
                UploadSessionStatus.UPLOADING, "parts/", "final.bin",
                java.util.Map.of(1, new PartReceipt(1, "parts/1", 4, "a".repeat(64), now)),
                java.util.Map.of(), null, null, null);

        assertThat(session.expectedPartLength(1)).isEqualTo(4);
        assertThat(session.expectedPartLength(3)).isEqualTo(2);
        assertThat(session.confirmedBytes()).isEqualTo(4);
        assertThat(session.orderedReceipts()).extracting(PartReceipt::partNumber).containsExactly(1);
        assertThat(session.renewed(now.plusSeconds(30), java.time.Duration.ofSeconds(900)).expiresAt())
                .isEqualTo(session.hardExpiresAt());
    }
}

package com.yuyu.salmonmind.knowledge.application.retrieval;

import com.yuyu.salmonmind.knowledge.application.port.EvidenceIndexPort;
import com.yuyu.salmonmind.knowledge.application.port.KnowledgeMetadataPort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** RRF 的纯规则门禁：重叠、单路、固定 k 和确定性 tie-break。 */
class RrfRankerTest {

    private static final String SHA = "a".repeat(64);

    @Test
    void fusesRanksWithoutAddingTechnicalScoresAndUsesStableTieBreak() {
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID third = UUID.fromString("00000000-0000-0000-0000-000000000003");
        Map<UUID, KnowledgeMetadataPort.ReadyEvidence> metadata = Map.of(
                first, ready(first), second, ready(second), third, ready(third));

        RrfRanker.Merge result = new RrfRanker().merge(
                List.of(hit(first, 1, 100.0), hit(second, 2, 0.01)),
                List.of(hit(first, 1, 0.02), hit(third, 2, 999.0)),
                metadata);

        assertThat(result.rrf()).extracting(RetrievalCandidate::id)
                .containsExactly(first, second, third);
        assertThat(result.rrf().get(0).rrfScore)
                .isEqualTo(2.0 / 61.0, org.assertj.core.data.Offset.offset(0.0000001));
        assertThat(result.rrf().get(1).rrfScore)
                .isEqualTo(1.0 / 62.0, org.assertj.core.data.Offset.offset(0.0000001));
        assertThat(result.rrf().get(2).rrfScore)
                .isEqualTo(1.0 / 62.0, org.assertj.core.data.Offset.offset(0.0000001));
    }

    private static EvidenceIndexPort.RankedEvidence hit(UUID id, int rank, double score) {
        return new EvidenceIndexPort.RankedEvidence(
                id, id, id, 0, "section", "正文 " + id, SHA, rank, score);
    }

    private static KnowledgeMetadataPort.ReadyEvidence ready(UUID id) {
        return new KnowledgeMetadataPort.ReadyEvidence(
                id, id, id, "doc-" + id, "section", SHA, 0, 3);
    }
}

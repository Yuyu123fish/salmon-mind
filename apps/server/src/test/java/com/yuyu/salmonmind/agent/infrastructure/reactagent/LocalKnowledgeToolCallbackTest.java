package com.yuyu.salmonmind.agent.infrastructure.reactagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.salmonmind.knowledge.retrieval.LocalKnowledgeRetriever;
import com.yuyu.salmonmind.knowledge.retrieval.LocalKnowledgeRetriever.LocalEvidence;
import com.yuyu.salmonmind.knowledge.retrieval.LocalKnowledgeRetriever.LocalKnowledgeReason;
import com.yuyu.salmonmind.knowledge.retrieval.LocalKnowledgeRetriever.LocalKnowledgeResult;
import com.yuyu.salmonmind.knowledge.retrieval.LocalKnowledgeRetriever.LocalKnowledgeStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

/** 验证生产本地 Knowledge Tool 的输入校验、结果边界和失败收束，不访问真实检索依赖。 */
class LocalKnowledgeToolCallbackTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void boundsEvidenceCountAndTextBeforeSerializing() throws Exception {
        LocalKnowledgeRetriever retriever = query -> new LocalKnowledgeResult(
                LocalKnowledgeStatus.SUCCESS,
                LocalKnowledgeReason.COMPLETE,
                List.of(
                        new LocalEvidence(UUID.randomUUID(), "manual.md", UUID.randomUUID(), "第 1 段", "x".repeat(5_000)),
                        new LocalEvidence(UUID.randomUUID(), "manual.md", UUID.randomUUID(), "第 2 段", "第二段"),
                        new LocalEvidence(UUID.randomUUID(), "manual.md", UUID.randomUUID(), "第 3 段", "第三段"),
                        new LocalEvidence(UUID.randomUUID(), "manual.md", UUID.randomUUID(), "第 4 段", "第四段"),
                        new LocalEvidence(UUID.randomUUID(), "manual.md", UUID.randomUUID(), "第 5 段", "第五段"),
                        new LocalEvidence(UUID.randomUUID(), "manual.md", UUID.randomUUID(), "第 6 段", "第六段")));

        LocalKnowledgeToolCallback callback = new LocalKnowledgeToolCallback(mapper, retriever);
        JsonNode result = mapper.readTree(callback.call("{\"query\":\"本地资料\"}"));

        assertThat(callback.getToolDefinition().name()).isEqualTo(LocalKnowledgeToolCallback.NAME);
        assertThat(result.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(result.path("reason").asText()).isEqualTo("COMPLETE");
        assertThat(result.path("evidences")).hasSize(5);
        assertThat(result.path("evidences").get(0).path("text").asText()).hasSize(4_001);
    }

    @Test
    void returnsStableStructuredFailureForInvalidInputOrRetrieverFailure() throws Exception {
        LocalKnowledgeToolCallback invalid = new LocalKnowledgeToolCallback(mapper, query -> {
            throw new AssertionError("无效输入不应调用 Retriever");
        });
        JsonNode invalidResult = mapper.readTree(invalid.call("{\"query\":\"" + "x".repeat(2_001) + "\"}"));
        assertThat(invalidResult.path("status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(invalidResult.path("reason").asText()).isEqualTo("INVALID_QUERY");

        LocalKnowledgeToolCallback failed = new LocalKnowledgeToolCallback(mapper, query -> {
            throw new IllegalStateException("依赖不可用");
        });
        JsonNode failedResult = mapper.readTree(failed.call("{\"query\":\"本地资料\"}"));
        assertThat(failedResult.path("status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(failedResult.path("reason").asText()).isEqualTo("RETRIEVAL_UNAVAILABLE");
        assertThat(failedResult.path("evidences")).isEmpty();
    }
}

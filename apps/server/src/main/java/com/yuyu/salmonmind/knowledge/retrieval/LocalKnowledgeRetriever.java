package com.yuyu.salmonmind.knowledge.retrieval;

import java.util.List;
import java.util.UUID;

/**
 * Agent 使用的本地资料检索边界。只返回有界、可追溯的最终 Evidence，
 * 不暴露 BM25/vector/RRF 技术分数、物理索引或模型响应。
 */
public interface LocalKnowledgeRetriever {

    /** 查询当前 Workspace 已发布的本地资料；空库和外部依赖失败都以结构化结果返回。 */
    LocalKnowledgeResult retrieve(String query);

    public record LocalKnowledgeResult(
            LocalKnowledgeStatus status,
            LocalKnowledgeReason reason,
            List<LocalEvidence> evidences
    ) {
        public LocalKnowledgeResult {
            if (status == null || reason == null) {
                throw new IllegalArgumentException("本地检索状态不能为空");
            }
            evidences = evidences == null ? List.of() : List.copyOf(evidences);
        }
    }

    /** 工具可见的最终 Local Evidence；text 是不受信任资料，不是系统指令。 */
    public record LocalEvidence(
            UUID evidenceId,
            String documentName,
            UUID revisionId,
            String location,
            String text
    ) {
    }

    public enum LocalKnowledgeStatus {
        SUCCESS,
        DEGRADED,
        EMPTY,
        UNAVAILABLE
    }

    public enum LocalKnowledgeReason {
        NO_READY_DOCUMENTS,
        COMPLETE,
        NO_MATCH,
        VECTOR_UNAVAILABLE,
        RERANK_UNAVAILABLE,
        INDEX_UNAVAILABLE,
        READY_SCOPE_TOO_LARGE,
        INVALID_QUERY,
        RETRIEVAL_UNAVAILABLE,
        TOOL_BUDGET_EXCEEDED
    }
}

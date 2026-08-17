package com.yuyu.salmonmind.knowledge.api;

import java.util.List;
import java.util.UUID;

/**
 * Knowledge 诊断检索的 HTTP 表示。分数只用于开发诊断，不是概率或可靠性保证；
 * 各阶段正文均为短预览，不返回查询向量、完整文档向量或提供方原始响应。
 */
public record KnowledgeSearchResult(
        String policyVersion,
        SearchStatus status,
        SearchReason reason,
        SearchStage bm25,
        SearchStage vector,
        SearchStage rrf,
        SearchStage finalResults,
        List<String> executedStages,
        List<String> skippedStages
) {

    public KnowledgeSearchResult {
        if (policyVersion == null || policyVersion.isBlank() || status == null || reason == null) {
            throw new IllegalArgumentException("检索诊断状态不能为空");
        }
        bm25 = bm25 == null ? SearchStage.empty() : bm25;
        vector = vector == null ? SearchStage.empty() : vector;
        rrf = rrf == null ? SearchStage.empty() : rrf;
        finalResults = finalResults == null ? SearchStage.empty() : finalResults;
        executedStages = executedStages == null ? List.of() : List.copyOf(executedStages);
        skippedStages = skippedStages == null ? List.of() : List.copyOf(skippedStages);
    }

    public enum SearchStatus {
        SUCCESS,
        DEGRADED,
        EMPTY,
        UNAVAILABLE
    }

    public enum SearchReason {
        NO_READY_DOCUMENTS,
        COMPLETE,
        NO_MATCH,
        VECTOR_UNAVAILABLE,
        RERANK_UNAVAILABLE,
        INDEX_UNAVAILABLE,
        READY_SCOPE_TOO_LARGE
    }

    public record SearchStage(List<SearchHit> items) {
        public SearchStage {
            items = items == null ? List.of() : List.copyOf(items);
        }

        public static SearchStage empty() {
            return new SearchStage(List.of());
        }
    }

    public record SearchHit(
            UUID evidenceId,
            UUID sourceId,
            UUID revisionId,
            String documentName,
            String location,
            String text,
            Integer rank,
            Double score
    ) {
    }
}

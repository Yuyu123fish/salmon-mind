package com.yuyu.salmonmind.model.rerank;

import java.util.List;

/** 一次精排响应的最小稳定表示；正文始终由本地 Evidence 保留。 */
public record RerankResult(String provider, String model, List<ScoredDocument> results) {

    public RerankResult {
        if (provider == null || provider.isBlank() || model == null || model.isBlank()) {
            throw new IllegalArgumentException("Rerank provider/model 不能为空");
        }
        results = results == null ? List.of() : List.copyOf(results);
    }

    /** 提供方返回的候选输入下标和 relevance score。 */
    public record ScoredDocument(int index, double score) {
    }
}

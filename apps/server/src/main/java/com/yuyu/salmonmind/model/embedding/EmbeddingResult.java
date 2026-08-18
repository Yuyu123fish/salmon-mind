package com.yuyu.salmonmind.model.embedding;

import java.util.List;

/** 一批 Embedding 的模型身份和有序向量结果。 */
public record EmbeddingResult(String provider, String model, List<List<Float>> vectors) {

    public EmbeddingResult {
        vectors = vectors.stream().map(List::copyOf).toList();
        if (provider == null || provider.isBlank() || model == null || model.isBlank()) {
            throw new IllegalArgumentException("Embedding provider/model 不能为空");
        }
        for (List<Float> vector : vectors) {
            if (vector.size() != EmbeddingService.DIMENSIONS) {
                throw new IllegalArgumentException("Embedding 维数不符合固定合同");
            }
        }
    }
}

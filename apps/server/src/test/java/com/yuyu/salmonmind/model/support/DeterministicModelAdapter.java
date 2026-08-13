package com.yuyu.salmonmind.model.support;

import java.util.ArrayList;
import java.util.List;

import com.yuyu.salmonmind.model.ModelGateway;

/**
 * Reusable no-network model adapter for business-module tests.
 */
public final class DeterministicModelAdapter implements ModelGateway {

    private final String completionText;
    private final int embeddingDimensions;

    public DeterministicModelAdapter(String completionText, int embeddingDimensions) {
        if (completionText == null || completionText.isBlank()) {
            throw new IllegalArgumentException("completionText must not be blank");
        }
        if (embeddingDimensions < 1) {
            throw new IllegalArgumentException("embeddingDimensions must be positive");
        }
        this.completionText = completionText;
        this.embeddingDimensions = embeddingDimensions;
    }

    @Override
    public Completion complete(Prompt prompt) {
        return new Completion(completionText, "deterministic-chat");
    }

    @Override
    public EmbeddingBatch embed(EmbeddingInput input) {
        var embeddings = input.texts().stream()
                .map(this::embeddingFor)
                .toList();
        return new EmbeddingBatch("deterministic-embedding", embeddings);
    }

    private Embedding embeddingFor(String text) {
        int seed = text.hashCode();
        var values = new ArrayList<Double>(embeddingDimensions);
        for (int index = 0; index < embeddingDimensions; index++) {
            int rotated = Integer.rotateLeft(seed, index);
            values.add((rotated & 0xffff) / 65535.0);
        }
        return new Embedding(List.copyOf(values));
    }
}

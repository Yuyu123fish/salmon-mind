package com.yuyu.salmonmind.conversation.api;

import java.util.List;
import java.util.UUID;

/**
 * Agent 最终回答 payload，保存提供方、模型、可空用量与已验证的最小 Citation，不保存凭据。
 */
public record AssistantMessagePayload(
        String text,
        UUID runId,
        String provider,
        String model,
        TokenUsage usage,
        List<CitationPayload> citations
) implements EntryPayload {

    public AssistantMessagePayload {
        citations = citations == null ? List.of() : List.copyOf(citations);
    }

    /** 旧调用兼容：历史/测试构造未提供 Citation 时按空列表处理。 */
    public AssistantMessagePayload(
            String text, UUID runId, String provider, String model, TokenUsage usage
    ) {
        this(text, runId, provider, model, usage, List.of());
    }
}

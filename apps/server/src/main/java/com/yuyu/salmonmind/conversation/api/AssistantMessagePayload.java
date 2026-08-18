package com.yuyu.salmonmind.conversation.api;

import java.util.List;
import java.util.UUID;

/**
 * Agent 最终回答 payload，保存提供方、模型、可空用量、已验证 Citation 与有界展示 Trace，
 * 不保存凭据、原始工具数据或可重放 Agent 状态。
 */
public record AssistantMessagePayload(
        String text,
        UUID runId,
        String provider,
        String model,
        TokenUsage usage,
        List<CitationPayload> citations,
        List<RunTraceItemPayload> trace
) implements EntryPayload {

    public AssistantMessagePayload {
        citations = citations == null ? List.of() : List.copyOf(citations);
        trace = trace == null ? List.of() : List.copyOf(trace);
    }

    /** 旧调用兼容：已有代码提供 Citation 但没有 Trace 时按空列表处理。 */
    public AssistantMessagePayload(
            String text, UUID runId, String provider, String model, TokenUsage usage,
            List<CitationPayload> citations
    ) {
        this(text, runId, provider, model, usage, citations, List.of());
    }

    /** 旧调用兼容：历史/测试构造未提供 Citation 时按空列表处理。 */
    public AssistantMessagePayload(
            String text, UUID runId, String provider, String model, TokenUsage usage
    ) {
        this(text, runId, provider, model, usage, List.of(), List.of());
    }
}

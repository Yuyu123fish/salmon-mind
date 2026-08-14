package com.yuyu.salmonmind.conversation.api;

import java.util.UUID;

/**
 * Agent 最终回答 payload，保存提供方、模型与可空用量，不保存凭据。
 */
public record AssistantMessagePayload(
        String text,
        UUID runId,
        String provider,
        String model,
        TokenUsage usage
) implements EntryPayload {
}

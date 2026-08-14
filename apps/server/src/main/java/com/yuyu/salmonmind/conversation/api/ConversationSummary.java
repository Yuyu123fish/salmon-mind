package com.yuyu.salmonmind.conversation.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Conversation 列表项：元数据加上可空的最新 Run 摘要。
 */
public record ConversationSummary(
        UUID id,
        UUID workspaceId,
        String title,
        Run latestRun,
        Instant createdAt,
        Instant updatedAt
) {
}

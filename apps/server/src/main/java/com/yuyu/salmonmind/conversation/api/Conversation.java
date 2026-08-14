package com.yuyu.salmonmind.conversation.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Conversation 的稳定领域记录，由 PostgreSQL 元数据映射而来，不包含消息正文。
 */
public record Conversation(
        UUID id,
        UUID workspaceId,
        String title,
        int historyFormatVersion,
        UUID activeLeafEntryId,
        long lastConfirmedSeq,
        UUID latestCompactionEntryId,
        Long latestCompactionSeq,
        Long latestCompactionByteOffset,
        Instant createdAt,
        Instant updatedAt
) {
}

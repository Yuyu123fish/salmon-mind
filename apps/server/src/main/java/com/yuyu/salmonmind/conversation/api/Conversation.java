package com.yuyu.salmonmind.conversation.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Conversation 的稳定领域记录，由 PostgreSQL 元数据映射而来，不包含消息正文。
 * 不变量：latestCompactionEntryId / latestCompactionSeq / latestCompactionByteOffset
 * 三个压缩索引字段必须同时为空或同时非空；它们不是跨存储外键，
 * 与 JSONL 中的 Compaction Entry 形成可相互校验的逻辑索引。
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

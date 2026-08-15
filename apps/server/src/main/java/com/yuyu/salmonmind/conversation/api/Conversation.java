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
        /** 产品标题；新建为「新对话」，之后以 JSONL 最新 Title Entry 为权威覆盖。 */
        String title,
        /** 对应 JSONL Header 的格式版本；当前为 1，用于识别历史文件布局。 */
        int historyFormatVersion,
        /** 当前 Active Path 的叶子 Entry；空会话为 null。Title 不推进此字段。打开详情时由此回溯路径。 */
        UUID activeLeafEntryId,
        /** PostgreSQL 已确认的最大 Entry seq（含 Title）；空会话为 0。JSONL 领先时由 reconcile 推进；下一条 seq = 此值 + 1。 */
        long lastConfirmedSeq,
        /** Active Path 上最新 Compaction 的 Entry ID；与 seq、byteOffset 必须同空或同非空。 */
        UUID latestCompactionEntryId,
        /** 该 Compaction 的 seq，与 JSONL 对应行交叉校验。 */
        Long latestCompactionSeq,
        /** 该 Compaction 行在 JSONL 中的起始字节偏移；按此定位并校验 id/seq，不是跨存储外键。 */
        Long latestCompactionByteOffset,
        Instant createdAt,
        Instant updatedAt
) {
}

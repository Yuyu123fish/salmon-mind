package com.yuyu.salmonmind.conversation.api;

import java.util.List;
import java.util.UUID;

/**
 * 未来状态压缩预留的自包含检查点。本 Feature 只支持编码、读取与索引校验，不生成此 Entry。
 */
public record CompactionPayload(
        String summary,
        UUID coveredThroughEntryId,
        List<Entry> retainedTail,
        Long tokensBefore,
        TokenUsage usage
) implements EntryPayload {
}

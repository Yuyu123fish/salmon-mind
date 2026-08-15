package com.yuyu.salmonmind.conversation.api;

import java.util.List;
import java.util.UUID;

/**
 * 上下文压缩的自包含检查点：用 Summary 替换退出原文区，并嵌套保存近期原文 Retained Tail。
 * 主调用前输入达到预算时由协调器生成并追加；投影与下一次增量压缩直接使用本 payload，
 * 不再从 Active Path 回查或重切被摘要覆盖的历史。retainedTail 只含 User/Assistant Entry。
 */
public record CompactionPayload(
        String summary,
        UUID coveredThroughEntryId,
        // 压缩后保留的尾节点，用于后续的恢复重建
        List<Entry> retainedTail,
        Long tokensBefore,
        TokenUsage usage
) implements EntryPayload {
}

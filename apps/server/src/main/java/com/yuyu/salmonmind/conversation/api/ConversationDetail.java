package com.yuyu.salmonmind.conversation.api;

import java.util.List;

/**
 * Conversation 详情：元数据、按 Active Path 根到叶子排列的可见消息，以及可空待处理 Run。
 */
public record ConversationDetail(
        Conversation conversation,
        List<Entry> activePath,
        Run pendingRun
) {
}

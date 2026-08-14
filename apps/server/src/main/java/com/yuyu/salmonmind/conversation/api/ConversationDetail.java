package com.yuyu.salmonmind.conversation.api;

import java.util.List;

/**
 * Conversation 详情：元数据、按 Active Path 根到叶子排列的可见消息，以及可空待处理 Run。
 * pendingRun 只在活动叶子是待回答用户 Entry 且其 Run 未成功时非空，
 * 前端据此展示失败/中断状态与重试动作。
 */
public record ConversationDetail(
        Conversation conversation,
        List<Entry> activePath,
        Run pendingRun
) {
}

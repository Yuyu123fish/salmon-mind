package com.yuyu.salmonmind.conversation.api;

/**
 * Assistant Trace 中可持久化的工具请求展示详情。
 *
 * <p>它是 Agent 安全投影在 Conversation 边界的值对象，只用于历史与 SSE 展示，
 * 不能反向重建工具调用。网页选项的默认标记由 Agent 依据实际 Tool Callback 语义给出。
 */
public record ToolRequestDetailPayload(
        String querySummary,
        boolean querySummaryTruncated,
        String freshness,
        boolean freshnessDefaulted,
        Integer count,
        boolean countDefaulted
) {

    public ToolRequestDetailPayload {
        if (querySummary == null || querySummary.isBlank()) {
            throw new IllegalArgumentException("工具查询摘要不能为空");
        }
        if (count != null && count < 1) {
            throw new IllegalArgumentException("工具结果数量必须为正数");
        }
    }
}

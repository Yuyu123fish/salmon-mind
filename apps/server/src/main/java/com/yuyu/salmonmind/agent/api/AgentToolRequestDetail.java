package com.yuyu.salmonmind.agent.api;

/**
 * 工具调用的安全请求展示。它只保存三个检索工具允许公开的字段，不能用于重放工具调用。
 * 网页选项始终由 Agent 边界归一化；默认标记用于区分模型显式参数与工具默认语义。
 */
public record AgentToolRequestDetail(
        String querySummary,
        boolean querySummaryTruncated,
        String freshness,
        boolean freshnessDefaulted,
        Integer count,
        boolean countDefaulted
) {

    public AgentToolRequestDetail {
        if (querySummary == null || querySummary.isBlank()) {
            throw new IllegalArgumentException("工具查询摘要不能为空");
        }
        if (count != null && count < 1) {
            throw new IllegalArgumentException("工具结果数量必须为正数");
        }
    }
}

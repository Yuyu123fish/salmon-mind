package com.yuyu.salmonmind.agent.api;

/**
 * 一次 Agent Run 的有界展示轨迹项。它只承载允许向用户展示的 reasoning 与工具摘要，
 * 不包含系统提示词、原始工具参数/结果、Provider 响应或内部异常。
 *
 * <p>列表顺序就是展示顺序；reasoning 只使用 {@code text}，工具项只使用工具字段。
 * 该记录属于观察结果，不得重新投影进后续模型上下文。
 */
public record AgentRunTraceItem(
        Kind kind,
        String text,
        boolean truncated,
        String toolCallId,
        String toolName,
        ToolStatus toolStatus,
        String safeSummary,
        String stableErrorCode
) {

    public AgentRunTraceItem {
        if (kind == null) {
            throw new IllegalArgumentException("Trace kind 不能为空");
        }
        if (kind == Kind.REASONING && text == null) {
            throw new IllegalArgumentException("Reasoning Trace 文本不能为空");
        }
        if (kind == Kind.TOOL
                && (toolCallId == null || toolName == null || toolStatus == null || safeSummary == null)) {
            throw new IllegalArgumentException("Tool Trace 缺少稳定身份、状态或安全摘要");
        }
    }

    /** 创建一段可展示 reasoning；连续段是否合并由 Agent 内部收集器决定。 */
    public static AgentRunTraceItem reasoning(String text, boolean truncated) {
        return new AgentRunTraceItem(
                Kind.REASONING, text, truncated, null, null, null, null, null);
    }

    /** 创建一个按 Tool Call ID 更新的工具轨迹项。 */
    public static AgentRunTraceItem tool(
            String toolCallId,
            String toolName,
            ToolStatus status,
            String safeSummary,
            String stableErrorCode,
            boolean truncated
    ) {
        return new AgentRunTraceItem(
                Kind.TOOL, null, truncated, toolCallId, toolName, status, safeSummary, stableErrorCode);
    }

    public enum Kind {
        REASONING,
        TOOL
    }

    public enum ToolStatus {
        RUNNING,
        COMPLETED,
        FAILED
    }
}

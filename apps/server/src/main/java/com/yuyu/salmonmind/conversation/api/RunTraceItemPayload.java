package com.yuyu.salmonmind.conversation.api;

/**
 * Assistant Entry 中可长期展示的有界 Run Trace 项。Reasoning 与工具字段互斥使用；
 * 内容已经过 Agent 边界裁剪，不包含原始参数、结果、凭据、提示词或内部堆栈。
 *
 * <p>该 payload 只负责历史展示，不是 Agent Loop 重放记录，也不得进入模型上下文。
 */
public record RunTraceItemPayload(
        Kind kind,
        String text,
        boolean truncated,
        String toolCallId,
        String toolName,
        ToolStatus toolStatus,
        String safeSummary,
        String stableErrorCode,
        ToolRequestDetailPayload requestDetail,
        ToolOutcomeDetailPayload outcomeDetail
) {

    /** 兼容没有展示详情的既有 Trace payload。 */
    public RunTraceItemPayload(
            Kind kind,
            String text,
            boolean truncated,
            String toolCallId,
            String toolName,
            ToolStatus toolStatus,
            String safeSummary,
            String stableErrorCode
    ) {
        this(kind, text, truncated, toolCallId, toolName, toolStatus, safeSummary, stableErrorCode,
                null, null);
    }

    public RunTraceItemPayload {
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

    public static RunTraceItemPayload reasoning(String text, boolean truncated) {
        return new RunTraceItemPayload(
                Kind.REASONING, text, truncated, null, null, null, null, null, null, null);
    }

    public static RunTraceItemPayload tool(
            String toolCallId,
            String toolName,
            ToolStatus status,
            String safeSummary,
            String stableErrorCode,
            boolean truncated
    ) {
        return new RunTraceItemPayload(
                Kind.TOOL, null, truncated, toolCallId, toolName, status, safeSummary, stableErrorCode,
                null, null);
    }

    /** 创建带请求与终态详情的 Tool Trace；详情只用于展示和历史核验。 */
    public static RunTraceItemPayload tool(
            String toolCallId,
            String toolName,
            ToolStatus status,
            String safeSummary,
            String stableErrorCode,
            ToolRequestDetailPayload requestDetail,
            ToolOutcomeDetailPayload outcomeDetail,
            boolean truncated
    ) {
        return new RunTraceItemPayload(
                Kind.TOOL, null, truncated, toolCallId, toolName, status, safeSummary, stableErrorCode,
                requestDetail, outcomeDetail);
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

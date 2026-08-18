package com.yuyu.salmonmind.conversation.api;

/**
 * Assistant Trace 中可持久化的工具终态展示详情。
 *
 * <p>字段只来自 Agent 已确认的结构化结果或稳定拦截器错误；原始 Provider 响应、
 * 完整工具结果和异常堆栈不跨越 Conversation 边界。
 */
public record ToolOutcomeDetailPayload(
        String provider,
        ResultStatus resultStatus,
        String stableReasonCode,
        Integer sourceCount,
        long durationMillis,
        boolean degraded,
        boolean resultTruncated
) {

    public ToolOutcomeDetailPayload {
        if (sourceCount != null && sourceCount < 0) {
            throw new IllegalArgumentException("工具来源数不能为负数");
        }
        if (durationMillis < 0) {
            throw new IllegalArgumentException("工具耗时不能为负数");
        }
    }

    public enum ResultStatus {
        SUCCESS,
        DEGRADED,
        EMPTY,
        UNAVAILABLE
    }
}

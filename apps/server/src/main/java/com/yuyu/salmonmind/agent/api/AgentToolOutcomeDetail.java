package com.yuyu.salmonmind.agent.api;

/**
 * 工具终态的安全结果展示。字段来自平台拥有的结构化结果或稳定拦截器错误，
 * 不允许从 Provider 原始文本、请求头或异常堆栈推断。
 *
 * @param sourceCount 实际进入有界 Tool Result 的来源数；无法证明时为 null
 */
public record AgentToolOutcomeDetail(
        String provider,
        ResultStatus resultStatus,
        String stableReasonCode,
        Integer sourceCount,
        long durationMillis,
        boolean degraded,
        boolean resultTruncated
) {

    public AgentToolOutcomeDetail {
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

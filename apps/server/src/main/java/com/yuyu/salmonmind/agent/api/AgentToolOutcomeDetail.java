package com.yuyu.salmonmind.agent.api;

/**
 * 工具终态的安全结果展示。字段来自平台拥有的结构化结果或稳定拦截器错误，
 * 不允许从 Provider 原始文本、请求头或异常堆栈推断。
 *
 * @param sourceCount 实际进入有界 Tool Result 的来源数；无法证明时为 null
 * @param estimatedResultTokens 本次实际返回结果的保守 token 估算；旧 Trace 可为空
 * @param remainingInputTokens 上一次模型调用前的剩余输入空间；旧 Trace 可为空
 * @param contextCleaned 是否在模型调用前清理过较旧 Tool Result
 */
public record AgentToolOutcomeDetail(
        String provider,
        ResultStatus resultStatus,
        String stableReasonCode,
        Integer sourceCount,
        long durationMillis,
        boolean degraded,
        boolean resultTruncated,
        Long estimatedResultTokens,
        Long remainingInputTokens,
        boolean contextCleaned
) {

    /** 兼容旧 JSONL/包内调用方没有上下文计量字段的构造。 */
    public AgentToolOutcomeDetail(
            String provider,
            ResultStatus resultStatus,
            String stableReasonCode,
            Integer sourceCount,
            long durationMillis,
            boolean degraded,
            boolean resultTruncated
    ) {
        this(provider, resultStatus, stableReasonCode, sourceCount, durationMillis,
                degraded, resultTruncated, null, null, false);
    }

    public AgentToolOutcomeDetail {
        if (sourceCount != null && sourceCount < 0) {
            throw new IllegalArgumentException("工具来源数不能为负数");
        }
        if (durationMillis < 0) {
            throw new IllegalArgumentException("工具耗时不能为负数");
        }
        if (estimatedResultTokens != null && estimatedResultTokens < 0
                || remainingInputTokens != null && remainingInputTokens < 0) {
            throw new IllegalArgumentException("工具上下文 token 计量不能为负数");
        }
    }

    public enum ResultStatus {
        SUCCESS,
        DEGRADED,
        EMPTY,
        UNAVAILABLE
    }
}

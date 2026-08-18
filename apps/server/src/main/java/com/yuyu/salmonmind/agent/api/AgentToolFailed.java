package com.yuyu.salmonmind.agent.api;

/**
 * 一个工具调用失败结束的平台事件。工具失败不必然终止整个 Agent 运行：
 * 框架把失败转为工具错误结果送回模型，由模型决定继续回答或失败。
 * 事件只携带稳定错误码、用户可理解的安全消息和已证实的终态详情，不能携带
 * Provider 原文、请求参数或异常堆栈。
 *
 * @param toolCallId       本次 Tool Call 的稳定身份，与 started 事件一一对应
 * @param toolName         模型选择的工具名
 * @param outcomeDetail    已知的耗时、Provider 和结构化结果状态；未知字段保持为空
 * @param stableErrorCode  平台稳定错误码，供上层判断失败语义而非展示内部异常
 * @param safeMessage      有界安全失败文案
 */
public record AgentToolFailed(
        String toolCallId,
        String toolName,
        AgentToolOutcomeDetail outcomeDetail,
        String stableErrorCode,
        String safeMessage
) {

    /** 兼容没有结果详情的既有事件构造。 */
    public AgentToolFailed(
            String toolCallId,
            String toolName,
            long durationMillis,
            String stableErrorCode,
            String safeMessage
    ) {
        this(toolCallId, toolName,
                new AgentToolOutcomeDetail(null, null, stableErrorCode, null, durationMillis, false, false),
                stableErrorCode, safeMessage);
    }

    public AgentToolFailed {
        if (outcomeDetail == null) {
            outcomeDetail = new AgentToolOutcomeDetail(null, null, stableErrorCode, null, 0, false, false);
        }
    }

    public long durationMillis() {
        return outcomeDetail.durationMillis();
    }
}

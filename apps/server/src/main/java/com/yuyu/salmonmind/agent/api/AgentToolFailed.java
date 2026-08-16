package com.yuyu.salmonmind.agent.api;

/**
 * 一个工具调用失败结束的平台事件。工具失败不必然终止整个 Agent 运行：
 * 框架把失败转为工具错误结果送回模型，由模型决定继续回答或失败。
 *
 * @param toolCallId      与 {@link AgentToolStarted#toolCallId()} 相同的稳定 ID
 * @param toolName        工具名
 * @param durationMillis  本次工具执行耗时（毫秒）
 * @param stableErrorCode 稳定错误码（如 TOOL_EXECUTION_FAILED），不暴露内部堆栈
 * @param safeMessage     可理解的失败说明，已截断且不含敏感细节
 */
public record AgentToolFailed(
        String toolCallId,
        String toolName,
        long durationMillis,
        String stableErrorCode,
        String safeMessage
) {
}

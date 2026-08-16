package com.yuyu.salmonmind.agent.api;

/**
 * 一个工具调用成功结束的平台事件。
 *
 * @param toolCallId     与 {@link AgentToolStarted#toolCallId()} 相同的稳定 ID
 * @param toolName       工具名
 * @param durationMillis 本次工具执行耗时（毫秒）
 */
public record AgentToolCompleted(String toolCallId, String toolName, long durationMillis) {
}

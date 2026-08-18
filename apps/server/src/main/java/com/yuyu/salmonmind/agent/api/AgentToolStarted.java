package com.yuyu.salmonmind.agent.api;

/**
 * 一个工具调用开始执行时的平台事件。
 *
 * @param toolCallId      框架为本次工具调用分配的稳定 ID，与后续 completed/failed 事件一一对应
 * @param toolName        工具名，与模型可见的 Tool Definition 名称一致
 * @param safeQuerySummary 参数的安全摘要：已去除控制字符并截断，只用于状态展示，不构成完整参数
 */
public record AgentToolStarted(String toolCallId, String toolName, String safeQuerySummary) {
}

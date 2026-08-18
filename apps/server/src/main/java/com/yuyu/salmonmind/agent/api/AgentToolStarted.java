package com.yuyu.salmonmind.agent.api;

/**
 * 一个工具调用开始执行时的平台事件。Request Detail 在拦截器发出事件前生成，
 * 因此后续层只接收白名单投影，不会接触原始参数。
 *
 * @param toolCallId       框架为本次工具调用分配的稳定 ID，与后续 completed/failed 事件一一对应
 * @param toolName         工具名，与模型可见的 Tool Definition 名称一致
 * @param safeQuerySummary 有界安全摘要，只用于紧凑行展示
 * @param requestDetail    三个检索工具的白名单参数详情；未知或非法输入时为空
 */
public record AgentToolStarted(
        String toolCallId,
        String toolName,
        String safeQuerySummary,
        AgentToolRequestDetail requestDetail
) {

    /** 兼容没有请求详情的既有事件构造。 */
    public AgentToolStarted(String toolCallId, String toolName, String safeQuerySummary) {
        this(toolCallId, toolName, safeQuerySummary, null);
    }
}

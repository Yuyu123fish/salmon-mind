package com.yuyu.salmonmind.agent.api;

/**
 * 一个工具调用成功结束的平台事件。
 *
 * @param toolCallId     与 {@link AgentToolStarted#toolCallId()} 相同的稳定 ID
 * @param toolName       工具名
 * @param durationMillis 本次工具执行耗时（毫秒）
 * @param provider       来源工具的安全 Provider 标识；普通工具为空
 * @param sourceCount    本次结果回传模型的来源数量
 * @param truncated      是否因大小边界删除了完整来源项
 * @param degraded       来源结果是否处于降级状态
 */
public record AgentToolCompleted(
        String toolCallId,
        String toolName,
        long durationMillis,
        String provider,
        int sourceCount,
        boolean truncated,
        boolean degraded
) {

    /** 普通工具/旧测试构造兼容：没有来源状态。 */
    public AgentToolCompleted(String toolCallId, String toolName, long durationMillis) {
        this(toolCallId, toolName, durationMillis, null, 0, false, false);
    }
}

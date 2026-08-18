package com.yuyu.salmonmind.agent.api;

/** Agent 输出是否自然结束；长度收束不是一次 Agent 调用失败。 */
public enum AgentCompletionStatus {
    COMPLETE,
    INCOMPLETE_LENGTH
}

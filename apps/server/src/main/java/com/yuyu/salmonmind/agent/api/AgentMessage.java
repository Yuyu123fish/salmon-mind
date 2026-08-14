package com.yuyu.salmonmind.agent.api;

/**
 * 模型可见消息的稳定表示，避免向 conversation 模块暴露 Spring AI Message 类型。
 */
public record AgentMessage(Role role, String text) {

    public AgentMessage {
        if (role == null) {
            throw new IllegalArgumentException("role 不能为空");
        }
        if (text == null) {
            throw new IllegalArgumentException("text 不能为空");
        }
    }

    public enum Role {
        USER,
        ASSISTANT
    }
}

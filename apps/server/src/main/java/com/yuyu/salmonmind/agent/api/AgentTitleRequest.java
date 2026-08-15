package com.yuyu.salmonmind.agent.api;

import java.util.List;

/**
 * 首次标题生成请求。messages 是 conversation 侧按固定标题 Prompt 渲染后的完整模型输入
 * （指令 + 首次成功交互的 User/Assistant 内容）。
 */
public record AgentTitleRequest(List<AgentMessage> messages) {

    public AgentTitleRequest {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages 不能为空");
        }
        messages = List.copyOf(messages);
    }
}

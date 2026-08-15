package com.yuyu.salmonmind.agent.api;

import java.util.List;

/**
 * 上下文摘要请求。messages 是 conversation 侧按固定 Summary 结构渲染后的完整模型输入
 * （指令 + 历史或增量新增内容）；本契约不携带 Spring AI 类型。
 */
public record AgentSummaryRequest(List<AgentMessage> messages) {

    public AgentSummaryRequest {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages 不能为空");
        }
        messages = List.copyOf(messages);
    }
}

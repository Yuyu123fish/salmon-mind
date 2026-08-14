package com.yuyu.salmonmind.agent.api;

import java.util.List;
import java.util.UUID;

/**
 * 一次完整 Agent 调用的稳定请求。
 *
 * @param threadId              稳定的会话身份，由 Conversation ID 派生
 * @param expectedCheckpointLeafId 期望 Checkpoint 对应的 JSONL 叶子 Entry ID；
 *                                 首轮无父节点时为 {@code null}，表示不能复用
 * @param answerLeafId          预分配的回答叶子 Entry ID，成功后写回 Checkpoint 标记
 * @param modelVisibleMessages  从 JSONL Active Path 投影出的完整模型可见消息
 */
public record AgentRequest(
        String threadId,
        UUID expectedCheckpointLeafId,
        UUID answerLeafId,
        List<AgentMessage> modelVisibleMessages
) {

    public AgentRequest {
        if (threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("threadId 不能为空");
        }
        if (answerLeafId == null) {
            throw new IllegalArgumentException("answerLeafId 不能为空");
        }
        if (modelVisibleMessages == null || modelVisibleMessages.isEmpty()) {
            throw new IllegalArgumentException("modelVisibleMessages 不能为空");
        }
        modelVisibleMessages = List.copyOf(modelVisibleMessages);
    }
}

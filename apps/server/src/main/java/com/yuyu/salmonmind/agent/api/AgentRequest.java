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
 * @param checkpointPolicy      Checkpoint 复用策略；默认 {@link CheckpointPolicy#REUSE_IF_MATCH}，
 *                              工具启用后的轮次应显式传 {@link CheckpointPolicy#REBUILD_FROM_PROJECTION}
 */
public record AgentRequest(
        String threadId,
        UUID expectedCheckpointLeafId,
        UUID answerLeafId,
        List<AgentMessage> modelVisibleMessages,
        CheckpointPolicy checkpointPolicy
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
        // 缺省保持 Feature 002 的叶子匹配复用语义；未来工具轮次显式选择强制重建
        checkpointPolicy = checkpointPolicy == null ? CheckpointPolicy.REUSE_IF_MATCH : checkpointPolicy;
    }

    /** 兼容构造：显式保持叶子匹配的默认复用语义。 */
    public AgentRequest(
            String threadId,
            UUID expectedCheckpointLeafId,
            UUID answerLeafId,
            List<AgentMessage> modelVisibleMessages
    ) {
        this(threadId, expectedCheckpointLeafId, answerLeafId, modelVisibleMessages, CheckpointPolicy.REUSE_IF_MATCH);
    }
}

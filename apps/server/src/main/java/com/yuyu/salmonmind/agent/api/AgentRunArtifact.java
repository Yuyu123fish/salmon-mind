package com.yuyu.salmonmind.agent.api;

import java.util.List;
import java.util.UUID;

/**
 * Conversation 在 Assistant JSONL 成功追加后调用的 Run 产物确认边界。
 * 实现必须按回答 Entry ID 幂等确认；Conversation 不理解调用链文件布局。
 */
public interface AgentRunArtifact {

    /**
     * 确认 Assistant 引用对应的待发布产物。
     *
     * @param callChains Assistant JSONL 中的最小引用；空列表表示没有调用链
     * @param answerEntryId 已追加的 Assistant Entry 身份
     */
    void confirmCallChains(List<AgentCallChainReference> callChains, UUID answerEntryId);
}

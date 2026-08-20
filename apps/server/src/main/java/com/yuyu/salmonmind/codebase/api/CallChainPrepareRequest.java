package com.yuyu.salmonmind.codebase.api;

import java.util.List;
import java.util.UUID;

/**
 * Agent 请求准备一条不可见的初始调用链。
 *
 * <p>成功只表示 pending 文件已经形成，Assistant JSONL 追加后仍需调用 confirm 才会对
 * 列表、详情和后续 Agent 可见。</p>
 */
public record CallChainPrepareRequest(
        UUID repositoryId,
        RepositoryObservation expectedObservation,
        String name,
        List<CallChainNodeInput> nodes,
        List<CallChainEdgeInput> edges,
        UUID originConversationId,
        UUID originAnswerEntryId
) {
    public CallChainPrepareRequest {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }
}

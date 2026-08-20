package com.yuyu.salmonmind.codebase.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 调用链的安全读取投影。源码只能来自 Server 已保存的 Source Snapshot，不能通过该
 * 投影下载任意仓库文件。
 */
public record CallChainDetail(
        UUID id,
        UUID repositoryId,
        String repositoryName,
        String name,
        int nodeCount,
        int edgeCount,
        UUID originConversationId,
        UUID originAnswerEntryId,
        Instant createdAt,
        Instant updatedAt,
        List<CallChainNodeDetail> nodes,
        List<CallChainEdge> edges
) {
    public CallChainDetail {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }
}

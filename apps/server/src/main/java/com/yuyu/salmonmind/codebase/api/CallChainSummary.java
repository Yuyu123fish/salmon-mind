package com.yuyu.salmonmind.codebase.api;

import java.time.Instant;
import java.util.UUID;

/** 仓库入口使用的调用链摘要，不包含节点图或源码。 */
public record CallChainSummary(
        UUID id,
        UUID repositoryId,
        String repositoryName,
        String name,
        int nodeCount,
        int edgeCount,
        Instant createdAt,
        Instant updatedAt
) {
}

package com.yuyu.salmonmind.conversation.api;

import java.util.UUID;

/** Assistant JSONL 中的调用链最小引用；详情通过 codebase HTTP 重新读取。 */
public record CallChainReferencePayload(
        UUID id,
        UUID repositoryId,
        String name,
        int nodeCount,
        int edgeCount
) {
    public CallChainReferencePayload {
        if (id == null || repositoryId == null || name == null || name.isBlank()
                || nodeCount < 2 || edgeCount < 1) {
            throw new IllegalArgumentException("调用链引用不合法");
        }
    }
}

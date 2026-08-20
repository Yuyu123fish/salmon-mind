package com.yuyu.salmonmind.agent.api;

import java.util.UUID;

/**
 * Assistant 可携带的调用链最小引用。节点、边、源码和数据目录均不跨越 agent::api 边界。
 */
public record AgentCallChainReference(
        UUID id,
        UUID repositoryId,
        String name,
        int nodeCount,
        int edgeCount
) {
    public AgentCallChainReference {
        if (id == null || repositoryId == null || name == null || name.isBlank()
                || nodeCount < 2 || edgeCount < 1) {
            throw new IllegalArgumentException("调用链引用不合法");
        }
    }
}

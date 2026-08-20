package com.yuyu.salmonmind.codebase.api;

import java.util.UUID;

/**
 * Assistant 可以安全持久化的调用链最小引用。
 *
 * <p>它不包含节点、边、源码、绝对路径或待确认文件名；详情必须通过
 * {@code codebase::api} 按 Repository 与 Chain 身份重新读取。</p>
 */
public record CallChainReference(
        UUID id,
        UUID repositoryId,
        String name,
        int nodeCount,
        int edgeCount
) {
    public CallChainReference {
        if (id == null || repositoryId == null || name == null || name.isBlank()
                || nodeCount < 2 || edgeCount < 1) {
            throw new IllegalArgumentException("调用链引用不合法");
        }
    }
}

package com.yuyu.salmonmind.codebase.api;

import java.util.List;
import java.util.UUID;

/** 调用链详情中的节点、当时源码快照和历史 Revision。 */
public record CallChainNodeDetail(
        String nodeId,
        UUID revisionId,
        String language,
        String qualifiedSymbol,
        String signature,
        String summary,
        String sourceHash,
        String path,
        int startLine,
        int endLine,
        String source,
        RepositoryObservation observation,
        List<NodeRevisionView> revisions
) {
    public CallChainNodeDetail {
        revisions = revisions == null ? List.of() : List.copyOf(revisions);
    }
}

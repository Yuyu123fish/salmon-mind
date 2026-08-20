package com.yuyu.salmonmind.codebase.api;

import java.time.Instant;
import java.util.UUID;

/** 节点历史 Revision 的安全详情；源码内容通过选中节点详情提供。 */
public record NodeRevisionView(
        UUID id,
        UUID parentRevisionId,
        String sourceHash,
        String path,
        int startLine,
        int endLine,
        RepositoryObservation observation,
        Instant observedAt
) {
}

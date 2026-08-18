package com.yuyu.salmonmind.agent.api;

import java.time.Instant;
import java.util.UUID;

/** 本地 Evidence 的有界回看来源。 */
public record AgentLocalRetrievedSource(
        String referenceId,
        UUID evidenceId,
        UUID revisionId,
        String documentName,
        String location,
        Instant retrievedAt,
        String excerptKind,
        String sourceExcerpt,
        String originToolCallId,
        Integer resultPosition,
        Integer providerRank
) implements AgentRetrievedSource {

    /** 兼容没有首次召回位置的既有来源。 */
    public AgentLocalRetrievedSource(
            String referenceId, UUID evidenceId, UUID revisionId, String documentName, String location,
            Instant retrievedAt, String excerptKind, String sourceExcerpt
    ) {
        this(referenceId, evidenceId, revisionId, documentName, location, retrievedAt, excerptKind,
                sourceExcerpt, null, null, null);
    }
}

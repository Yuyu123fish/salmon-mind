package com.yuyu.salmonmind.conversation.api;

import java.time.Instant;
import java.util.UUID;

/** 本地 Evidence 的有界来源回看记录。 */
public record LocalRetrievedSourcePayload(
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
) implements RetrievedSourcePayload {

    /** 兼容没有首次召回位置的既有来源。 */
    public LocalRetrievedSourcePayload(
            String referenceId,
            UUID evidenceId,
            UUID revisionId,
            String documentName,
            String location,
            Instant retrievedAt,
            String excerptKind,
            String sourceExcerpt
    ) {
        this(referenceId, evidenceId, revisionId, documentName, location, retrievedAt, excerptKind,
                sourceExcerpt, null, null, null);
    }

    @Override
    public String kind() {
        return "local";
    }
}

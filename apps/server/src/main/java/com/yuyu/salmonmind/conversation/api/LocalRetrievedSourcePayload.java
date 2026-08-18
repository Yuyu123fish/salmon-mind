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
        String sourceExcerpt
) implements RetrievedSourcePayload {

    @Override
    public String kind() {
        return "local";
    }
}

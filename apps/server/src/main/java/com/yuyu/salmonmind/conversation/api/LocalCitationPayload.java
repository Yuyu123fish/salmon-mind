package com.yuyu.salmonmind.conversation.api;

import java.util.UUID;

/** 本地 Evidence 的最小持久化引用。 */
public record LocalCitationPayload(
        String referenceId,
        UUID evidenceId,
        UUID revisionId,
        String documentName,
        String location
) implements CitationPayload {

    @Override
    public String kind() {
        return "local";
    }
}

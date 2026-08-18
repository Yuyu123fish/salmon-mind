package com.yuyu.salmonmind.conversation.api;

import java.util.UUID;

/** 本地 Evidence 的最小持久化引用。 */
public record LocalCitationPayload(
        String referenceId,
        UUID evidenceId,
        UUID revisionId,
        String documentName,
        String location,
        String citationNote
) implements CitationPayload {

    /** 兼容旧 JSONL 与既有调用方没有 Note 的构造。 */
    public LocalCitationPayload(
            String referenceId, UUID evidenceId, UUID revisionId, String documentName, String location
    ) {
        this(referenceId, evidenceId, revisionId, documentName, location, null);
    }

    @Override
    public String kind() {
        return "local";
    }
}

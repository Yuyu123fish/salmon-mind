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
        String sourceExcerpt
) implements AgentRetrievedSource {
}

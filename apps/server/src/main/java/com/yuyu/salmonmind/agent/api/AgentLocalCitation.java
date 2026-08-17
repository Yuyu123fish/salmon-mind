package com.yuyu.salmonmind.agent.api;

import java.util.UUID;

/** 已由本地 Evidence 身份核对的最小 Citation，不携带正文。 */
public record AgentLocalCitation(
        String referenceId,
        UUID evidenceId,
        UUID revisionId,
        String documentName,
        String location
) implements AgentCitation {
}

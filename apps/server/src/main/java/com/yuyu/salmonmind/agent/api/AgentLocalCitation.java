package com.yuyu.salmonmind.agent.api;

import java.util.UUID;

/** 已由本地 Evidence 身份核对的 Citation；Note 只来自 Agent 已有回答。 */
public record AgentLocalCitation(
        String referenceId,
        UUID evidenceId,
        UUID revisionId,
        String documentName,
        String location,
        String citationNote
) implements AgentCitation {

    /** 兼容没有 Citation Note 的既有调用方与历史测试替身。 */
    public AgentLocalCitation(
            String referenceId, UUID evidenceId, UUID revisionId, String documentName, String location
    ) {
        this(referenceId, evidenceId, revisionId, documentName, location, null);
    }
}

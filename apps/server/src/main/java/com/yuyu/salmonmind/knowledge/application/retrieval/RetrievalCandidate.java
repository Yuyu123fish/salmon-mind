package com.yuyu.salmonmind.knowledge.application.retrieval;

import com.yuyu.salmonmind.knowledge.application.port.EvidenceIndexPort;
import com.yuyu.salmonmind.knowledge.application.port.KnowledgeMetadataPort;

import java.util.UUID;

/** Pipeline 内部候选；携带两路原始 rank/score 和后续确定性排序字段。 */
public final class RetrievalCandidate {

    public final EvidenceIndexPort.RankedEvidence evidence;
    public final KnowledgeMetadataPort.ReadyEvidence metadata;
    public Integer bm25Rank;
    public Double bm25Score;
    public Integer vectorRank;
    public Double vectorScore;
    public double rrfScore;
    public double rerankScore;

    public RetrievalCandidate(
            EvidenceIndexPort.RankedEvidence evidence,
            KnowledgeMetadataPort.ReadyEvidence metadata
    ) {
        this.evidence = evidence;
        this.metadata = metadata;
    }

    public UUID id() {
        return evidence.id();
    }

    int bestLaneRank() {
        int best = Integer.MAX_VALUE;
        if (bm25Rank != null) best = Math.min(best, bm25Rank);
        if (vectorRank != null) best = Math.min(best, vectorRank);
        return best;
    }
}

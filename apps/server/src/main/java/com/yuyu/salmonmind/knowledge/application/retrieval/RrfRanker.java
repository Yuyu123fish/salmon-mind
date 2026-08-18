package com.yuyu.salmonmind.knowledge.application.retrieval;

import com.yuyu.salmonmind.knowledge.application.port.EvidenceIndexPort;
import com.yuyu.salmonmind.knowledge.application.port.KnowledgeMetadataPort;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 应用层 Reciprocal Rank Fusion 规则。两路技术分数不直接比较或相加，
 * 只使用从 1 开始的原始 rank 和固定 k=60；结果 tie-break 完全确定。
 */
public final class RrfRanker {

    static final int K = 60;
    static final int TOP_K = 20;

    public Merge merge(
            List<EvidenceIndexPort.RankedEvidence> bm25,
            List<EvidenceIndexPort.RankedEvidence> vector,
            Map<java.util.UUID, KnowledgeMetadataPort.ReadyEvidence> metadata
    ) {
        List<RetrievalCandidate> bm25Candidates = new ArrayList<>();
        List<RetrievalCandidate> vectorCandidates = new ArrayList<>();
        Map<java.util.UUID, RetrievalCandidate> merged = new HashMap<>();
        addLane(bm25, metadata, true, bm25Candidates, merged);
        addLane(vector, metadata, false, vectorCandidates, merged);
        List<RetrievalCandidate> rrf = merged.values().stream()
                .sorted(Comparator.comparingDouble((RetrievalCandidate candidate) -> candidate.rrfScore).reversed()
                        .thenComparingInt(RetrievalCandidate::bestLaneRank)
                        .thenComparing(candidate -> candidate.id().toString()))
                .limit(TOP_K)
                .toList();
        return new Merge(List.copyOf(bm25Candidates), List.copyOf(vectorCandidates), rrf);
    }

    private static void addLane(
            List<EvidenceIndexPort.RankedEvidence> hits,
            Map<java.util.UUID, KnowledgeMetadataPort.ReadyEvidence> metadata,
            boolean bm25,
            List<RetrievalCandidate> lane,
            Map<java.util.UUID, RetrievalCandidate> merged
    ) {
        for (EvidenceIndexPort.RankedEvidence hit : hits) {
            KnowledgeMetadataPort.ReadyEvidence ready = metadata.get(hit.id());
            // PostgreSQL 是 READY/身份权威；ES 中残留或正文身份不一致的文档不能进入任何阶段。
            if (ready == null || !ready.revisionId().equals(hit.revisionId())
                    || !ready.sourceId().equals(hit.sourceId())
                    || !Objects.equals(ready.contentSha256(), hit.contentSha256())) {
                continue;
            }
            RetrievalCandidate candidate = merged.computeIfAbsent(
                    hit.id(), ignored -> new RetrievalCandidate(hit, ready));
            if (bm25) {
                candidate.bm25Rank = hit.rank();
                candidate.bm25Score = hit.score();
            } else {
                candidate.vectorRank = hit.rank();
                candidate.vectorScore = hit.score();
            }
            candidate.rrfScore += 1.0d / (K + hit.rank());
            lane.add(candidate);
        }
    }

    public record Merge(
            List<RetrievalCandidate> bm25,
            List<RetrievalCandidate> vector,
            List<RetrievalCandidate> rrf
    ) {
    }
}

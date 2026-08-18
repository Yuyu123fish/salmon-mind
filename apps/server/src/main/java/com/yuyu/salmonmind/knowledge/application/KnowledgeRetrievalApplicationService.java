package com.yuyu.salmonmind.knowledge.application;

import com.yuyu.salmonmind.knowledge.api.KnowledgeSearchResult;
import com.yuyu.salmonmind.knowledge.api.KnowledgeSearchResult.SearchHit;
import com.yuyu.salmonmind.knowledge.api.KnowledgeSearchResult.SearchReason;
import com.yuyu.salmonmind.knowledge.api.KnowledgeSearchResult.SearchStage;
import com.yuyu.salmonmind.knowledge.api.KnowledgeSearchResult.SearchStatus;
import com.yuyu.salmonmind.knowledge.api.KnowledgeSearchService;
import com.yuyu.salmonmind.knowledge.api.KnowledgeException;
import com.yuyu.salmonmind.knowledge.application.port.EvidenceIndexPort;
import com.yuyu.salmonmind.knowledge.application.port.KnowledgeMetadataPort;
import com.yuyu.salmonmind.knowledge.application.retrieval.RetrievalCandidate;
import com.yuyu.salmonmind.knowledge.application.retrieval.RrfRanker;
import com.yuyu.salmonmind.knowledge.retrieval.LocalKnowledgeRetriever.LocalEvidence;
import com.yuyu.salmonmind.knowledge.retrieval.LocalKnowledgeRetriever.LocalKnowledgeReason;
import com.yuyu.salmonmind.knowledge.retrieval.LocalKnowledgeRetriever.LocalKnowledgeResult;
import com.yuyu.salmonmind.knowledge.retrieval.LocalKnowledgeRetriever.LocalKnowledgeStatus;
import com.yuyu.salmonmind.knowledge.retrieval.LocalKnowledgeRetriever;
import com.yuyu.salmonmind.model.embedding.EmbeddingException;
import com.yuyu.salmonmind.model.embedding.EmbeddingResult;
import com.yuyu.salmonmind.model.embedding.EmbeddingService;
import com.yuyu.salmonmind.model.rerank.RerankException;
import com.yuyu.salmonmind.model.rerank.RerankResult;
import com.yuyu.salmonmind.model.rerank.RerankService;
import com.yuyu.salmonmind.workspace.api.WorkspaceRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Knowledge 唯一检索编排器。Web 诊断和 Agent Tool 都复用同一条 Pipeline；
 * PostgreSQL 先确定可检索范围，ES 只负责两路候选，RRF/Rerank 和所有降级都在这里完成。
 */
@Service
class KnowledgeRetrievalApplicationService implements KnowledgeSearchService, LocalKnowledgeRetriever {

    static final String POLICY_VERSION = "local-hybrid-v1";
    private static final int BM25_TOP_K = 40;
    private static final int VECTOR_TOP_K = 40;
    private static final int RRF_TOP_K = 20;
    private static final int FINAL_TOP_K = 5;
    private static final int QUERY_MAX_CHARS = 2000;
    private static final int DEFAULT_NUM_CANDIDATES = 200;
    private static final int TEXT_PREVIEW_CHARS = 600;

    private final WorkspaceRegistry workspaceRegistry;
    private final KnowledgeMetadataPort metadata;
    private final EvidenceIndexPort index;
    private final EmbeddingService embedding;
    private final RerankService rerank;
    private final int maxRevisionFilter;
    private final int vectorNumCandidates;

    KnowledgeRetrievalApplicationService(
            WorkspaceRegistry workspaceRegistry,
            KnowledgeMetadataPort metadata,
            EvidenceIndexPort index,
            EmbeddingService embedding,
            RerankService rerank,
            @Value("${salmon.knowledge.search.max-revision-filter:512}") int maxRevisionFilter,
            @Value("${salmon.knowledge.search.vector-num-candidates:" + DEFAULT_NUM_CANDIDATES + "}") int vectorNumCandidates
    ) {
        this.workspaceRegistry = workspaceRegistry;
        this.metadata = metadata;
        this.index = index;
        this.embedding = embedding;
        this.rerank = rerank;
        this.maxRevisionFilter = Math.max(1, maxRevisionFilter);
        this.vectorNumCandidates = Math.max(VECTOR_TOP_K, vectorNumCandidates);
    }

    @Override
    public KnowledgeSearchResult search(String query) {
        return execute(normalizeQuery(query)).diagnostics();
    }

    @Override
    public LocalKnowledgeResult retrieve(String query) {
        SearchExecution execution = execute(normalizeQuery(query));
        return new LocalKnowledgeResult(
                toLocalStatus(execution.status),
                toLocalReason(execution.reason),
                execution.finalCandidates.stream()
                        .limit(FINAL_TOP_K)
                        .map(candidate -> new LocalEvidence(
                                candidate.id(), candidate.metadata.documentName(), candidate.metadata.revisionId(),
                                candidate.metadata.location(), candidate.evidence.text()))
                        .toList());
    }

    static String normalizeQuery(String query) {
        if (query == null) {
            throw new KnowledgeException(KnowledgeException.Code.INVALID_SEARCH_QUERY, "检索查询不能为空");
        }
        String normalized = query.replaceAll("\\p{Cc}", " ").replaceAll("\\s+", " ").trim();
        if (normalized.isBlank()) {
            throw new KnowledgeException(KnowledgeException.Code.INVALID_SEARCH_QUERY, "检索查询不能为空");
        }
        if (normalized.length() > QUERY_MAX_CHARS) {
            throw new KnowledgeException(KnowledgeException.Code.INVALID_SEARCH_QUERY, "检索查询不能超过 2000 个字符");
        }
        return normalized;
    }

    private SearchExecution execute(String query) {
        KnowledgeMetadataPort.RetrievalScope scope;
        try {
            scope = metadata.currentRetrievalScope(workspaceRegistry.current().id(), maxRevisionFilter);
        } catch (RuntimeException ex) {
            return SearchExecution.unavailable(SearchReason.INDEX_UNAVAILABLE,
                    List.of(), List.of("READY_SCOPE", "BM25", "VECTOR", "RRF", "RERANK"));
        }
        if (scope == null || !scope.hasReadyRevision()) {
            return SearchExecution.empty(SearchReason.NO_READY_DOCUMENTS,
                    List.of(), List.of("READY_SCOPE", "BM25", "VECTOR", "RRF", "RERANK"));
        }
        if (!scope.revisionFilterBounded()) {
            return SearchExecution.unavailable(SearchReason.READY_SCOPE_TOO_LARGE,
                    List.of("READY_SCOPE"), List.of("BM25", "VECTOR", "RRF", "RERANK"));
        }

        List<EvidenceIndexPort.RankedEvidence> bm25;
        try {
            bm25 = index.searchText(scope.physicalIndex(), query, scope.readyRevisionIds(), BM25_TOP_K);
        } catch (RuntimeException ex) {
            return SearchExecution.unavailable(SearchReason.INDEX_UNAVAILABLE,
                    List.of("READY_SCOPE"), List.of("VECTOR", "RRF", "RERANK"));
        }

        List<Float> queryVector;
        try {
            EmbeddingResult result = embedding.embed(List.of(query));
            if (result.vectors().size() != 1 || result.vectors().get(0).size() != EmbeddingService.DIMENSIONS) {
                throw new EmbeddingException(EmbeddingException.Code.INVALID_RESPONSE, "查询 Embedding 维数无效");
            }
            queryVector = result.vectors().get(0);
        } catch (RuntimeException ex) {
            return degradedVector(scope, bm25);
        }

        List<EvidenceIndexPort.RankedEvidence> vector;
        try {
            vector = index.searchVector(scope.physicalIndex(), queryVector, scope.readyRevisionIds(),
                    VECTOR_TOP_K, vectorNumCandidates);
        } catch (RuntimeException ex) {
            return degradedVector(scope, bm25);
        }

        RrfRanker.Merge merged;
        try {
            Map<UUID, KnowledgeMetadataPort.ReadyEvidence> ready = metadata.findReadyEvidence(
                    scope, unionIds(bm25, vector));
            merged = new RrfRanker().merge(bm25, vector, ready);
        } catch (RuntimeException ex) {
            return SearchExecution.unavailable(SearchReason.INDEX_UNAVAILABLE,
                    List.of("READY_SCOPE", "BM25", "VECTOR"), List.of("RRF", "RERANK"));
        }
        if (merged.rrf().isEmpty()) {
            return SearchExecution.empty(SearchReason.NO_MATCH,
                    List.of("READY_SCOPE", "BM25", "VECTOR", "RRF"), List.of("RERANK"),
                    merged.bm25(), merged.vector(), List.of(), List.of());
        }

        List<RetrievalCandidate> finalCandidates;
        try {
            List<String> documents = merged.rrf().stream().limit(RRF_TOP_K)
                    .map(candidate -> candidate.evidence.text()).toList();
            RerankResult result = rerank.rerank(query, documents, FINAL_TOP_K);
            finalCandidates = mapRerank(merged.rrf(), result);
            if (finalCandidates.isEmpty()) {
                throw new RerankException(RerankException.Code.INVALID_RESPONSE, "Rerank 未返回候选");
            }
        } catch (RuntimeException ex) {
            List<RetrievalCandidate> fallback = top(merged.rrf(), FINAL_TOP_K);
            fallback.forEach(candidate -> candidate.rerankScore = candidate.rrfScore);
            return SearchExecution.degraded(SearchReason.RERANK_UNAVAILABLE,
                    merged.bm25(), merged.vector(), merged.rrf(), fallback,
                    List.of("READY_SCOPE", "BM25", "VECTOR", "RRF", "RERANK"), List.of());
        }
        return SearchExecution.success(merged.bm25(), merged.vector(), merged.rrf(), finalCandidates,
                List.of("READY_SCOPE", "BM25", "VECTOR", "RRF", "RERANK"), List.of());
    }

    private SearchExecution degradedVector(
            KnowledgeMetadataPort.RetrievalScope scope,
            List<EvidenceIndexPort.RankedEvidence> bm25
    ) {
        try {
            Map<UUID, KnowledgeMetadataPort.ReadyEvidence> ready = metadata.findReadyEvidence(
                    scope, unionIds(bm25, List.of()));
            RrfRanker.Merge merged = new RrfRanker().merge(bm25, List.of(), ready);
            merged.bm25().forEach(candidate ->
                    candidate.rerankScore = candidate.bm25Score == null ? 0.0d : candidate.bm25Score);
            List<RetrievalCandidate> finalCandidates = top(merged.bm25(), FINAL_TOP_K);
            return SearchExecution.degraded(SearchReason.VECTOR_UNAVAILABLE,
                    merged.bm25(), List.of(), List.of(), finalCandidates,
                    List.of("READY_SCOPE", "BM25"), List.of("VECTOR", "RRF", "RERANK"));
        } catch (RuntimeException ex) {
            return SearchExecution.unavailable(SearchReason.INDEX_UNAVAILABLE,
                    List.of("READY_SCOPE"), List.of("BM25", "VECTOR", "RRF", "RERANK"));
        }
    }

    private static List<RetrievalCandidate> mapRerank(
            List<RetrievalCandidate> candidates, RerankResult result
    ) {
        Set<Integer> indexes = new HashSet<>();
        List<RetrievalCandidate> mapped = new ArrayList<>();
        for (RerankResult.ScoredDocument item : result.results()) {
            if (item.index() < 0 || item.index() >= candidates.size() || !indexes.add(item.index())) {
                throw new RerankException(RerankException.Code.INVALID_RESPONSE, "Rerank index 映射无效");
            }
            RetrievalCandidate candidate = candidates.get(item.index());
            candidate.rerankScore = item.score();
            mapped.add(candidate);
        }
        return mapped.stream().limit(FINAL_TOP_K).toList();
    }

    private static List<UUID> unionIds(
            Collection<EvidenceIndexPort.RankedEvidence> first,
            Collection<EvidenceIndexPort.RankedEvidence> second
    ) {
        return java.util.stream.Stream.concat(first.stream(), second.stream())
                .map(EvidenceIndexPort.RankedEvidence::id).distinct().toList();
    }

    private static List<RetrievalCandidate> top(List<RetrievalCandidate> candidates, int limit) {
        return candidates.stream().limit(limit).toList();
    }

    private static SearchStage stage(Collection<RetrievalCandidate> candidates, StageField field, int limit) {
        List<SearchHit> hits = new ArrayList<>();
        int position = 0;
        for (RetrievalCandidate candidate : candidates) {
            if (position++ >= limit) break;
            Integer rank = null;
            Double score = null;
            switch (field) {
                case BM25 -> { rank = candidate.bm25Rank; score = candidate.bm25Score; }
                case VECTOR -> { rank = candidate.vectorRank; score = candidate.vectorScore; }
                case RRF -> { rank = position; score = candidate.rrfScore; }
                case FINAL -> { rank = position; score = candidate.rerankScore; }
            }
            hits.add(toHit(candidate, rank, score));
        }
        return new SearchStage(hits);
    }

    private static SearchHit toHit(RetrievalCandidate candidate, Integer rank, Double score) {
        String text = candidate.evidence.text();
        String preview = text.length() <= TEXT_PREVIEW_CHARS ? text : text.substring(0, TEXT_PREVIEW_CHARS) + "…";
        return new SearchHit(candidate.id(), candidate.metadata.sourceId(), candidate.metadata.revisionId(),
                candidate.metadata.documentName(), candidate.metadata.location(), preview, rank, score);
    }

    private static LocalKnowledgeStatus toLocalStatus(SearchStatus status) {
        return LocalKnowledgeStatus.valueOf(status.name());
    }

    private static LocalKnowledgeReason toLocalReason(SearchReason reason) {
        return LocalKnowledgeReason.valueOf(reason.name());
    }

    private enum StageField { BM25, VECTOR, RRF, FINAL }

    private record SearchExecution(
            SearchStatus status,
            SearchReason reason,
            List<RetrievalCandidate> bm25Candidates,
            List<RetrievalCandidate> vectorCandidates,
            List<RetrievalCandidate> rrfCandidates,
            List<RetrievalCandidate> finalCandidates,
            List<String> executedStages,
            List<String> skippedStages
    ) {
        static SearchExecution success(List<RetrievalCandidate> bm25, List<RetrievalCandidate> vector,
                                       List<RetrievalCandidate> rrf, List<RetrievalCandidate> finalResults,
                                       List<String> executed, List<String> skipped) {
            return new SearchExecution(SearchStatus.SUCCESS, SearchReason.COMPLETE,
                    bm25, vector, rrf, finalResults, executed, skipped);
        }

        static SearchExecution degraded(SearchReason reason, List<RetrievalCandidate> bm25,
                                        List<RetrievalCandidate> vector, List<RetrievalCandidate> rrf,
                                        List<RetrievalCandidate> finalResults, List<String> executed,
                                        List<String> skipped) {
            return new SearchExecution(SearchStatus.DEGRADED, reason, bm25, vector, rrf, finalResults,
                    executed, skipped);
        }

        static SearchExecution empty(SearchReason reason, List<String> executed, List<String> skipped) {
            return empty(reason, executed, skipped, List.of(), List.of(), List.of(), List.of());
        }

        static SearchExecution empty(SearchReason reason, List<String> executed, List<String> skipped,
                                     List<RetrievalCandidate> bm25, List<RetrievalCandidate> vector,
                                     List<RetrievalCandidate> rrf, List<RetrievalCandidate> finalResults) {
            return new SearchExecution(SearchStatus.EMPTY, reason, bm25, vector, rrf, finalResults,
                    executed, skipped);
        }

        static SearchExecution unavailable(SearchReason reason, List<String> executed, List<String> skipped) {
            return new SearchExecution(SearchStatus.UNAVAILABLE, reason,
                    List.of(), List.of(), List.of(), List.of(), executed, skipped);
        }

        KnowledgeSearchResult diagnostics() {
            return new KnowledgeSearchResult(
                    POLICY_VERSION, status, reason,
                    stage(bm25Candidates, StageField.BM25, BM25_TOP_K),
                    stage(vectorCandidates, StageField.VECTOR, VECTOR_TOP_K),
                    stage(rrfCandidates, StageField.RRF, RRF_TOP_K),
                    stage(finalCandidates, StageField.FINAL, FINAL_TOP_K),
                    executedStages, skippedStages);
        }
    }
}

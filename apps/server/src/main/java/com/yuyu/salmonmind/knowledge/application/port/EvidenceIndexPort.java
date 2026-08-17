package com.yuyu.salmonmind.knowledge.application.port;

import java.util.List;
import java.util.Collection;
import java.util.UUID;

/** Elasticsearch Evidence Adapter；隐藏 mapping、BM25 与 kNN 查询细节。 */
public interface EvidenceIndexPort {

    /** 确保当前 Generation 的物理索引存在，并返回内部索引名。 */
    String ensureIndex();

    /** 以稳定 Evidence ID 幂等写入一条正文、位置和固定维数向量。 */
    void upsert(String indexName, IndexedEvidence evidence);

    /** 统计某个 Revision 的可见派生 Evidence；仅供 READY 发布前校验和详情展示。 */
    long countForRevision(String indexName, UUID revisionId);

    /** 分页读取某个 Revision 的 Evidence；调用方保证文档已 READY。 */
    List<IndexedEvidence> pageForRevision(String indexName, UUID revisionId, int offset, int size);

    /** 在 READY Revision terms pre-filter 内执行 mapping-v1 的 BM25 文本召回。 */
    List<RankedEvidence> searchText(String indexName, String query, Collection<UUID> revisionIds, int limit);

    /** 在同一 READY Revision pre-filter 内执行固定 2560 维 cosine kNN 召回。 */
    List<RankedEvidence> searchVector(
            String indexName,
            List<Float> queryVector,
            Collection<UUID> revisionIds,
            int limit,
            int numCandidates
    );

    /** Elasticsearch 中的派生证据；物理索引名和向量不会越过 Knowledge API。 */
    record IndexedEvidence(
            UUID id,
            UUID revisionId,
            UUID sourceId,
            int ordinal,
            String location,
            String text,
            List<Float> vector,
            String contentSha256
    ) {
        public IndexedEvidence {
            vector = List.copyOf(vector);
        }

        public int charCount() {
            return text.length();
        }
    }

    /** 一路 Elasticsearch 召回的原始 rank/score；score 不跨路直接相加。 */
    record RankedEvidence(
            UUID id,
            UUID revisionId,
            UUID sourceId,
            int ordinal,
            String location,
            String text,
            String contentSha256,
            int rank,
            double score
    ) {
    }
}

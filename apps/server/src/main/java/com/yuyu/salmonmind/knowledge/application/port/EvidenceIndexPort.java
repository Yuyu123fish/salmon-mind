package com.yuyu.salmonmind.knowledge.application.port;

import java.util.List;
import java.util.UUID;

/** Elasticsearch Evidence Adapter；Stage 02 只负责写入、验证和预览，不提供召回。 */
public interface EvidenceIndexPort {

    /** 确保当前 Generation 的物理索引存在，并返回内部索引名。 */
    String ensureIndex();

    /** 以稳定 Evidence ID 幂等写入一条正文、位置和固定维数向量。 */
    void upsert(String indexName, IndexedEvidence evidence);

    /** 统计某个 Revision 的可见派生 Evidence；仅供 READY 发布前校验和详情展示。 */
    long countForRevision(String indexName, UUID revisionId);

    /** 分页读取某个 Revision 的 Evidence；调用方保证文档已 READY。 */
    List<IndexedEvidence> pageForRevision(String indexName, UUID revisionId, int offset, int size);

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
}

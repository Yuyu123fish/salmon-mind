package com.yuyu.salmonmind.knowledge.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.KnnQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.indices.IndexSettings;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.salmonmind.knowledge.api.EvidencePreview;
import com.yuyu.salmonmind.knowledge.api.KnowledgeException;
import com.yuyu.salmonmind.knowledge.application.port.EvidenceIndexPort;
import com.yuyu.salmonmind.model.embedding.EmbeddingService;
import jakarta.annotation.PreDestroy;
import org.apache.http.HttpHost;
import org.apache.http.message.BasicHeader;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Elasticsearch 8.13 Java API Client Adapter。首次使用时建立 mapping-v1，
 * 其中 vector 固定 2560 维 cosine；Stage 02 只写入和读取自己的 Evidence 预览。
 */
@Component
class ElasticsearchEvidenceStore implements EvidenceIndexPort {

    private final String baseUrl;
    private final String username;
    private final String password;
    private final String indexPrefix;
    private final int shards;
    private final int replicas;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final ObjectMapper objectMapper;

    private volatile RestClient restClient;
    private volatile RestClientTransport transport;
    private volatile ElasticsearchClient client;

    ElasticsearchEvidenceStore(
            @Value("${salmon.knowledge.search.base-url:}") String baseUrl,
            @Value("${salmon.knowledge.search.username:}") String username,
            @Value("${salmon.knowledge.search.password:}") String password,
            @Value("${salmon.knowledge.search.index-prefix:salmon-evidence}") String indexPrefix,
            @Value("${salmon.knowledge.search.shards:1}") int shards,
            @Value("${salmon.knowledge.search.replicas:0}") int replicas,
            @Value("${salmon.knowledge.search.connect-timeout:5s}") Duration connectTimeout,
            @Value("${salmon.knowledge.search.read-timeout:30s}") Duration readTimeout,
            ObjectMapper objectMapper
    ) {
        this.baseUrl = baseUrl;
        this.username = username;
        this.password = password;
        this.indexPrefix = indexPrefix;
        this.shards = shards;
        this.replicas = replicas;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        this.objectMapper = objectMapper;
    }

    @Override
    public String ensureIndex() {
        String index = indexPrefix + "-v1";
        try {
            ElasticsearchClient current = client();
            if (!current.indices().exists(request -> request.index(index)).value()) {
                current.indices().create(request -> request
                        .index(index)
                        .settings(IndexSettings.of(settings -> settings
                                .numberOfShards(Integer.toString(shards))
                                .numberOfReplicas(Integer.toString(replicas))))
                        .mappings(mapping -> mapping
                                .properties("sourceId", property -> property.keyword(keyword -> keyword))
                                .properties("revisionId", property -> property.keyword(keyword -> keyword))
                                .properties("contentSha256", property -> property.keyword(keyword -> keyword))
                                .properties("location", property -> property.keyword(keyword -> keyword))
                                .properties("ordinal", property -> property.integer(integer -> integer))
                                .properties("text", property -> property.text(text -> text.analyzer("standard")))
                                .properties("vector", property -> property.denseVector(vector -> vector
                                        .dims(EmbeddingService.DIMENSIONS)
                                        // Elasticsearch 8.13 的 Java Client 未暴露 similarity builder；
                                        // dense_vector 开启索引时使用服务端默认 cosine 相似度。
                                        .index(true)))));
            }
            return index;
        } catch (Exception ex) {
            throw new KnowledgeException(KnowledgeException.Code.KNOWLEDGE_INDEX_UNAVAILABLE,
                    "Elasticsearch 索引不可用", ex);
        }
    }

    @Override
    public void upsert(String indexName, IndexedEvidence evidence) {
        if (evidence.vector().size() != EmbeddingService.DIMENSIONS) {
            throw new KnowledgeException(KnowledgeException.Code.KNOWLEDGE_INDEX_UNAVAILABLE, "Embedding 维数与索引不一致");
        }
        Map<String, Object> document = new HashMap<>();
        document.put("sourceId", evidence.sourceId().toString());
        document.put("revisionId", evidence.revisionId().toString());
        document.put("contentSha256", evidence.contentSha256());
        document.put("ordinal", evidence.ordinal());
        document.put("location", evidence.location());
        document.put("text", evidence.text());
        document.put("vector", evidence.vector());
        try {
            client().index(request -> request.index(indexName).id(evidence.id().toString())
                    .document(document).refresh(Refresh.WaitFor));
        } catch (Exception ex) {
            throw new KnowledgeException(KnowledgeException.Code.KNOWLEDGE_INDEX_UNAVAILABLE,
                    "Evidence 写入 Elasticsearch 失败", ex);
        }
    }

    @Override
    public long countForRevision(String indexName, UUID revisionId) {
        try {
            return client().count(request -> request.index(indexName)
                    .query(query -> query.term(term -> term.field("revisionId").value(revisionId.toString()))))
                    .count();
        } catch (Exception ex) {
            throw new KnowledgeException(KnowledgeException.Code.KNOWLEDGE_INDEX_UNAVAILABLE,
                    "Evidence 数量校验失败", ex);
        }
    }

    @Override
    public List<IndexedEvidence> pageForRevision(String indexName, UUID revisionId, int offset, int size) {
        try {
            SearchResponse<Map> response = client().search(request -> request
                            .index(indexName)
                            .from(Math.max(0, offset))
                            .size(Math.max(1, Math.min(size, 100)))
                            .query(query -> query.term(term -> term.field("revisionId").value(revisionId.toString())))
                            .sort(sort -> sort.field(field -> field.field("ordinal").order(SortOrder.Asc))),
                    Map.class);
            return response.hits().hits().stream().map(ElasticsearchEvidenceStore::toIndexedEvidence).toList();
        } catch (Exception ex) {
            throw new KnowledgeException(KnowledgeException.Code.KNOWLEDGE_INDEX_UNAVAILABLE,
                    "Evidence 读取失败", ex);
        }
    }

    @Override
    public void deleteForRevisions(String indexName, Collection<UUID> revisionIds) {
        if (revisionIds == null || revisionIds.isEmpty()) {
            return;
        }
        try {
            if (!client().indices().exists(request -> request.index(indexName)).value()) {
                return;
            }
            DeleteByQueryResponse response = client().deleteByQuery(request -> request
                    .index(indexName)
                    .query(revisionFilter(revisionIds))
                    // 让下一次 count 与后续检索都看到本次删除；默认 version conflict 不继续吞错。
                    .refresh(true));
            if (Boolean.TRUE.equals(response.timedOut())
                    || (response.versionConflicts() != null && response.versionConflicts() > 0)
                    || (response.failures() != null && !response.failures().isEmpty())) {
                throw new KnowledgeException(KnowledgeException.Code.KNOWLEDGE_INDEX_UNAVAILABLE,
                        "Evidence 删除未完成");
            }
            client().indices().refresh(request -> request.index(indexName));
            long remaining = client().count(request -> request
                    .index(indexName)
                    .query(revisionFilter(revisionIds))).count();
            if (remaining != 0) {
                throw new KnowledgeException(KnowledgeException.Code.KNOWLEDGE_INDEX_UNAVAILABLE,
                        "Evidence 删除后仍有残留");
            }
        } catch (KnowledgeException ex) {
            throw ex;
        } catch (ElasticsearchException ex) {
            if (ex.status() == 404) {
                // 物理索引在两次重试之间被移除，目标已经不可见，按幂等删除收束。
                return;
            }
            throw new KnowledgeException(KnowledgeException.Code.KNOWLEDGE_INDEX_UNAVAILABLE,
                    "Evidence 删除失败", ex);
        } catch (Exception ex) {
            throw new KnowledgeException(KnowledgeException.Code.KNOWLEDGE_INDEX_UNAVAILABLE,
                    "Evidence 删除失败", ex);
        }
    }

    @Override
    public List<RankedEvidence> searchText(
            String indexName, String queryText, Collection<UUID> revisionIds, int limit
    ) {
        try {
            SearchResponse<Map> response = client().search(request -> request
                            .index(indexName)
                            .size(Math.max(1, Math.min(limit, 40)))
                            .source(source -> source.filter(filter -> filter.includes(
                                    "sourceId", "revisionId", "ordinal", "location", "text", "contentSha256")))
                            .query(query -> query.bool(bool -> bool
                                    .must(must -> must.match(match -> match.field("text").query(queryText)))
                                    .filter(revisionFilter(revisionIds))))
                            .sort(sort -> sort.field(field -> field.field("_score").order(SortOrder.Desc))),
                    Map.class);
            return rankedHits(response);
        } catch (Exception ex) {
            throw new KnowledgeException(KnowledgeException.Code.KNOWLEDGE_INDEX_UNAVAILABLE,
                    "BM25 检索失败", ex);
        }
    }

    @Override
    public List<RankedEvidence> searchVector(
            String indexName,
            List<Float> queryVector,
            Collection<UUID> revisionIds,
            int limit,
            int numCandidates
    ) {
        if (queryVector == null || queryVector.size() != EmbeddingService.DIMENSIONS) {
            throw new KnowledgeException(KnowledgeException.Code.KNOWLEDGE_INDEX_UNAVAILABLE,
                    "查询 Embedding 维数与索引不一致");
        }
        try {
            int boundedLimit = Math.max(1, Math.min(limit, 40));
            SearchResponse<Map> response = client().search(request -> request
                            .index(indexName)
                            .size(boundedLimit)
                            .source(source -> source.filter(filter -> filter.includes(
                                    "sourceId", "revisionId", "ordinal", "location", "text", "contentSha256")))
                            // Elasticsearch 8.13 的 knn filter 位于 approximate kNN 内部，
                            // 不能退化为查询后过滤，否则残留 Evidence 会挤占候选名额。
                            .knn(knn -> knn.field("vector")
                                    .queryVector(queryVector)
                                    .k(boundedLimit)
                                    .numCandidates(Math.max(boundedLimit, Math.min(numCandidates, 10_000)))
                                    .filter(revisionFilter(revisionIds))),
                    Map.class);
            return rankedHits(response);
        } catch (Exception ex) {
            throw new KnowledgeException(KnowledgeException.Code.KNOWLEDGE_INDEX_UNAVAILABLE,
                    "向量检索失败", ex);
        }
    }

    @PreDestroy
    void close() {
        try {
            if (transport != null) transport.close();
            if (restClient != null) restClient.close();
        } catch (IOException ignored) {
            // 应用关闭阶段不覆盖业务结果。
        }
    }

    private synchronized ElasticsearchClient client() {
        if (client != null) {
            return client;
        }
        if (!StringUtils.hasText(baseUrl)) {
            throw new KnowledgeException(KnowledgeException.Code.KNOWLEDGE_INDEX_UNAVAILABLE, "Elasticsearch 未配置");
        }
        HttpHost host = HttpHost.create(baseUrl);
        if (StringUtils.hasText(username)) {
            String token = Base64.getEncoder().encodeToString(
                    (username + ":" + password).getBytes(StandardCharsets.UTF_8));
            restClient = RestClient.builder(host)
                    .setDefaultHeaders(new BasicHeader[]{new BasicHeader("Authorization", "Basic " + token)})
                    .setRequestConfigCallback(this::requestConfig)
                    .build();
        } else {
            restClient = RestClient.builder(host)
                    .setRequestConfigCallback(this::requestConfig)
                    .build();
        }
        transport = new RestClientTransport(restClient, new JacksonJsonpMapper(objectMapper));
        client = new ElasticsearchClient(transport);
        return client;
    }

    /** 为检索与索引写入设置有限的连接、连接池等待和读取超时。 */
    private org.apache.http.client.config.RequestConfig.Builder requestConfig(
            org.apache.http.client.config.RequestConfig.Builder builder
    ) {
        int connectMillis = timeoutMillis(connectTimeout);
        return builder
                .setConnectTimeout(connectMillis)
                .setConnectionRequestTimeout(connectMillis)
                .setSocketTimeout(timeoutMillis(readTimeout));
    }

    private static int timeoutMillis(Duration timeout) {
        return Math.toIntExact(timeout.toMillis());
    }

    @SuppressWarnings("unchecked")
    private static IndexedEvidence toIndexedEvidence(Hit<Map> hit) {
        Map<String, Object> source = hit.source() == null ? Map.of() : hit.source();
        UUID id = UUID.fromString(hit.id());
        UUID revisionId = UUID.fromString((String) source.get("revisionId"));
        UUID sourceId = UUID.fromString((String) source.get("sourceId"));
        int ordinal = ((Number) source.get("ordinal")).intValue();
        String location = String.valueOf(source.get("location"));
        String text = String.valueOf(source.get("text"));
        String sha = String.valueOf(source.get("contentSha256"));
        List<Float> vector = ((List<?>) source.getOrDefault("vector", List.of())).stream()
                .map(value -> ((Number) value).floatValue()).toList();
        return new IndexedEvidence(id, revisionId, sourceId, ordinal, location, text, vector, sha);
    }

    private static Query revisionFilter(Collection<UUID> revisionIds) {
        if (revisionIds == null || revisionIds.isEmpty()) {
            throw new IllegalArgumentException("READY Revision 过滤范围不能为空");
        }
        List<FieldValue> values = revisionIds.stream()
                .map(revisionId -> FieldValue.of(revisionId.toString()))
                .toList();
        return Query.of(query -> query.terms(terms -> terms.field("revisionId")
                .terms(value -> value.value(values))));
    }

    @SuppressWarnings("unchecked")
    private static List<RankedEvidence> rankedHits(SearchResponse<Map> response) {
        // ES 返回顺序通常已经按 _score 排列；这里再做一次稳定排序，避免同分候选
        // 因分片返回顺序变化而让诊断结果漂移。_id 不写入 mapping，故在应用层 tie-break。
        List<Hit<Map>> hits = response.hits().hits().stream()
                .sorted(Comparator.comparing(Hit<Map>::score, Comparator.nullsFirst(Comparator.reverseOrder()))
                        .thenComparing(Hit::id))
                .toList();
        List<RankedEvidence> result = new java.util.ArrayList<>(hits.size());
        for (int i = 0; i < hits.size(); i++) {
            Hit<Map> hit = hits.get(i);
            Map<String, Object> source = hit.source() == null ? Map.of() : hit.source();
            result.add(new RankedEvidence(
                    UUID.fromString(hit.id()),
                    UUID.fromString(String.valueOf(source.get("revisionId"))),
                    UUID.fromString(String.valueOf(source.get("sourceId"))),
                    ((Number) source.get("ordinal")).intValue(),
                    String.valueOf(source.get("location")),
                    String.valueOf(source.get("text")),
                    String.valueOf(source.get("contentSha256")),
                    i + 1,
                    hit.score() == null ? 0.0d : hit.score()));
        }
        return result;
    }
}

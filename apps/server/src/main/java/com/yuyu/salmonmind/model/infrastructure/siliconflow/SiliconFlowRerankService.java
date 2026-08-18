package com.yuyu.salmonmind.model.infrastructure.siliconflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuyu.salmonmind.model.rerank.RerankException;
import com.yuyu.salmonmind.model.rerank.RerankResult;
import com.yuyu.salmonmind.model.rerank.RerankService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Duration;

/**
 * SiliconFlow Qwen3 Reranker Adapter。只有检索真正进入精排阶段时才发起请求；
 * 配置缺失不会阻止 Server 启动，响应的 index/数量/范围校验在此处完成。
 */
@Component
class SiliconFlowRerankService implements RerankService {

    private final String baseUrl;
    private final String apiKey;
    private final String modelName;
    private final String instruction;
    private final Duration connectTimeout;
    private final Duration readTimeout;

    private volatile RestClient client;

    SiliconFlowRerankService(
            @Value("${salmon.model.rerank.base-url:}") String baseUrl,
            @Value("${salmon.model.rerank.api-key:}") String apiKey,
            @Value("${salmon.model.rerank.model-name:" + MODEL + "}") String modelName,
            @Value("${salmon.model.rerank.instruction:" + INSTRUCTION + "}") String instruction,
            @Value("${salmon.model.connect-timeout:5s}") Duration connectTimeout,
            @Value("${salmon.model.read-timeout:60s}") Duration readTimeout
    ) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.instruction = instruction;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    @Override
    public RerankResult rerank(String query, List<String> documents, int topN) {
        if (!StringUtils.hasText(query) || documents == null || documents.isEmpty()
                || documents.stream().anyMatch(document -> !StringUtils.hasText(document))
                || topN < 1) {
            throw new RerankException(RerankException.Code.INVALID_RESPONSE, "Rerank 输入不符合合同");
        }
        if (!StringUtils.hasText(baseUrl) || !StringUtils.hasText(apiKey)
                || !StringUtils.hasText(modelName) || !StringUtils.hasText(instruction)) {
            throw new RerankException(RerankException.Code.NOT_CONFIGURED, "Rerank 模型未配置");
        }
        int requestedTopN = Math.min(topN, documents.size());
        try {
            Map<String, Object> request = new HashMap<>();
            request.put("model", modelName);
            request.put("query", query);
            request.put("documents", documents);
            request.put("top_n", requestedTopN);
            request.put("return_documents", false);
            request.put("instruction", instruction);
            JsonNode response = client().post()
                    .uri("/rerank")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .body(request)
                    .retrieve()
                    .body(JsonNode.class);
            return parse(response, documents.size(), requestedTopN);
        } catch (RestClientResponseException ex) {
            throw new RerankException(RerankException.Code.FAILED,
                    "Rerank 服务请求失败（HTTP " + ex.getStatusCode().value() + ")", ex);
        } catch (RerankException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new RerankException(RerankException.Code.FAILED, "Rerank 服务不可用", ex);
        }
    }

    private RerankResult parse(JsonNode response, int documentCount, int requestedTopN) {
        if (response == null || !response.has("results") || !response.get("results").isArray()) {
            throw new RerankException(RerankException.Code.INVALID_RESPONSE, "Rerank 响应缺少 results");
        }
        List<RerankResult.ScoredDocument> results = new ArrayList<>();
        Set<Integer> indexes = new HashSet<>();
        for (JsonNode item : response.get("results")) {
            int index = item.path("index").asInt(Integer.MIN_VALUE);
            JsonNode scoreNode = item.get("relevance_score");
            if (index < 0 || index >= documentCount || !indexes.add(index)
                    || scoreNode == null || !scoreNode.isNumber()) {
                throw new RerankException(RerankException.Code.INVALID_RESPONSE,
                        "Rerank 响应 index 或 score 无效");
            }
            results.add(new RerankResult.ScoredDocument(index, scoreNode.asDouble()));
        }
        if (results.size() > requestedTopN) {
            throw new RerankException(RerankException.Code.INVALID_RESPONSE, "Rerank 响应数量超过 top_n");
        }
        return new RerankResult("siliconflow", modelName, results);
    }

    private synchronized RestClient client() {
        if (client == null) {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(connectTimeout);
            requestFactory.setReadTimeout(readTimeout);
            client = RestClient.builder()
                    .baseUrl(baseUrl.replaceAll("/+$", ""))
                    .requestFactory(requestFactory)
                    .build();
        }
        return client;
    }
}

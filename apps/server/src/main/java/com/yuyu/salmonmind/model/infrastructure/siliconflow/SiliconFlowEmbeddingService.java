package com.yuyu.salmonmind.model.infrastructure.siliconflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuyu.salmonmind.model.embedding.EmbeddingException;
import com.yuyu.salmonmind.model.embedding.EmbeddingResult;
import com.yuyu.salmonmind.model.embedding.EmbeddingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.time.Duration;

/**
 * SiliconFlow OpenAI-compatible Embedding Adapter。
 *
 * <p>只在 Worker 真正处理文档时构造 HTTP 客户端并发起请求；配置缺失不会阻止
 * Server 启动。响应必须带齐输入顺序和 2560 维向量，否则整批失败，不发布 READY。
 */
@Component
class SiliconFlowEmbeddingService implements EmbeddingService {

    private final String baseUrl;
    private final String apiKey;
    private final String modelName;
    private final Duration connectTimeout;
    private final Duration readTimeout;

    private volatile RestClient client;

    SiliconFlowEmbeddingService(
            @Value("${salmon.model.embedding.base-url:}") String baseUrl,
            @Value("${salmon.model.embedding.api-key:}") String apiKey,
            @Value("${salmon.model.embedding.model-name:Qwen/Qwen3-Embedding-4B}") String modelName,
            @Value("${salmon.model.connect-timeout:5s}") Duration connectTimeout,
            @Value("${salmon.model.read-timeout:60s}") Duration readTimeout
    ) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    @Override
    public EmbeddingResult embed(List<String> texts) {
        if (texts == null || texts.isEmpty() || texts.stream().anyMatch(text -> !StringUtils.hasText(text))) {
            throw new EmbeddingException(EmbeddingException.Code.INVALID_RESPONSE, "Embedding 输入不能为空");
        }
        if (!StringUtils.hasText(baseUrl) || !StringUtils.hasText(apiKey) || !StringUtils.hasText(modelName)) {
            throw new EmbeddingException(EmbeddingException.Code.NOT_CONFIGURED, "Embedding 模型未配置");
        }
        try {
            Map<String, Object> request = new HashMap<>();
            request.put("model", modelName);
            request.put("input", texts);
            request.put("encoding_format", "float");
            request.put("dimensions", EmbeddingService.DIMENSIONS);
            JsonNode response = client().post()
                    .uri("/embeddings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .body(request)
                    .retrieve()
                    .body(JsonNode.class);
            return parse(response, texts.size());
        } catch (RestClientResponseException ex) {
            throw new EmbeddingException(EmbeddingException.Code.FAILED,
                    "Embedding 服务请求失败（HTTP " + ex.getStatusCode().value() + ")", ex);
        } catch (EmbeddingException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new EmbeddingException(EmbeddingException.Code.FAILED, "Embedding 服务不可用", ex);
        }
    }

    private EmbeddingResult parse(JsonNode response, int expectedSize) {
        if (response == null || !response.has("data") || !response.get("data").isArray()) {
            throw new EmbeddingException(EmbeddingException.Code.INVALID_RESPONSE, "Embedding 响应缺少 data");
        }
        List<JsonNode> data = new ArrayList<>();
        response.get("data").forEach(data::add);
        data.sort(Comparator.comparingInt(node -> node.path("index").asInt(-1)));
        if (data.size() != expectedSize) {
            throw new EmbeddingException(EmbeddingException.Code.INVALID_RESPONSE, "Embedding 响应数量不一致");
        }
        List<List<Float>> vectors = new ArrayList<>();
        for (int i = 0; i < data.size(); i++) {
            JsonNode item = data.get(i);
            JsonNode embeddingNode = item.get("embedding");
            if (item.path("index").asInt(-1) != i || embeddingNode == null || !embeddingNode.isArray()
                    || embeddingNode.size() != EmbeddingService.DIMENSIONS) {
                throw new EmbeddingException(EmbeddingException.Code.INVALID_RESPONSE, "Embedding 响应顺序或维数错误");
            }
            List<Float> vector = new ArrayList<>(EmbeddingService.DIMENSIONS);
            item.get("embedding").forEach(value -> vector.add((float) value.asDouble()));
            vectors.add(vector);
        }
        return new EmbeddingResult("siliconflow", modelName, vectors);
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

package com.yuyu.salmonmind.websearch.infrastructure.bocha;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuyu.salmonmind.websearch.api.WebSearchService.WebSearchFreshness;
import com.yuyu.salmonmind.websearch.api.WebSearchService.WebSearchProvider;
import com.yuyu.salmonmind.websearch.api.WebSearchService.WebSearchReason;
import com.yuyu.salmonmind.websearch.application.port.WebSearchProviderException;
import com.yuyu.salmonmind.websearch.application.port.WebSearchProviderPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 博查原始 Web Search Adapter；不消费 AI Search/生成式答案，也不调用其他 Provider。 */
@Component
public class BochaWebSearchAdapter implements WebSearchProviderPort {

    private final String baseUrl;
    private final String apiKey;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private volatile RestClient client;

    public BochaWebSearchAdapter(
            @Value("${salmon.websearch.bocha.base-url:https://api.bochaai.com}") String baseUrl,
            @Value("${salmon.websearch.bocha.api-key:}") String apiKey,
            @Value("${salmon.websearch.connect-timeout:5s}") Duration connectTimeout,
            @Value("${salmon.websearch.read-timeout:15s}") Duration readTimeout
    ) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    @Override
    public WebSearchProvider provider() {
        return WebSearchProvider.BOCHA;
    }

    @Override
    public RawSearchResult search(String query, WebSearchFreshness freshness, int count) {
        if (!StringUtils.hasText(baseUrl) || !StringUtils.hasText(apiKey)) {
            throw new WebSearchProviderException(WebSearchReason.NOT_CONFIGURED, "博查未配置");
        }
        Map<String, Object> body = new HashMap<>();
        body.put("query", query);
        body.put("freshness", freshnessValue(freshness));
        body.put("summary", true);
        body.put("count", count);
        try {
            JsonNode response = client().post()
                    .uri("/v1/web-search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null || !response.has("webPages")
                    || !response.get("webPages").has("value")
                    || !response.get("webPages").get("value").isArray()) {
                throw new WebSearchProviderException(WebSearchReason.INVALID_RESPONSE,
                        "博查响应缺少 webPages.value");
            }
            List<RawSearchHit> hits = new java.util.ArrayList<>();
            int rank = 1;
            for (JsonNode item : response.get("webPages").get("value")) {
                hits.add(new RawSearchHit(
                        rank++, text(item, "name"), text(item, "url"), text(item, "siteName"),
                        firstText(item, "summary", "snippet"), text(item, "datePublished")));
            }
            return new RawSearchResult(hits, firstText(response, "requestId", "traceId"));
        } catch (WebSearchProviderException ex) {
            throw ex;
        } catch (RestClientResponseException ex) {
            throw new WebSearchProviderException(reasonOf(ex.getStatusCode().value()),
                    "博查请求失败", ex);
        } catch (ResourceAccessException ex) {
            throw new WebSearchProviderException(isTimeout(ex)
                    ? WebSearchReason.TIMEOUT : WebSearchReason.PROVIDER_FAILED, "博查不可用", ex);
        } catch (RestClientException ex) {
            throw new WebSearchProviderException(WebSearchReason.INVALID_RESPONSE,
                    "博查响应无法解析", ex);
        } catch (RuntimeException ex) {
            throw new WebSearchProviderException(WebSearchReason.PROVIDER_FAILED, "博查不可用", ex);
        }
    }

    private synchronized RestClient client() {
        if (client == null) {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(connectTimeout);
            factory.setReadTimeout(readTimeout);
            client = RestClient.builder().baseUrl(baseUrl.replaceAll("/+$", ""))
                    .requestFactory(factory).build();
        }
        return client;
    }

    private static String freshnessValue(WebSearchFreshness freshness) {
        return switch (freshness == null ? WebSearchFreshness.ANY : freshness) {
            case ANY -> "noLimit";
            case DAY -> "oneDay";
            case WEEK -> "oneWeek";
            case MONTH -> "oneMonth";
            case YEAR -> "oneYear";
        };
    }

    private static WebSearchReason reasonOf(int status) {
        return status == 401 || status == 403 ? WebSearchReason.AUTH_FAILED
                : status == 429 ? WebSearchReason.RATE_LIMITED
                : status == 408 || status == 504 ? WebSearchReason.TIMEOUT
                : status >= 500 ? WebSearchReason.PROVIDER_FAILED
                : WebSearchReason.PROVIDER_FAILED;
    }

    private static boolean isTimeout(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof SocketTimeoutException
                    || current.getClass().getSimpleName().contains("Timeout")) {
                return true;
            }
        }
        return false;
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node == null ? null : node.get(name);
        return value != null && value.isValueNode() && !value.isNull() ? value.asText() : null;
    }

    private static String firstText(JsonNode node, String first, String second) {
        String value = text(node, first);
        return StringUtils.hasText(value) ? value : text(node, second);
    }
}

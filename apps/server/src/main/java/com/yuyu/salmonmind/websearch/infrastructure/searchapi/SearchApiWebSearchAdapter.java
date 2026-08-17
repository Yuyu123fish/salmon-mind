package com.yuyu.salmonmind.websearch.infrastructure.searchapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuyu.salmonmind.websearch.api.WebSearchService.WebSearchFreshness;
import com.yuyu.salmonmind.websearch.api.WebSearchService.WebSearchProvider;
import com.yuyu.salmonmind.websearch.api.WebSearchService.WebSearchReason;
import com.yuyu.salmonmind.websearch.application.port.WebSearchProviderException;
import com.yuyu.salmonmind.websearch.application.port.WebSearchProviderPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** SearchApi.io Google organic-results Adapter；只读取 organic_results，不读取 AI/垂直卡片。 */
@Component
public class SearchApiWebSearchAdapter implements WebSearchProviderPort {

    private final String baseUrl;
    private final String apiKey;
    private final String gl;
    private final String hl;
    private final String safe;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private volatile RestClient client;

    public SearchApiWebSearchAdapter(
            @Value("${salmon.websearch.search-api.base-url:https://www.searchapi.io}") String baseUrl,
            @Value("${salmon.websearch.search-api.api-key:}") String apiKey,
            @Value("${salmon.websearch.search-api.gl:cn}") String gl,
            @Value("${salmon.websearch.search-api.hl:zh-cn}") String hl,
            @Value("${salmon.websearch.search-api.safe:active}") String safe,
            @Value("${salmon.websearch.connect-timeout:5s}") Duration connectTimeout,
            @Value("${salmon.websearch.read-timeout:15s}") Duration readTimeout
    ) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.gl = gl;
        this.hl = hl;
        this.safe = safe;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    @Override
    public WebSearchProvider provider() {
        return WebSearchProvider.SEARCH_API;
    }

    @Override
    public RawSearchResult search(String query, WebSearchFreshness freshness, int count) {
        if (!StringUtils.hasText(baseUrl) || !StringUtils.hasText(apiKey)) {
            throw new WebSearchProviderException(WebSearchReason.NOT_CONFIGURED, "SearchApi.io 未配置");
        }
        try {
            JsonNode response = client().get()
                    .uri(builder -> uri(builder, query, freshness, gl, hl, safe))
                    .header("Authorization", "Bearer " + apiKey)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null || !response.has("organic_results")
                    || !response.get("organic_results").isArray()) {
                throw new WebSearchProviderException(WebSearchReason.INVALID_RESPONSE,
                        "SearchApi.io 响应缺少 organic_results");
            }
            List<RawSearchHit> hits = new ArrayList<>();
            for (JsonNode item : response.get("organic_results")) {
                hits.add(new RawSearchHit(
                        item.path("position").asInt(0), text(item, "title"), text(item, "link"),
                        firstText(item, "source", "domain"), text(item, "snippet"), text(item, "date")));
            }
            JsonNode metadata = response.get("search_metadata");
            return new RawSearchResult(hits, text(metadata, "id"));
        } catch (WebSearchProviderException ex) {
            throw ex;
        } catch (RestClientResponseException ex) {
            throw new WebSearchProviderException(reasonOf(ex.getStatusCode().value()),
                    "SearchApi.io 请求失败", ex);
        } catch (ResourceAccessException ex) {
            throw new WebSearchProviderException(isTimeout(ex)
                    ? WebSearchReason.TIMEOUT : WebSearchReason.PROVIDER_FAILED,
                    "SearchApi.io 不可用", ex);
        } catch (RestClientException ex) {
            throw new WebSearchProviderException(WebSearchReason.INVALID_RESPONSE,
                    "SearchApi.io 响应无法解析", ex);
        } catch (RuntimeException ex) {
            throw new WebSearchProviderException(WebSearchReason.PROVIDER_FAILED,
                    "SearchApi.io 不可用", ex);
        }
    }

    private static java.net.URI uri(
            UriBuilder builder, String query, WebSearchFreshness freshness,
            String gl, String hl, String safe
    ) {
        UriBuilder current = builder.path("/api/v1/search")
                .queryParam("engine", "google")
                .queryParam("page", 1)
                .queryParam("q", query)
                .queryParam("gl", gl)
                .queryParam("hl", hl)
                .queryParam("safe", safe);
        String timePeriod = switch (freshness == null ? WebSearchFreshness.ANY : freshness) {
            case ANY -> null;
            case DAY -> "last_day";
            case WEEK -> "last_week";
            case MONTH -> "last_month";
            case YEAR -> "last_year";
        };
        if (timePeriod != null) {
            current.queryParam("time_period", timePeriod);
        }
        return current.build();
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

    private static WebSearchReason reasonOf(int status) {
        return status == 401 || status == 403 ? WebSearchReason.AUTH_FAILED
                : status == 429 ? WebSearchReason.RATE_LIMITED
                : status == 408 || status == 504 ? WebSearchReason.TIMEOUT
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

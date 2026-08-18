package com.yuyu.salmonmind.agent.infrastructure.reactagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.salmonmind.websearch.api.WebSearchService;
import com.yuyu.salmonmind.websearch.api.WebSearchService.WebSearchHit;
import com.yuyu.salmonmind.websearch.api.WebSearchService.WebSearchProvider;
import com.yuyu.salmonmind.websearch.api.WebSearchService.WebSearchReason;
import com.yuyu.salmonmind.websearch.api.WebSearchService.WebSearchStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

/** 两个网页 ToolCallback 的同构输入、Provider 隔离和结构化结果测试。 */
class WebSearchToolCallbackTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void exposesBothProvidersWithTheSameSafeEnvelope() throws Exception {
        WebSearchService service = (provider, request) -> new WebSearchService.WebSearchResult(
                provider, WebSearchStatus.SUCCESS, WebSearchReason.NONE,
                List.of(new WebSearchHit(provider, 1, "结果", "https://example.com/result",
                        "example.com", "摘要", "昨天", Instant.parse("2026-08-17T00:00:00Z"))),
                "private-trace");

        for (WebSearchProvider provider : List.of(WebSearchProvider.BOCHA, WebSearchProvider.SEARCH_API)) {
            WebSearchToolCallback callback = new WebSearchToolCallback(mapper, service, provider);

            JsonNode definition = mapper.readTree(callback.getToolDefinition().inputSchema());
            JsonNode output = mapper.readTree(callback.call("{\"query\":\"最新消息\"}"));

            assertThat(definition.path("additionalProperties").asBoolean()).isFalse();
            assertThat(output.path("provider").asText()).isEqualTo(provider.name());
            assertThat(output.path("items").get(0).path("url").asText())
                    .isEqualTo("https://example.com/result");
            assertThat(output.toString()).doesNotContain("private-trace", "query");
            assertThat(mapper.readTree(callback.call("{\"query\":123}"))
                    .path("reason").asText()).isEqualTo("INVALID_QUERY");
            assertThat(mapper.readTree(callback.call("{\"query\":"))
                    .path("reason").asText()).isEqualTo("INVALID_QUERY");
            assertThat(mapper.readTree(callback.call("{\"query\":\"x\",\"extra\":true}"))
                    .path("reason").asText()).isEqualTo("INVALID_QUERY");
        }
    }
}

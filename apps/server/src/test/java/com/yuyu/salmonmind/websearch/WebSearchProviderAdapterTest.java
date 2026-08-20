package com.yuyu.salmonmind.websearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yuyu.salmonmind.websearch.api.WebSearchService.WebSearchFreshness;
import com.yuyu.salmonmind.websearch.api.WebSearchService.WebSearchReason;
import com.yuyu.salmonmind.websearch.application.port.WebSearchProviderException;
import com.yuyu.salmonmind.websearch.application.port.WebSearchProviderPort.RawSearchResult;
import com.yuyu.salmonmind.websearch.infrastructure.searchapi.SearchApiWebSearchAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/** SearchApi.io Adapter 只通过本地 HTTP Stub 验证请求边界，不访问真实付费服务。 */
class WebSearchProviderAdapterTest {

    private com.sun.net.httpserver.HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void searchApiUsesOrganicResultsBearerAndNoApiKeyOrNumQuery() throws Exception {
        AtomicReference<String> query = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/search", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, """
                    {"answer_box":{"answer":"不要读取"},"organic_results":[{"position":2,"title":"Google 页面","link":"https://example.org/result","source":"example.org","snippet":"结果","date":"2 days ago"}],"search_metadata":{"id":"searchapi-trace","json_url":"https://private.invalid/json"}}
                    """);
        });
        server.start();

        SearchApiWebSearchAdapter adapter = new SearchApiWebSearchAdapter(
                baseUrl(), "searchapi-secret", "us", "en", "active",
                java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1));
        RawSearchResult result = adapter.search("spring ai", WebSearchFreshness.WEEK, 5);

        assertThat(query).hasValue("engine=google&page=1&q=spring%20ai&gl=us&hl=en&safe=active&time_period=last_week");
        assertThat(query.get()).doesNotContain("api_key", "num");
        assertThat(authorization).hasValue("Bearer searchapi-secret");
        assertThat(result.hits()).singleElement().satisfies(hit -> {
            assertThat(hit.rank()).isEqualTo(2);
            assertThat(hit.title()).isEqualTo("Google 页面");
            assertThat(hit.url()).isEqualTo("https://example.org/result");
            assertThat(hit.dateLabel()).isEqualTo("2 days ago");
        });
        assertThat(result.traceId()).isEqualTo("searchapi-trace");
    }

    @Test
    void mapsHttpFailuresAndMalformedBodyToStableReasons() throws Exception {
        assertSearchApiStatus(401, WebSearchReason.AUTH_FAILED);
        assertSearchApiStatus(403, WebSearchReason.AUTH_FAILED);
        assertSearchApiStatus(429, WebSearchReason.RATE_LIMITED);
        assertSearchApiStatus(408, WebSearchReason.TIMEOUT);
        assertSearchApiStatus(504, WebSearchReason.TIMEOUT);
        assertSearchApiStatus(500, WebSearchReason.PROVIDER_FAILED);

        server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/search", exchange -> respond(exchange, 200, "not-json"));
        server.start();
        SearchApiWebSearchAdapter adapter = adapter();
        assertThatThrownBy(() -> adapter.search("query", WebSearchFreshness.ANY, 5))
                .isInstanceOfSatisfying(WebSearchProviderException.class,
                        error -> assertThat(error.reason()).isEqualTo(WebSearchReason.INVALID_RESPONSE));
    }

    private void assertSearchApiStatus(int status, WebSearchReason expected) throws Exception {
        server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/search", exchange -> respond(exchange, status, "{}"));
        server.start();
        SearchApiWebSearchAdapter adapter = adapter();
        assertThatThrownBy(() -> adapter.search("query", WebSearchFreshness.ANY, 5))
                .isInstanceOfSatisfying(WebSearchProviderException.class,
                        error -> assertThat(error.reason()).isEqualTo(expected));
        server.stop(0);
        server = null;
    }

    private SearchApiWebSearchAdapter adapter() {
        return new SearchApiWebSearchAdapter(
                baseUrl(), "searchapi-secret", "us", "en", "active",
                java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1));
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        respond(exchange, 200, body);
    }

    private static void respond(
            com.sun.net.httpserver.HttpExchange exchange, int status, String body
    ) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}

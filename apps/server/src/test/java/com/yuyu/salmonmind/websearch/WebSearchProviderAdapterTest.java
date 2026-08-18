package com.yuyu.salmonmind.websearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.yuyu.salmonmind.websearch.api.WebSearchService.WebSearchFreshness;
import com.yuyu.salmonmind.websearch.api.WebSearchService.WebSearchReason;
import com.yuyu.salmonmind.websearch.application.port.WebSearchProviderPort.RawSearchResult;
import com.yuyu.salmonmind.websearch.application.port.WebSearchProviderException;
import com.yuyu.salmonmind.websearch.infrastructure.bocha.BochaWebSearchAdapter;
import com.yuyu.salmonmind.websearch.infrastructure.searchapi.SearchApiWebSearchAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/** 两个 Provider 只通过本地 HTTP Stub 验证请求边界，不访问真实付费服务。 */
class WebSearchProviderAdapterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void bochaUsesBearerPostAndMapsOriginalWebPages() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/web-search", exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getRawPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respond(exchange, """
                    {"code":200,"log_id":"bocha-trace","data":{"webPages":{"value":[{"name":"官方页面","url":"https://example.com/a","siteName":"example.com","summary":"摘要","datePublished":"2026-08-17"}]}}}
                    """);
        });
        server.start();

        BochaWebSearchAdapter adapter = new BochaWebSearchAdapter(
                baseUrl(), "bocha-secret", Duration.ofSeconds(1), Duration.ofSeconds(1));
        RawSearchResult result = adapter.search("SalmonMind", WebSearchFreshness.DAY, 5);

        assertThat(method).hasValue("POST");
        assertThat(path).hasValue("/v1/web-search");
        assertThat(authorization).hasValue("Bearer bocha-secret");
        JsonNode request = mapper.readTree(body.get());
        assertThat(request.path("query").asText()).isEqualTo("SalmonMind");
        assertThat(request.path("freshness").asText()).isEqualTo("oneDay");
        assertThat(request.path("summary").asBoolean()).isTrue();
        assertThat(request.path("count").asInt()).isEqualTo(5);
        assertThat(result.hits()).hasSize(1);
        assertThat(result.hits().get(0).url()).isEqualTo("https://example.com/a");
        assertThat(result.traceId()).isEqualTo("bocha-trace");
        assertThat(body.get()).doesNotContain("bocha-secret");
    }

    @Test
    void acceptsAValidEmptyEnvelopeAndRejectsMalformedDataShapes() throws Exception {
        assertBochaBody("{\"code\":200,\"log_id\":\"empty\",\"data\":{\"webPages\":{\"value\":[]}}}",
                false);
        assertBochaBody("{\"code\":200,\"data\":{}}", true);
        assertBochaBody("{\"code\":200,\"data\":{\"webPages\":{}}}", true);
        assertBochaBody("{\"code\":200,\"data\":{\"webPages\":{\"value\":{}}}}", true);
        assertBochaBody("{\"code\":999,\"msg\":\"鉴权失败\",\"data\":{\"webPages\":{\"value\":[]}}}", true);
    }

    @Test
    void searchApiUsesOrganicResultsBearerAndNoApiKeyOrNumQuery() throws Exception {
        AtomicReference<String> query = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
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
                Duration.ofSeconds(1), Duration.ofSeconds(1));
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
    void mapsBochaHttpFailuresAndMalformedBodyToStableReasons() throws Exception {
        assertBochaStatus(401, WebSearchReason.AUTH_FAILED);
        assertBochaStatus(403, WebSearchReason.AUTH_FAILED);
        assertBochaStatus(429, WebSearchReason.RATE_LIMITED);
        assertBochaStatus(408, WebSearchReason.TIMEOUT);
        assertBochaStatus(504, WebSearchReason.TIMEOUT);
        assertBochaStatus(500, WebSearchReason.PROVIDER_FAILED);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/web-search", exchange -> respond(exchange, 200, "not-json"));
        server.start();
        BochaWebSearchAdapter adapter = new BochaWebSearchAdapter(
                baseUrl(), "bocha-secret", Duration.ofSeconds(1), Duration.ofSeconds(1));

        assertThatThrownBy(() -> adapter.search("query", WebSearchFreshness.ANY, 5))
                .isInstanceOfSatisfying(WebSearchProviderException.class,
                        error -> assertThat(error.reason()).isEqualTo(WebSearchReason.INVALID_RESPONSE));
    }

    private void assertBochaStatus(int status, WebSearchReason expected) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/web-search", exchange -> respond(exchange, status, "{}"));
        server.start();
        BochaWebSearchAdapter adapter = new BochaWebSearchAdapter(
                baseUrl(), "bocha-secret", Duration.ofSeconds(1), Duration.ofSeconds(1));

        assertThatThrownBy(() -> adapter.search("query", WebSearchFreshness.ANY, 5))
                .isInstanceOfSatisfying(WebSearchProviderException.class,
                        error -> assertThat(error.reason()).isEqualTo(expected));
        server.stop(0);
        server = null;
    }

    private void assertBochaBody(String body, boolean invalid) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/web-search", exchange -> respond(exchange, 200, body));
        server.start();
        BochaWebSearchAdapter adapter = new BochaWebSearchAdapter(
                baseUrl(), "bocha-secret", Duration.ofSeconds(1), Duration.ofSeconds(1));

        if (invalid) {
            assertThatThrownBy(() -> adapter.search("query", WebSearchFreshness.ANY, 5))
                    .isInstanceOfSatisfying(WebSearchProviderException.class,
                            error -> assertThat(error.reason()).isEqualTo(WebSearchReason.INVALID_RESPONSE));
        } else {
            assertThat(adapter.search("query", WebSearchFreshness.ANY, 5).hits()).isEmpty();
        }
        server.stop(0);
        server = null;
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

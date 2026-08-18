package com.yuyu.salmonmind.model.infrastructure.siliconflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.salmonmind.model.rerank.RerankService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** 使用本地 HTTP Stub 锁定 SiliconFlow Rerank 请求合同，不访问外网。 */
class SiliconFlowRerankServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<byte[]> requestBody = new AtomicReference<>();
    private HttpServer server;

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/rerank", this::respond);
        server.start();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    @Test
    void sendsFrozenRequestAndMapsProviderIndexes() throws Exception {
        SiliconFlowRerankService service = service();
        var result = service.rerank("本地资料", List.of("第一段", "第二段"), 5);

        JsonNode request = mapper.readTree(requestBody.get());
        assertThat(request.path("model").asText()).isEqualTo(RerankService.MODEL);
        assertThat(request.path("query").asText()).isEqualTo("本地资料");
        assertThat(request.path("documents").isArray()).isTrue();
        assertThat(request.path("documents").get(0).asText()).isEqualTo("第一段");
        assertThat(request.path("documents").get(1).asText()).isEqualTo("第二段");
        assertThat(request.path("top_n").asInt()).isEqualTo(2);
        assertThat(request.path("return_documents").asBoolean()).isFalse();
        assertThat(request.path("instruction").asText()).isEqualTo(RerankService.INSTRUCTION);
        assertThat(result.results()).extracting(item -> item.index()).containsExactly(1, 0);
        assertThat(result.results()).extracting(item -> item.score()).containsExactly(0.9d, 0.2d);
    }

    private SiliconFlowRerankService service() {
        return new SiliconFlowRerankService(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "test-key", RerankService.MODEL, RerankService.INSTRUCTION,
                Duration.ofSeconds(2), Duration.ofSeconds(2));
    }

    private void respond(HttpExchange exchange) throws IOException {
        try (exchange) {
            requestBody.set(exchange.getRequestBody().readAllBytes());
            byte[] body = "{\"results\":[{\"index\":1,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.2}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
    }
}

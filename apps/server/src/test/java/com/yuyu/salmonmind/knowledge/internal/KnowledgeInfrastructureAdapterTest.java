package com.yuyu.salmonmind.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class KnowledgeInfrastructureAdapterTest {

    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void storesAndReadsObjectsThroughTheRustFsCompatibleS3Adapter() {
        var bucketExists = new AtomicBoolean();
        var stored = new AtomicReference<byte[]>();
        var authorization = new AtomicReference<String>();
        server.createContext("/salmon-knowledge", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/salmon-knowledge") && exchange.getRequestMethod().equals("HEAD")) {
                respond(exchange, bucketExists.get() ? 200 : 404, new byte[0], null);
            } else if (path.equals("/salmon-knowledge") && exchange.getRequestMethod().equals("PUT")) {
                bucketExists.set(true);
                respond(exchange, 200, new byte[0], null);
            } else if (exchange.getRequestMethod().equals("PUT")) {
                stored.set(exchange.getRequestBody().readAllBytes());
                respond(exchange, 200, new byte[0], null);
            } else if (exchange.getRequestMethod().equals("GET") && stored.get() != null) {
                respond(exchange, 200, stored.get(), "application/octet-stream");
            } else if (exchange.getRequestMethod().equals("DELETE")) {
                stored.set(null);
                respond(exchange, 204, new byte[0], null);
            } else {
                respond(exchange, 404, new byte[0], null);
            }
        });
        var properties = new KnowledgeProperties.ContentStoreProperties(
                baseUrl(),
                "test-access",
                "test-secret",
                "salmon-knowledge",
                "us-east-1"
        );

        try (var store = new S3ContentStore(properties, 1024)) {
            store.put("sources/one/content", "raw-source".getBytes(StandardCharsets.UTF_8), "text/plain");

            assertThat(new String(store.get("sources/one/content"), StandardCharsets.UTF_8))
                    .isEqualTo("raw-source");
            assertThat(authorization.get()).startsWith("AWS4-HMAC-SHA256").doesNotContain("test-secret");

            store.delete("sources/one/content");
            assertThat(stored.get()).isNull();
        }
    }

    @Test
    void mapsEvidenceThroughTheElasticsearchAdapter() {
        var requests = new ArrayList<Request>();
        UUID evidenceId = UUID.randomUUID();
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Request(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().toString(),
                    body,
                    exchange.getRequestHeaders().getFirst("Authorization")
            ));
            String path = exchange.getRequestURI().getPath();
            if (exchange.getRequestMethod().equals("PUT") && path.equals("/salmon-evidence-test")) {
                respondJson(exchange, 200, "{\"acknowledged\":true}");
            } else if (path.contains("/_doc/")) {
                respondJson(exchange, 201, "{\"result\":\"created\"}");
            } else if (path.endsWith("/_refresh")) {
                respondJson(exchange, 200, "{\"_shards\":{\"successful\":1}}");
            } else if (path.endsWith("/_search")) {
                respondJson(exchange, 200, """
                        {"hits":{"hits":[{
                          "_score":0.91,
                          "_source":{
                            "evidenceId":"%s",
                            "text":"alpha evidence",
                            "contentSha256":"%s"
                          }
                        }]}}
                        """.formatted(evidenceId, Hashing.sha256("alpha evidence")));
            } else {
                respondJson(exchange, 404, "{\"error\":\"not_found\"}");
            }
        });
        var index = new ElasticsearchSearchIndex(
                RestClient.builder().baseUrl(baseUrl()).build(),
                "elastic",
                "password"
        );
        UUID revisionId = UUID.randomUUID();
        var document = new SearchIndex.IndexDocument(
                evidenceId,
                revisionId,
                "source.txt:L1-L1",
                "alpha evidence",
                Hashing.sha256("alpha evidence"),
                List.of(1.0, 0.0, 0.0)
        );

        index.create(new SearchIndex.IndexSpec("salmon-evidence-test", 3, 1, 0));
        index.index("salmon-evidence-test", List.of(document));
        index.refresh("salmon-evidence-test");
        var hits = index.search("salmon-evidence-test", List.of(1.0, 0.0, 0.0), 5);

        assertThat(hits).containsExactly(new SearchIndex.SearchHit(
                evidenceId,
                "alpha evidence",
                Hashing.sha256("alpha evidence"),
                0.91
        ));
        assertThat(requests)
                .anySatisfy(request -> assertThat(request.body())
                        .contains("dense_vector", "\"dims\":3", "\"number_of_shards\":1"))
                .anySatisfy(request -> assertThat(request.body())
                        .contains("\"evidenceId\":\"" + evidenceId + "\"", "\"embedding\":[1.0,0.0,0.0]"))
                .anySatisfy(request -> assertThat(request.body())
                        .contains("\"knn\"", "\"query_vector\":[1.0,0.0,0.0]"));
        assertThat(requests).allSatisfy(request -> {
            assertThat(request.authorization()).startsWith("Basic ");
            assertThat(request.authorization()).doesNotContain("password");
        });
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respondJson(HttpExchange exchange, int status, String body) throws IOException {
        respond(exchange, status, body.getBytes(StandardCharsets.UTF_8), "application/json");
    }

    private static void respond(HttpExchange exchange, int status, byte[] body, String contentType)
            throws IOException {
        if (contentType != null) {
            exchange.getResponseHeaders().set("Content-Type", contentType);
        }
        if (body.length == 0) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }

    private record Request(String method, String uri, String body, String authorization) {
    }
}

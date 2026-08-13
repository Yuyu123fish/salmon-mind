package com.yuyu.salmonmind.model.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yuyu.salmonmind.model.ModelExecutionException;
import com.yuyu.salmonmind.model.ModelGateway;
import com.yuyu.salmonmind.model.ModelGateway.EmbeddingInput;
import com.yuyu.salmonmind.model.ModelGateway.Message;
import com.yuyu.salmonmind.model.ModelGateway.Prompt;
import com.yuyu.salmonmind.model.ModelGateway.Role;
import com.yuyu.salmonmind.model.support.DeterministicModelAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

class ModelGatewayTest {

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
    void deterministicAdapterNeedsNoProviderOrSecret() {
        ModelGateway gateway = new DeterministicModelAdapter("fixed reply", 3);

        var completion = gateway.complete(prompt("question"));
        var embeddings = gateway.embed(new EmbeddingInput(List.of("alpha", "beta")));

        assertThat(completion.text()).isEqualTo("fixed reply");
        assertThat(completion.model()).isEqualTo("deterministic-chat");
        assertThat(embeddings.embeddings()).hasSize(2);
        assertThat(embeddings.dimensions()).isEqualTo(3);
    }

    @Test
    void mapsCompatibleChatAndEmbeddingResponsesThroughThePublicInterface() {
        var chatBody = new AtomicReference<String>();
        var embeddingBody = new AtomicReference<String>();
        var authorization = new AtomicReference<String>();

        server.createContext("/v1/chat/completions", exchange -> {
            chatBody.set(readBody(exchange));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, """
                    {"model":"provider-chat","choices":[{"message":{"content":"answer"}}]}
                    """);
        });
        server.createContext("/v1/embeddings", exchange -> {
            embeddingBody.set(readBody(exchange));
            respond(exchange, 200, """
                    {"model":"provider-embedding","data":[
                      {"index":1,"embedding":[0.3,0.4]},
                      {"index":0,"embedding":[0.1,0.2]}
                    ]}
                    """);
        });
        ModelGateway gateway = gateway(configuredProperties());

        var completion = gateway.complete(prompt("question"));
        var embeddings = gateway.embed(new EmbeddingInput(List.of("first", "second")));

        assertThat(completion.text()).isEqualTo("answer");
        assertThat(completion.model()).isEqualTo("provider-chat");
        assertThat(embeddings.model()).isEqualTo("provider-embedding");
        assertThat(embeddings.embeddings().getFirst().values()).containsExactly(0.1, 0.2);
        assertThat(embeddings.embeddings().get(1).values()).containsExactly(0.3, 0.4);
        assertThat(chatBody.get()).contains("\"model\":\"chat-test\"");
        assertThat(embeddingBody.get()).contains("\"model\":\"embedding-test\"");
        assertThat(authorization.get()).isNull();
    }

    @Test
    void convertsProviderRateLimitToStableRetryableFailure() {
        server.createContext("/v1/chat/completions", exchange ->
                respond(exchange, 429, "{\"error\":\"provider-only-detail\"}"));
        var properties = configuredProperties();
        properties.getChat().setApiKey("test-secret");
        ModelGateway gateway = gateway(properties);

        assertThatThrownBy(() -> gateway.complete(prompt("question")))
                .isInstanceOf(ModelExecutionException.class)
                .satisfies(exception -> {
                    var modelFailure = (ModelExecutionException) exception;
                    assertThat(modelFailure.kind()).isEqualTo(ModelExecutionException.Kind.RATE_LIMITED);
                    assertThat(modelFailure.retryable()).isTrue();
                    assertThat(modelFailure).hasNoCause();
                    assertThat(modelFailure.getMessage())
                            .doesNotContain("provider-only-detail")
                            .doesNotContain("test-secret");
                });
    }

    @Test
    void applicationContextStartsWithoutModelConfigurationAndFailsOnlyOnInvocation() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RestClientAutoConfiguration.class))
                .withUserConfiguration(ModelConfiguration.class)
                .withPropertyValues(
                        "salmon.model.connect-timeout=1s",
                        "salmon.model.read-timeout=2s"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ModelGateway.class);
                    assertThat(context.getBean(ModelProperties.class).getConnectTimeout())
                            .isEqualTo(Duration.ofSeconds(1));

                    var gateway = context.getBean(ModelGateway.class);
                    assertThatThrownBy(() -> gateway.complete(prompt("question")))
                            .isInstanceOf(ModelExecutionException.class)
                            .satisfies(exception -> {
                                var modelFailure = (ModelExecutionException) exception;
                                assertThat(modelFailure.kind())
                                        .isEqualTo(ModelExecutionException.Kind.NOT_CONFIGURED);
                                assertThat(modelFailure).hasNoCause();
                            });
                });
    }

    private ModelGateway gateway(ModelProperties properties) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(2));
        return new OpenAiCompatibleModelAdapter(
                RestClient.builder().requestFactory(requestFactory).build(),
                properties
        );
    }

    private ModelProperties configuredProperties() {
        var properties = new ModelProperties();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        properties.getChat().setBaseUrl(baseUrl);
        properties.getChat().setModelName("chat-test");
        properties.getEmbedding().setBaseUrl(baseUrl);
        properties.getEmbedding().setModelName("embedding-test");
        return properties;
    }

    private static Prompt prompt(String content) {
        return new Prompt(List.of(new Message(Role.USER, content)));
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}

package com.yuyu.salmonmind.agent.internal;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.salmonmind.agent.AgentRuntime;
import com.yuyu.salmonmind.agent.AgentRuntime.RunBudget;
import com.yuyu.salmonmind.agent.AgentRuntime.RunRequest;
import com.yuyu.salmonmind.agent.AgentRuntime.RunStatus;
import com.yuyu.salmonmind.agent.AgentRuntime.TerminationReason;
import com.yuyu.salmonmind.agent.AgentRuntime.Tool;
import com.yuyu.salmonmind.agent.AgentRuntime.ToolContext;
import com.yuyu.salmonmind.agent.AgentRuntime.ToolDefinition;
import com.yuyu.salmonmind.agent.AgentRuntime.ToolResult;
import com.yuyu.salmonmind.model.ModelGateway;
import org.junit.jupiter.api.Test;

class AgentRuntimeTest {

    @Test
    void runsToolsAndAppliesTheResultBudget() {
        var model = new ScriptedModel(
                "{\"type\":\"tool\",\"name\":\"echo\",\"arguments\":{}}",
                "{\"type\":\"final\",\"text\":\"done\"}"
        );
        AgentRuntime runtime = runtime(model);

        var result = runtime.run(
                new RunRequest(UUID.randomUUID(), "Be useful.", "go", new RunBudget(3, 4)),
                List.of(new EchoTool())
        );

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.output()).isEqualTo("done");
        assertThat(result.steps()).isEqualTo(2);
        assertThat(result.usedToolResultChars()).isEqualTo(4);
        assertThat(result.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.result()).isEqualTo("abcd");
            assertThat(call.truncated()).isTrue();
        });
        assertThat(model.lastPrompt()).contains("TOOL_RESULT", "\"truncated\":true");
    }

    @Test
    void returnsClearLimitsAndFailures() {
        var limited = runtime(new ScriptedModel(
                "{\"type\":\"tool\",\"name\":\"echo\",\"arguments\":{}}"
        )).run(
                new RunRequest(UUID.randomUUID(), "Be useful.", "go", new RunBudget(1, 20)),
                List.of(new EchoTool())
        );
        assertThat(limited.status()).isEqualTo(RunStatus.LIMITED);
        assertThat(limited.terminationReason()).isEqualTo(TerminationReason.STEP_LIMIT);

        var invalid = runtime(new ScriptedModel("not-json")).run(
                new RunRequest(UUID.randomUUID(), "Be useful.", "go", new RunBudget(1, 20)),
                List.of()
        );
        assertThat(invalid.status()).isEqualTo(RunStatus.FAILED);
        assertThat(invalid.terminationReason()).isEqualTo(TerminationReason.INVALID_MODEL_RESPONSE);

        assertThatThrownBy(() -> runtime(new ScriptedModel()).run(
                new RunRequest(UUID.randomUUID(), "x".repeat(5000), "go", new RunBudget(1, 20)),
                List.of()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prompt");
    }

    @Test
    void cancelsCooperativelyAfterAModelCall() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        ModelGateway model = new BlockingModel(entered, release);
        AgentRuntime runtime = runtime(model);
        UUID runId = UUID.randomUUID();

        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> runtime.run(
                    new RunRequest(runId, "Be useful.", "go", new RunBudget(2, 20)),
                    List.of()
            ));
            assertThat(entered.await(5, SECONDS)).isTrue();
            assertThat(runtime.cancel(runId)).isTrue();
            release.countDown();

            var cancelled = future.get(5, SECONDS);
            assertThat(cancelled.status()).isEqualTo(RunStatus.CANCELLED);
            assertThat(cancelled.terminationReason()).isEqualTo(TerminationReason.CANCELLED);
        }
    }

    private static AgentRuntime runtime(ModelGateway model) {
        return new DefaultAgentRuntime(model, new ObjectMapper(), 8, 1024, 8, 4096, 4096);
    }

    private static final class EchoTool implements Tool {

        @Override
        public ToolDefinition definition() {
            return new ToolDefinition("echo", "Returns test text.", "{\"type\":\"object\"}");
        }

        @Override
        public ToolResult execute(ToolContext context, String argumentsJson) {
            return new ToolResult("abcdefghij");
        }
    }

    private static final class ScriptedModel implements ModelGateway {

        private final ArrayDeque<String> responses;
        private Prompt lastPrompt;

        private ScriptedModel(String... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public Completion complete(Prompt prompt) {
            lastPrompt = prompt;
            return new Completion(responses.removeFirst(), "test-model");
        }

        @Override
        public EmbeddingBatch embed(EmbeddingInput input) {
            throw new UnsupportedOperationException();
        }

        private String lastPrompt() {
            return lastPrompt.messages().toString();
        }
    }

    private record BlockingModel(CountDownLatch entered, CountDownLatch release) implements ModelGateway {

        @Override
        public Completion complete(Prompt prompt) {
            entered.countDown();
            try {
                if (!release.await(5, SECONDS)) {
                    throw new AssertionError("model was not released");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
            return new Completion("{\"type\":\"final\",\"text\":\"late\"}", "test-model");
        }

        @Override
        public EmbeddingBatch embed(EmbeddingInput input) {
            throw new UnsupportedOperationException();
        }
    }
}

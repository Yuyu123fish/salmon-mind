package com.yuyu.salmonmind.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.salmonmind.agent.api.AgentExecutionException;
import com.yuyu.salmonmind.agent.api.AgentExecutionException.AgentErrorCode;
import com.yuyu.salmonmind.agent.api.AgentMessage;
import com.yuyu.salmonmind.agent.api.AgentRequest;
import com.yuyu.salmonmind.agent.api.AgentResult;
import com.yuyu.salmonmind.agent.api.AgentSession;
import com.yuyu.salmonmind.agent.api.AgentUsage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

/**
 * Conversation 模块 HTTP 集成测试：RANDOM_PORT + Testcontainers PostgreSQL + 临时数据目录 +
 * 测试侧确定性 AgentSession（@Primary，覆盖 agent::api seam）。覆盖创建、列表、两轮上下文、
 * Conversation 隔离、失败与刷新后重试、重试不重复用户 Entry、遗留 RUNNING 恢复、上下文超限、
 * 稳定错误，以及用可控阻塞 Agent 验证同 Conversation 串行与不同 Conversation 并行。
 * 制造数据库落后状态使用测试侧 SQL/JdbcTemplate，不导入 Entity 或 Mapper。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:tc:postgresql:17.10-alpine:///salmon_mind",
                "spring.datasource.username=test",
                "spring.datasource.password=test",
                // 上下文限制用小值便于在测试内触发 CONTEXT_LIMIT_REACHED
                "salmon.agent.max-prompt-chars=500"
        }
)
class ConversationModuleIntegrationTest {

    private static final Path DATA_DIR = Path.of(
            System.getProperty("java.io.tmpdir"), "salmon-mind-conv-http-test-" + UUID.randomUUID());

    /** 测试用确定性 Agent 单例；每个测试方法前重置状态。 */
    private static final DeterministicAgent AGENT = new DeterministicAgent();

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("salmon.conversation.data-dir", () -> DATA_DIR.toString());
    }

    @TestConfiguration
    static class TestAgentConfig {

        @Bean
        @Primary
        AgentSession testAgentSession() {
            return AGENT;
        }
    }

    @BeforeAll
    static void createDataDir() throws IOException {
        Files.createDirectories(DATA_DIR);
    }

    @AfterAll
    static void cleanupDataDir() throws IOException {
        if (Files.exists(DATA_DIR)) {
            try (var paths = Files.walk(DATA_DIR)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ex) {
                        throw new RuntimeException(ex);
                    }
                });
            }
        }
    }

    @BeforeEach
    void resetAgent() {
        AGENT.reset();
    }

    // ---------- 用例 ----------

    @Test
    void createsListsAndOpensConversation() throws Exception {
        Map<String, Object> created = create();

        assertThat(created.get("title")).isEqualTo("新对话");

        String listJson = rest.getForEntity("/api/conversations", String.class).getBody();
        List<Map<String, Object>> list = parseList(listJson);
        assertThat(list).extracting(m -> m.get("id")).contains(created.get("id"));

        Map<String, Object> detail = open(created.get("id").toString());
        assertThat((List<?>) detail.get("activePath")).isEmpty();
        assertThat(detail.get("pendingRun")).isNull();
    }

    @Test
    void sendsTwoRoundsWithContextAndKeepsConversationsIsolated() throws Exception {
        UUID conv1 = createId();
        UUID conv2 = createId();

        Map<String, Object> first = send(conv1, "你好");
        assertSendResult(first, "你好");
        assertThat(agentRequest(0).threadId()).isEqualTo(conv1.toString());
        assertThat(agentRequest(0).expectedCheckpointLeafId()).isNull();
        assertThat(messagesOf(agentRequest(0))).containsExactly(new AgentMessage(AgentMessage.Role.USER, "你好"));

        Map<String, Object> second = send(conv1, "再讲一遍");
        assertSendResult(second, "再讲一遍");
        // 第二轮模型可见完整上下文，Checkpoint 标记等于第一轮回答叶子
        assertThat(messagesOf(agentRequest(1)))
                .containsExactly(
                        new AgentMessage(AgentMessage.Role.USER, "你好"),
                        new AgentMessage(AgentMessage.Role.ASSISTANT, "测试回答"),
                        new AgentMessage(AgentMessage.Role.USER, "再讲一遍"));
        assertThat(agentRequest(1).expectedCheckpointLeafId().toString())
                .isEqualTo(entryOf(first, "assistantEntry").get("id"));

        Map<String, Object> other = send(conv2, "另一个话题");
        assertSendResult(other, "另一个话题");
        assertThat(agentRequest(2).threadId()).isEqualTo(conv2.toString());
        assertThat(messagesOf(agentRequest(2))).containsExactly(
                new AgentMessage(AgentMessage.Role.USER, "另一个话题"));

        // 详情按 Active Path 返回完整两轮，标题为首条用户消息截断文本
        Map<String, Object> detail = open(conv1.toString());
        assertThat((List<?>) detail.get("activePath")).hasSize(4);
        assertThat(((Map<?, ?>) detail.get("conversation")).get("title")).isEqualTo("你好");
        // Assistant Entry 携带 provider / model 与映射后的用量（AgentUsage -> TokenUsage）
        Map<String, Object> last = lastEntry(detail);
        assertThat(last.get("type")).isEqualTo("ASSISTANT_MESSAGE");
        Map<String, Object> payload = payloadOf(last);
        assertThat(payload.get("provider")).isEqualTo("test-provider");
        assertThat(payload.get("model")).isEqualTo("test-model");
        assertThat(payload.get("usage")).isEqualTo(Map.of("promptTokens", 5, "completionTokens", 3, "totalTokens", 8));

        // JSONL 每轮两个 Entry：Header + 4 行
        assertThat(Files.readAllLines(fileOf(conv1))).hasSize(5);
        // 数据库不允许遗留 RUNNING Run
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM conversation_runs WHERE status = 'RUNNING'", Integer.class)).isZero();
    }

    @Test
    void failsThenRetriesWithoutDuplicatingUserEntry() throws Exception {
        UUID conv = createId();

        AGENT.failWith(new AgentExecutionException(AgentErrorCode.CHAT_MODEL_FAILED, "模型调用失败"));
        // 用无重试的普通客户端：TestRestTemplate 会被 Spring AI 的 retry 定制器在 503 时自动重发，
        // 重发会在 Run 已 FAILED 后命中 CONVERSATION_AWAITING_RETRY，掩盖真实的 503 断言
        ResponseEntity<String> failed = plainPost("/api/conversations/" + conv + "/messages", Map.of("text", "会失败的问题"));
        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(parse(failed.getBody())).containsEntry("code", "CHAT_MODEL_FAILED");

        // 刷新后：用户 Entry 保留，活动叶子仍是待回答用户 Entry，pendingRun 为 FAILED
        Map<String, Object> detail = open(conv.toString());
        assertThat((List<?>) detail.get("activePath")).hasSize(1);
        Map<String, Object> pending = (Map<String, Object>) detail.get("pendingRun");
        assertThat(pending).isNotNull();
        assertThat(pending.get("status")).isEqualTo("FAILED");
        assertThat(pending.get("errorCode")).isEqualTo("CHAT_MODEL_FAILED");
        String failedRunId = pending.get("id").toString();

        // 待回答状态不能发送新消息
        ResponseEntity<String> blocked = postJson("/api/conversations/" + conv + "/messages", Map.of("text", "新消息"));
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(parse(blocked.getBody())).containsEntry("code", "CONVERSATION_AWAITING_RETRY");

        // 重试复用原用户 Entry，不重复追加用户消息
        AGENT.clearFailure();
        ResponseEntity<String> retried = rest.postForEntity(
                "/api/conversations/" + conv + "/runs/" + failedRunId + "/retry", null, String.class);
        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> result = parse(retried.getBody());
        assertThat(result.get("run")).isNotNull();
        assertThat(((Map<?, ?>) result.get("run")).get("status")).isEqualTo("SUCCEEDED");
        assertThat(agentRequest(1).threadId()).isEqualTo(conv.toString());
        // 两次调用的模型可见消息完全一致：重试没有引入重复的用户消息
        assertThat(messagesOf(agentRequest(0))).isEqualTo(messagesOf(agentRequest(1)));

        Map<String, Object> afterRetry = open(conv.toString());
        List<?> path = (List<?>) afterRetry.get("activePath");
        assertThat(path).hasSize(2);
        assertThat(path.stream()
                .map(this::entryOf)
                .filter(e -> "USER_MESSAGE".equals(e.get("type")))
                .filter(e -> "会失败的问题".equals(payloadOf(e).get("text")))
                .count()).isEqualTo(1);
        // 同一触发 Entry 有两个 Run：一次 FAILED、一次 SUCCEEDED
        UUID triggerEntryId = UUID.fromString(entryOf(path.get(0)).get("id").toString());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM conversation_runs WHERE status = 'FAILED' AND trigger_entry_id = ?",
                Integer.class, triggerEntryId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM conversation_runs WHERE status = 'SUCCEEDED' AND trigger_entry_id = ?",
                Integer.class, triggerEntryId)).isEqualTo(1);
    }

    @Test
    void recoversStaleRunningRunToInterruptedOnFirstRead() throws Exception {
        UUID conv = createId();
        Map<String, Object> firstRound = send(conv, "第一轮");
        String firstAnswerId = entryOf(firstRound, "assistantEntry").get("id").toString();

        // 模拟旧进程在 startRun 提交后崩溃：JSONL 已有用户 Entry，数据库留下 RUNNING Run
        UUID staleRunId = UUID.randomUUID();
        UUID userEntryId = UUID.randomUUID();
        appendRawUser(conv, userEntryId, 3, firstAnswerId, "崩溃后的问题", staleRunId);
        jdbcTemplate.update(
                "INSERT INTO conversation_runs (id, conversation_id, trigger_entry_id, status, error_code, started_at, ended_at)"
                        + " VALUES (?, ?, ?, 'RUNNING', NULL, CURRENT_TIMESTAMP, NULL)",
                staleRunId, conv, userEntryId);
        jdbcTemplate.update(
                "UPDATE conversations SET active_leaf_entry_id = ?, last_confirmed_seq = 3 WHERE id = ?",
                userEntryId, conv);

        // 首次读取：遗留 RUNNING 恢复为 INTERRUPTED，可识别并重试
        Map<String, Object> detail = open(conv.toString());
        assertThat((List<?>) detail.get("activePath")).hasSize(3);
        Map<String, Object> pending = (Map<String, Object>) detail.get("pendingRun");
        assertThat(pending).isNotNull();
        assertThat(pending.get("status")).isEqualTo("INTERRUPTED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM conversation_runs WHERE id = ?", String.class, staleRunId))
                .isEqualTo("INTERRUPTED");

        // 重试成功，用户 Entry 不重复
        ResponseEntity<String> retried = rest.postForEntity(
                "/api/conversations/" + conv + "/runs/" + pending.get("id") + "/retry", null, String.class);
        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> afterRetry = open(conv.toString());
        List<?> path = (List<?>) afterRetry.get("activePath");
        assertThat(path).hasSize(4);
        assertThat(path.stream()
                .map(this::entryOf)
                .filter(e -> "USER_MESSAGE".equals(e.get("type")))
                .filter(e -> "崩溃后的问题".equals(payloadOf(e).get("text")))
                .count()).isEqualTo(1);
    }

    @Test
    void rejectsWhenContextExceedsHardLimitWithoutWritingEntry() throws Exception {
        UUID conv = createId();
        String longText = "长".repeat(600);

        ResponseEntity<String> rejected = postJson("/api/conversations/" + conv + "/messages", Map.of("text", longText));
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(parse(rejected.getBody())).containsEntry("code", "CONTEXT_LIMIT_REACHED");

        // 超限拒绝发生在追加用户 Entry 之前：历史保持为空，不静默裁剪
        Map<String, Object> detail = open(conv.toString());
        assertThat((List<?>) detail.get("activePath")).isEmpty();
        assertThat(Files.readAllLines(fileOf(conv))).hasSize(1);

        // 短消息仍可用
        ResponseEntity<String> ok = postJson("/api/conversations/" + conv + "/messages", Map.of("text", "短消息"));
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void mapsStableErrorsToHttpStatuses() {
        UUID conv = createId();

        ResponseEntity<String> notFound = rest.getForEntity("/api/conversations/" + UUID.randomUUID(), String.class);
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(parse(notFound.getBody())).containsEntry("code", "CONVERSATION_NOT_FOUND");

        ResponseEntity<String> blank = postJson("/api/conversations/" + conv + "/messages", Map.of("text", "   "));
        assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(parse(blank.getBody())).containsEntry("code", "INVALID_INPUT");

        ResponseEntity<String> badBody = postJson("/api/conversations/" + conv + "/messages", "{not-json");
        assertThat(badBody.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<String> badUuid = rest.getForEntity("/api/conversations/not-a-uuid", String.class);
        assertThat(badUuid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<String> unknownPath = rest.getForEntity("/api/nonexistent", String.class);
        assertThat(unknownPath.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(parse(unknownPath.getBody())).containsEntry("code", "NOT_FOUND");
    }

    @Test
    void serializesSameConversationAndParallelizesDifferentConversations() throws Exception {
        UUID conv = createId();
        AGENT.blockNextCall();

        // 第一次发送进入 Agent 并阻塞；同一 Conversation 的第二次发送在队列中等待
        CompletableFuture<ResponseEntity<String>> first = asyncSend(conv, "第一问");
        awaitUntil(() -> AGENT.requestCount() == 1);
        CompletableFuture<ResponseEntity<String>> second = asyncSend(conv, "第二问");
        Thread.sleep(400);
        assertThat(AGENT.requestCount()).isEqualTo(1);

        AGENT.releaseBlockedCalls();
        assertThat(first.get(10, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.get(10, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(AGENT.requestCount()).isEqualTo(2);
        Map<String, Object> detail = open(conv.toString());
        assertThat((List<?>) detail.get("activePath")).hasSize(4);

        // 不同 Conversation 不被全局锁串行：A 阻塞时 B 仍可完成
        UUID convA = createId();
        UUID convB = createId();
        int callsBefore = AGENT.requestCount();
        AGENT.blockNextCall();
        CompletableFuture<ResponseEntity<String>> futureA = asyncSend(convA, "A 的问题");
        awaitUntil(() -> AGENT.requestCount() == callsBefore + 1);
        ResponseEntity<String> futureB = sendSync(convB, "B 的问题");
        assertThat(futureB.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(AGENT.requestCount()).isEqualTo(callsBefore + 2);
        AGENT.releaseBlockedCalls();
        assertThat(futureA.get(10, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---------- HTTP 辅助 ----------

    private Map<String, Object> create() {
        ResponseEntity<String> response = rest.postForEntity("/api/conversations", null, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return parse(response.getBody());
    }

    private UUID createId() {
        return UUID.fromString(create().get("id").toString());
    }

    private Map<String, Object> open(String conversationId) {
        ResponseEntity<String> response = rest.getForEntity("/api/conversations/" + conversationId, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return parse(response.getBody());
    }

    private Map<String, Object> send(UUID conversationId, String text) {
        ResponseEntity<String> response = postJson("/api/conversations/" + conversationId + "/messages", Map.of("text", text));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return parse(response.getBody());
    }

    private ResponseEntity<String> sendSync(UUID conversationId, String text) {
        return postJson("/api/conversations/" + conversationId + "/messages", Map.of("text", text));
    }

    private CompletableFuture<ResponseEntity<String>> asyncSend(UUID conversationId, String text) {
        return CompletableFuture.supplyAsync(() -> sendSync(conversationId, text));
    }

    private ResponseEntity<String> postJson(String url, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    // 无 Spring AI retry 定制器的普通客户端，用于断言 503 类依赖失败响应
    private ResponseEntity<String> plainPost(String url, Object body) {
        RestTemplate plain = new RestTemplate(new SimpleClientHttpRequestFactory());
        // 5xx 是本次要断言的响应而非异常，禁用错误抛出
        plain.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            protected boolean hasError(HttpStatusCode statusCode) {
                return false;
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return plain.exchange(rest.getRootUri() + url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    private void assertSendResult(Map<String, Object> result, String text) {
        assertThat(result.get("conversation")).isNotNull();
        Map<String, Object> userEntry = payloadOf(entryOf(result, "userEntry"));
        assertThat(userEntry.get("text")).isEqualTo(text);
        Map<String, Object> assistantEntry = payloadOf(entryOf(result, "assistantEntry"));
        assertThat(assistantEntry.get("text")).isEqualTo("测试回答");
        assertThat(((Map<?, ?>) result.get("run")).get("status")).isEqualTo("SUCCEEDED");
        // 成功后的活动叶子指向 Assistant Entry
        assertThat(((Map<?, ?>) result.get("conversation")).get("activeLeafEntryId"))
                .isEqualTo(entryOf(result, "assistantEntry").get("id"));
    }

    // ---------- 测试数据辅助 ----------

    @SuppressWarnings("unchecked")
    private Map<String, Object> entryOf(Map<String, Object> parent, String key) {
        return (Map<String, Object>) parent.get(key);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payloadOf(Map<String, Object> entry) {
        return (Map<String, Object>) entry.get("payload");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> entryOf(Object entry) {
        return (Map<String, Object>) entry;
    }

    private Map<String, Object> lastEntry(Map<String, Object> detail) {
        List<?> path = (List<?>) detail.get("activePath");
        return entryOf(path.get(path.size() - 1));
    }

    private static List<AgentMessage> messagesOf(AgentRequest request) {
        return request.modelVisibleMessages();
    }

    private AgentRequest agentRequest(int index) {
        return AGENT.requests().get(index);
    }

    private static void awaitUntil(Supplier<Boolean> condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.get()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("等待条件超时");
    }

    private static Path fileOf(UUID conversationId) {
        return DATA_DIR.resolve("conversations").resolve(conversationId.toString()).resolve("events.jsonl");
    }

    // 模拟旧进程已落盘但数据库未提交的写入：按 JSONL v1 格式追加用户 Entry 行
    private static void appendRawUser(
            UUID conversationId, UUID entryId, long seq, String parentId, String text, UUID runId
    ) {
        try {
            String line = "{\"formatVersion\":1,\"conversationId\":\"" + conversationId
                    + "\",\"id\":\"" + entryId
                    + "\",\"seq\":" + seq
                    + ",\"parentId\":\"" + parentId + "\""
                    + ",\"type\":\"user_message\""
                    + ",\"createdAt\":\"" + Instant.parse("2026-08-01T00:00:00Z") + "\""
                    + ",\"payload\":{\"text\":\"" + text + "\",\"runId\":\"" + runId + "\"}}\n";
            Files.writeString(fileOf(conversationId), line, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    /** 确定性 Agent：记录每次请求；可阻塞、可失败、可恢复。 */
    static class DeterministicAgent implements AgentSession {

        private final List<AgentRequest> requests = new CopyOnWriteArrayList<>();
        private final List<CountDownLatch> gates = new CopyOnWriteArrayList<>();
        private volatile AgentExecutionException failure;

        @Override
        public AgentResult complete(AgentRequest request) {
            requests.add(request);
            int index = requests.size() - 1;
            if (index < gates.size()) {
                try {
                    if (!gates.get(index).await(15, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("测试门闩未释放");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("测试线程被中断", ex);
                }
            }
            AgentExecutionException currentFailure = failure;
            if (currentFailure != null) {
                throw currentFailure;
            }
            return new AgentResult("测试回答", "test-provider", "test-model", new AgentUsage(5L, 3L, 8L));
        }

        List<AgentRequest> requests() {
            return List.copyOf(requests);
        }

        int requestCount() {
            return requests.size();
        }

        /** 下一次 Agent 调用阻塞，直到 {@link #releaseBlockedCalls()}。 */
        void blockNextCall() {
            gates.add(new CountDownLatch(1));
        }

        void releaseBlockedCalls() {
            gates.forEach(CountDownLatch::countDown);
        }

        void failWith(AgentExecutionException ex) {
            failure = ex;
        }

        void clearFailure() {
            failure = null;
        }

        void reset() {
            requests.clear();
            gates.clear();
            failure = null;
        }
    }
}

package com.yuyu.salmonmind.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
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
import com.yuyu.salmonmind.agent.api.AgentCitation;
import com.yuyu.salmonmind.agent.api.AgentLocalCitation;
import com.yuyu.salmonmind.agent.api.AgentMessage;
import com.yuyu.salmonmind.agent.api.AgentRequest;
import com.yuyu.salmonmind.agent.api.AgentResult;
import com.yuyu.salmonmind.agent.api.AgentStreamListener;
import com.yuyu.salmonmind.agent.api.AgentStreamSession;
import com.yuyu.salmonmind.agent.api.AgentSummaryRequest;
import com.yuyu.salmonmind.agent.api.AgentSummaryResult;
import com.yuyu.salmonmind.agent.api.AgentSummaryService;
import com.yuyu.salmonmind.agent.api.AgentTitleRequest;
import com.yuyu.salmonmind.agent.api.AgentTitleResult;
import com.yuyu.salmonmind.agent.api.AgentTitleService;
import com.yuyu.salmonmind.agent.api.AgentUsage;
import com.yuyu.salmonmind.agent.api.AgentWebCitation;
import com.yuyu.salmonmind.conversation.api.ConversationService;
import com.yuyu.salmonmind.conversation.api.RunStreamListener;
import com.yuyu.salmonmind.conversation.api.RunStreamListener.RunStarted;
import com.yuyu.salmonmind.conversation.api.RunStreamListener.CompactionCompleted;
import com.yuyu.salmonmind.conversation.api.RunStreamListener.AssistantDelta;
import com.yuyu.salmonmind.conversation.api.RunStreamListener.AssistantCompleted;
import com.yuyu.salmonmind.conversation.api.RunStreamListener.TitleUpdated;
import com.yuyu.salmonmind.conversation.api.RunStreamListener.RunCompleted;
import com.yuyu.salmonmind.conversation.api.RunStreamListener.RunFailed;
import com.yuyu.salmonmind.conversation.domain.SummaryTemplate;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Conversation 模块 SSE 集成测试：RANDOM_PORT + Testcontainers PostgreSQL + 临时数据目录 +
 * 测试侧确定性 Agent 三接口（AgentStreamSession / AgentSummaryService / AgentTitleService，
 * @Primary 覆盖 agent::api seam）。
 *
 * <p>压缩预算按比例缩小：working-window=2000、output-reserve=500（阈值 1500）、
 * retained-tail-target=100；usage 锚点 1200 + 584 字符 ASCII 消息（估算 300 tokens）恰好
 * 达到阈值，583 字符不触发，对应 Spec 的 196,711/196,712 边界。
 *
 * <p>覆盖：SSE 事件顺序与终态互斥、durable run_started、delta 不落盘、标题 Entry 与
 * Active Path 关系、压缩边界与增量摘要、压缩后主调用失败重试、overflow 一次恢复、
 * 同 Conversation 串行与不同 Conversation 并行、前置 JSON 错误与流内错误边界。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:tc:postgresql:17.10-alpine:///salmon_mind",
                "spring.datasource.username=test",
                "spring.datasource.password=test",
                "salmon.compaction.working-window=2000",
                "salmon.compaction.output-reserve=500",
                "salmon.compaction.retained-tail-target=100",
                "salmon.compaction.summary-max-output-tokens=800",
                "salmon.compaction.system-prompt-tokens=100"
        }
)
class ConversationModuleIntegrationTest {

    private static final Path DATA_DIR = Path.of(
            System.getProperty("java.io.tmpdir"), "salmon-mind-conv-http-test-" + UUID.randomUUID());

    /** 测试用确定性 Agent 三接口单例；每个测试方法前重置状态。 */
    private static final DeterministicAgent AGENT = new DeterministicAgent();

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ConversationService conversationService;

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("salmon.conversation.data-dir", () -> DATA_DIR.toString());
    }

    @TestConfiguration
    static class TestAgentConfig {

        // 一个 @Primary Bean 同时覆盖三个 agent::api 接口（按具体类型注册，
        // 避免同一实例的三个接口 Bean 在按接口注入时互相成为 primary 候选）
        @Bean
        @Primary
        DeterministicAgent testAgent() {
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
    void sendsViaSseWithStrictEventOrderAndDurableRunStarted() throws Exception {
        UUID conv = createId();

        List<SseEvent> events = postSse("/api/conversations/" + conv + "/messages", Map.of("text", "你好"));

        // 事件顺序：run_started → delta×2 → assistant_completed → title_updated → run_completed
        assertThat(events).extracting(SseEvent::event)
                .containsExactly("run_started", "assistant_delta", "assistant_delta",
                        "assistant_completed", "title_updated", "run_completed");
        assertThat(events.stream().filter(e -> e.event.equals("run_completed") || e.event.equals("run_failed")))
                .hasSize(1);

        SseEvent started = events.get(0);
        Map<String, Object> startedData = started.data;
        assertThat(startedData.get("conversationId").toString()).isEqualTo(conv.toString());
        assertThat(startedData.get("isRetry")).isEqualTo(false);
        // run_started 携带持久化后的完整 User Entry 与 RUNNING Run（客户端在流结束后
        // 无法观察中途文件状态，durable 语义由「事件数据完整 + 终态后 JSONL 权威」共同覆盖）
        Map<String, Object> userEntry = (Map<String, Object>) startedData.get("userEntry");
        assertThat(userEntry.get("type")).isEqualTo("USER_MESSAGE");
        assertThat(userEntry.get("seq")).isEqualTo(1);
        assertThat(((Map<?, ?>) userEntry.get("payload")).get("text")).isEqualTo("你好");
        assertThat(((Map<?, ?>) startedData.get("run")).get("status")).isEqualTo("RUNNING");

        // delta 只用于临时显示：JSONL 只有 User + Assistant 两条 Entry（+Header）
        assertThat(events.stream().filter(e -> e.event.equals("assistant_delta")).map(e -> e.data.get("delta")))
                .containsExactly("测试", "回答");
        Map<String, Object> completed = events.stream()
                .filter(e -> e.event.equals("assistant_completed")).findFirst().orElseThrow().data;
        Map<String, Object> assistantEntry = (Map<String, Object>) completed.get("assistantEntry");
        assertThat(((Map<?, ?>) assistantEntry.get("payload")).get("text")).isEqualTo("测试回答");
        assertThat(Files.readAllLines(fileOf(conv))).hasSize(4);

        // 标题事件：Title Entry 追加、列表索引同步；Active Path 不被 Title 推进
        Map<String, Object> titleData = events.stream()
                .filter(e -> e.event.equals("title_updated")).findFirst().orElseThrow().data;
        assertThat(titleData.get("title")).isEqualTo("模型生成的标题");
        Map<String, Object> titleEntry = (Map<String, Object>) titleData.get("titleEntry");
        assertThat(titleEntry.get("type")).isEqualTo("TITLE");
        assertThat(titleEntry.get("parentId")).isEqualTo(assistantEntry.get("id"));

        Map<String, Object> detail = open(conv.toString());
        assertThat((List<?>) detail.get("activePath")).hasSize(2);
        assertThat(((Map<?, ?>) detail.get("conversation")).get("title")).isEqualTo("模型生成的标题");
        assertThat(detail.get("pendingRun")).isNull();
        String listJson = rest.getForEntity("/api/conversations", String.class).getBody();
        List<Map<String, Object>> list = parseList(listJson);
        assertThat(list.stream().filter(m -> m.get("id").toString().equals(conv.toString())).findFirst().orElseThrow())
                .containsEntry("title", "模型生成的标题");
    }

    @Test
    void titleFailureKeepsDefaultTitleAndSuccessfulRun() throws Exception {
        UUID conv = createId();
        AGENT.titleFailure = true;

        List<SseEvent> events = postSse("/api/conversations/" + conv + "/messages", Map.of("text", "你好"));

        // 标题失败不影响成功 Run：没有 title_updated，终态仍是 run_completed
        assertThat(events).extracting(SseEvent::event)
                .containsExactly("run_started", "assistant_delta", "assistant_delta",
                        "assistant_completed", "run_completed");
        Map<String, Object> detail = open(conv.toString());
        assertThat(((Map<?, ?>) detail.get("conversation")).get("title")).isEqualTo("新对话");
        // 没有 Title Entry 落盘
        assertThat(Files.readAllLines(fileOf(conv))).hasSize(3);
    }

    @Test
    void sendsTwoRoundsWithContextAndKeepsConversationsIsolated() throws Exception {
        UUID conv1 = createId();
        UUID conv2 = createId();

        postSse("/api/conversations/" + conv1 + "/messages", Map.of("text", "你好"));
        assertThat(agentRequest(0).threadId()).isEqualTo(conv1.toString());
        assertThat(agentRequest(0).expectedCheckpointLeafId()).isNull();
        assertThat(messagesOf(agentRequest(0))).containsExactly(new AgentMessage(AgentMessage.Role.USER, "你好"));

        postSse("/api/conversations/" + conv1 + "/messages", Map.of("text", "再讲一遍"));
        // 第二轮模型可见完整上下文，Checkpoint 标记等于第一轮回答叶子
        assertThat(messagesOf(agentRequest(1)))
                .containsExactly(
                        new AgentMessage(AgentMessage.Role.USER, "你好"),
                        new AgentMessage(AgentMessage.Role.ASSISTANT, "测试回答"),
                        new AgentMessage(AgentMessage.Role.USER, "再讲一遍"));
        Map<String, Object> detail = open(conv1.toString());
        Map<String, Object> firstAssistant = (Map<String, Object>) ((List<?>) detail.get("activePath")).get(1);
        assertThat(agentRequest(1).expectedCheckpointLeafId().toString())
                .isEqualTo(firstAssistant.get("id"));

        postSse("/api/conversations/" + conv2 + "/messages", Map.of("text", "另一个话题"));
        assertThat(agentRequest(2).threadId()).isEqualTo(conv2.toString());
        assertThat(messagesOf(agentRequest(2))).containsExactly(
                new AgentMessage(AgentMessage.Role.USER, "另一个话题"));

        // 数据库不允许遗留 RUNNING Run
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM conversation_runs WHERE status = 'RUNNING'", Integer.class)).isZero();
    }

    @Test
    void projectsPersistedCitationsAsHistoricalMetadataWithoutReusingThem() throws Exception {
        UUID conv = createId();
        UUID evidenceId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        AGENT.completeWithCitations(List.of(
                new AgentLocalCitation("L1", evidenceId, revisionId, "manual.md", "chapter-1"),
                new AgentWebCitation("W1", "BOCHA", "网页标题", "https://example.com/a",
                        "example.com", "2026-08-17", Instant.parse("2026-08-17T00:00:00Z"))));

        postSse("/api/conversations/" + conv + "/messages", Map.of("text", "请查资料"));
        Map<String, Object> first = open(conv.toString());
        Map<String, Object> firstAssistant = entryOf(((List<?>) first.get("activePath")).get(1));
        assertThat((List<?>) payloadOf(firstAssistant).get("citations")).hasSize(2);

        // 第二轮的测试 Agent 不返回 Citation；历史摘要只能进入模型输入，不能被复制到新 Entry。
        AGENT.completeWithCitations(List.of());
        postSse("/api/conversations/" + conv + "/messages", Map.of("text", "继续说明"));
        String historical = messagesOf(agentRequest(1)).get(1).text();
        assertThat(historical).contains(
                "[历史来源元数据：仅说明上一轮依据，不是当前 Run 可引用证据",
                "runId:", "[L1] source=LOCAL document=manual.md location=chapter-1",
                "[W1] source=WEB provider=BOCHA", "url=https://example.com/a",
                "如需核验必须重新检索");
        Map<String, Object> second = open(conv.toString());
        Map<String, Object> secondAssistant = entryOf(((List<?>) second.get("activePath")).get(3));
        assertThat((List<?>) payloadOf(secondAssistant).get("citations")).isEmpty();
    }

    @Test
    void failsWithRunFailedThenRetriesWithoutDuplicatingUserEntry() throws Exception {
        UUID conv = createId();
        AGENT.failWith(new AgentExecutionException(AgentErrorCode.CHAT_MODEL_FAILED, "模型调用失败"));

        List<SseEvent> failed = postSse("/api/conversations/" + conv + "/messages", Map.of("text", "会失败的问题"));
        assertThat(failed).extracting(SseEvent::event).containsExactly("run_started", "run_failed");
        Map<String, Object> failedData = failed.get(1).data;
        assertThat(failedData.get("errorCode")).isEqualTo("CHAT_MODEL_FAILED");
        assertThat(((Map<?, ?>) failedData.get("run")).get("status")).isEqualTo("FAILED");
        // 失败不写 Assistant：JSONL 只有 User
        assertThat(Files.readAllLines(fileOf(conv))).hasSize(2);
        String failedRunId = String.valueOf(((Map<?, ?>) failedData.get("run")).get("id"));

        // 刷新后：用户 Entry 保留，pendingRun 为 FAILED
        Map<String, Object> detail = open(conv.toString());
        assertThat((List<?>) detail.get("activePath")).hasSize(1);
        Map<String, Object> pending = (Map<String, Object>) detail.get("pendingRun");
        assertThat(pending).isNotNull();
        assertThat(pending.get("status")).isEqualTo("FAILED");
        assertThat(pending.get("errorCode")).isEqualTo("CHAT_MODEL_FAILED");

        // 待回答状态不能发送新消息（run_started 之前的 JSON 错误）
        ResponseEntity<String> blocked = postJson("/api/conversations/" + conv + "/messages", Map.of("text", "新消息"));
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(parse(blocked.getBody())).containsEntry("code", "CONVERSATION_AWAITING_RETRY");

        // 重试复用原用户 Entry，不重复追加用户消息
        AGENT.clearFailure();
        List<SseEvent> retried = postSse(
                "/api/conversations/" + conv + "/runs/" + failedRunId + "/retry", null);
        assertThat(retried.get(0).data.get("isRetry")).isEqualTo(true);
        assertThat(retried).extracting(SseEvent::event)
                .contains("run_started", "assistant_completed", "run_completed");
        assertThat(retried.stream().filter(e -> e.event.equals("run_failed"))).isEmpty();
        // 重试成功后可以生成标题（首次成功交互）
        assertThat(retried).extracting(SseEvent::event).contains("title_updated");
        // 两次调用的模型可见消息完全一致：重试没有引入重复的用户消息
        assertThat(messagesOf(agentRequest(0))).isEqualTo(messagesOf(agentRequest(1)));

        Map<String, Object> afterRetry = open(conv.toString());
        assertThat(((List<?>) afterRetry.get("activePath"))
                .stream()
                .map(this::entryOf)
                .filter(e -> "USER_MESSAGE".equals(e.get("type")))
                .filter(e -> "会失败的问题".equals(payloadOf(e).get("text")))
                .count()).isEqualTo(1);
    }

    @Test
    void compactsAtThresholdBoundaryWithCurrentUserAndReestimates() throws Exception {
        UUID conv = createId();
        postSse("/api/conversations/" + conv + "/messages", Map.of("text", "你好"));

        // 583 字符：锚点 1200 + (583/2 + 8) = 1499，不压缩
        List<SseEvent> below = postSse("/api/conversations/" + conv + "/messages",
                Map.of("text", "x".repeat(583)));
        assertThat(below).extracting(SseEvent::event).doesNotContain("compaction_completed");

        // 584 字符：锚点 1200 + (584/2 + 8) = 1500，触发压缩
        List<SseEvent> at = postSse("/api/conversations/" + conv + "/messages", Map.of("text", "x".repeat(584)));
        assertThat(at).extracting(SseEvent::event)
                .contains("run_started", "compaction_completed", "assistant_delta", "assistant_completed", "run_completed");
        SseEvent compactionEvent = at.stream()
                .filter(e -> e.event.equals("compaction_completed")).findFirst().orElseThrow();
        Map<String, Object> compactionEntry = (Map<String, Object>) compactionEvent.data.get("compactionEntry");
        Map<String, Object> payload = payloadOf(compactionEntry);
        assertThat(((Number) payload.get("tokensBefore")).longValue()).isEqualTo(1500L);
        // 压缩后投影：摘要前缀消息 + Retained Tail（本次 User 原样保留）
        List<AgentMessage> projection = messagesOf(agentRequest(2));
        assertThat(projection).hasSize(2);
        assertThat(projection.get(0).text()).startsWith("以下为此前对话的结构化摘要");
        assertThat(projection.get(0).text()).contains("## 用户目标");
        assertThat(projection.get(1)).isEqualTo(new AgentMessage(AgentMessage.Role.USER, "x".repeat(584)));
        // 重计量低于阈值，Run 继续成功
        assertThat(at.stream().filter(e -> e.event.equals("run_failed"))).isEmpty();

        // PostgreSQL 压缩三元组已更新；Active Path 含 Compaction；Usage 锚点在压缩后失效
        Map<String, Object> detail = open(conv.toString());
        List<?> path = (List<?>) detail.get("activePath");
        // u1/a1/u2/a2/u3/c1/a3：Compaction 是路径上第 6 条
        assertThat(path).hasSize(7);
        assertThat(((Map<?, ?>) detail.get("conversation")).get("latestCompactionEntryId"))
                .isEqualTo(compactionEntry.get("id"));
        Map<String, Object> compacted = (Map<String, Object>) path.get(5);
        assertThat(compacted.get("type")).isEqualTo("COMPACTION");
        assertThat(((Map<?, ?>) compacted.get("payload")).get("coveredThroughEntryId")).isNotNull();
    }

    @Test
    void secondCompactionUsesPreviousSummaryIncrementally() throws Exception {
        UUID conv = createId();
        String u1 = "第一轮问题";
        postSse("/api/conversations/" + conv + "/messages", Map.of("text", u1));

        // 第一次压缩：候选区为全部消息，首次摘要不含 previousSummary
        postSse("/api/conversations/" + conv + "/messages", Map.of("text", "x".repeat(584)));
        assertThat(AGENT.summaryRequests).hasSize(1);
        assertThat(summaryInputOf(0)).doesNotContain("输入痕迹");

        // 第二次压缩：候选区 = 旧 retainedTail + 其后新消息；增量摘要包含 previousSummary
        List<SseEvent> second = postSse("/api/conversations/" + conv + "/messages", Map.of("text", "x".repeat(584)));
        assertThat(second).extracting(SseEvent::event).contains("compaction_completed");
        assertThat(AGENT.summaryRequests).hasSize(2);
        assertThat(summaryInputOf(1)).contains("输入痕迹");
        assertThat(summaryInputOf(1)).contains("用户：" + "x".repeat(584));
        // 已进入旧摘要且未变化的原始历史不重复发送
        assertThat(summaryInputOf(1)).doesNotContain("用户：" + u1);
    }

    @Test
    void compactionFailureFailsRunAndRetryReusesCompaction() throws Exception {
        UUID conv = createId();
        postSse("/api/conversations/" + conv + "/messages", Map.of("text", "你好"));

        AGENT.summaryFailure = new AgentExecutionException(AgentErrorCode.CHAT_MODEL_FAILED, "摘要调用失败");
        List<SseEvent> failed = postSse("/api/conversations/" + conv + "/messages",
                Map.of("text", "x".repeat(584)));
        assertThat(failed).extracting(SseEvent::event).containsExactly("run_started", "run_failed");
        assertThat(failed.get(1).data.get("errorCode")).isEqualTo("COMPACTION_FAILED");
        String failedRunId = String.valueOf(((Map<?, ?>) failed.get(1).data.get("run")).get("id"));

        Map<String, Object> detail = open(conv.toString());
        Map<String, Object> pending = (Map<String, Object>) detail.get("pendingRun");
        assertThat(pending.get("status")).isEqualTo("FAILED");
        assertThat(pending.get("errorCode")).isEqualTo("COMPACTION_FAILED");

        // 重试成功：Compaction 落盘、用户不重复、主调用从新投影重建
        AGENT.summaryFailure = null;
        List<SseEvent> retried = postSse("/api/conversations/" + conv + "/runs/" + failedRunId + "/retry", null);
        assertThat(retried).extracting(SseEvent::event).contains("compaction_completed", "assistant_completed");
        assertThat(retried).extracting(SseEvent::event).doesNotContain("run_failed");
        Map<String, Object> after = open(conv.toString());
        List<?> path = (List<?>) after.get("activePath");
        // u1/a1/u2/c1/a2：Compaction 是路径上第 4 条
        assertThat(path).hasSize(5);
        assertThat(path.stream()
                .map(this::entryOf)
                .filter(e -> "USER_MESSAGE".equals(e.get("type")))
                .filter(e -> "x".repeat(584).equals(payloadOf(e).get("text")))
                .count()).isEqualTo(1);
        // 重试路径上第一个上下文节点是 Compaction：期望 Checkpoint 叶子 = Compaction（强制重建）
        Map<String, Object> compactionNode = (Map<String, Object>) path.get(3);
        assertThat(compactionNode.get("type")).isEqualTo("COMPACTION");
        assertThat(agentRequest(1).expectedCheckpointLeafId())
                .isEqualTo(UUID.fromString(compactionNode.get("id").toString()));
        assertThat(messagesOf(agentRequest(1)).get(0).text()).startsWith("以下为此前对话的结构化摘要");
    }

    @Test
    void mainCallFailsAfterCompactionThenRetryReusesCompactionLeaf() throws Exception {
        UUID conv = createId();
        postSse("/api/conversations/" + conv + "/messages", Map.of("text", "你好"));

        // 压缩成功但主调用失败：Compaction 已落盘，失败 Run 的活动叶子是 Compaction
        AGENT.failMainAfterCompaction = true;
        List<SseEvent> failed = postSse("/api/conversations/" + conv + "/messages",
                Map.of("text", "x".repeat(584)));
        assertThat(failed).extracting(SseEvent::event)
                .contains("compaction_completed", "run_failed");
        String failedRunId = String.valueOf(((Map<?, ?>) failed.get(failed.size() - 1).data.get("run")).get("id"));

        // pendingRun 仍从路径定位触发 User 的最新未成功 Run
        Map<String, Object> detail = open(conv.toString());
        Map<String, Object> pending = (Map<String, Object>) detail.get("pendingRun");
        assertThat(pending).isNotNull();
        assertThat(pending.get("status")).isEqualTo("FAILED");

        // 重试直接复用该 Compaction：活动叶子是 Compaction 也可重试，不重复用户消息
        AGENT.failMainAfterCompaction = false;
        List<SseEvent> retried = postSse("/api/conversations/" + conv + "/runs/" + failedRunId + "/retry", null);
        assertThat(retried).extracting(SseEvent::event).contains("assistant_completed", "run_completed");
        assertThat(retried).extracting(SseEvent::event).doesNotContain("run_failed");
        Map<String, Object> after = open(conv.toString());
        assertThat(((List<?>) after.get("activePath"))
                .stream()
                .map(this::entryOf)
                .filter(e -> "USER_MESSAGE".equals(e.get("type")))
                .filter(e -> "x".repeat(584).equals(payloadOf(e).get("text")))
                .count()).isEqualTo(1);
    }

    @Test
    void contextOverflowCompactsOnceAndRetriesThenFailsOnSecondOverflow() throws Exception {
        UUID conv = createId();
        postSse("/api/conversations/" + conv + "/messages", Map.of("text", "你好"));

        // 无 delta 时明确上下文溢出：强制压缩一次后自动重试成功
        AGENT.overflowTimes = 1;
        List<SseEvent> recovered = postSse("/api/conversations/" + conv + "/messages",
                Map.of("text", "x".repeat(300)));
        assertThat(recovered).extracting(SseEvent::event)
                .contains("compaction_completed", "assistant_delta", "assistant_completed", "run_completed");
        // compaction_completed 必须出现在任何 delta 之前
        assertThat(recovered.stream().map(SseEvent::event).toList().indexOf("compaction_completed"))
                .isLessThan(recovered.stream().map(SseEvent::event).toList().indexOf("assistant_delta"));

        // 第二次也溢出：已用尽唯一压缩机会，直接 CONTEXT_LIMIT_REACHED 失败
        AGENT.overflowTimes = 2;
        List<SseEvent> failed = postSse("/api/conversations/" + conv + "/messages",
                Map.of("text", "x".repeat(300)));
        assertThat(failed).extracting(SseEvent::event).contains("compaction_completed", "run_failed");
        assertThat(failed.get(failed.size() - 1).data.get("errorCode")).isEqualTo("CONTEXT_LIMIT_REACHED");
        // 失败不写 Assistant；Compaction 作为失败 Run 的活动叶子
        Map<String, Object> detail = open(conv.toString());
        List<?> path = (List<?>) detail.get("activePath");
        assertThat(path.get(path.size() - 1)).extracting("type").isEqualTo("COMPACTION");
        assertThat(detail.get("pendingRun")).isNotNull();
    }

    @Test
    void failsWithoutCompactingWhenDeltaAlreadyEmitted() throws Exception {
        UUID conv = createId();
        AGENT.failAfterDelta = true;

        List<SseEvent> failed = postSse("/api/conversations/" + conv + "/messages", Map.of("text", "你好"));
        assertThat(failed).extracting(SseEvent::event)
                .containsExactly("run_started", "assistant_delta", "run_failed");
        assertThat(failed.get(2).data.get("errorCode")).isEqualTo("CHAT_MODEL_FAILED");
        // 已输出 delta 后失败：不自动压缩，不写 Assistant
        assertThat(Files.readAllLines(fileOf(conv))).hasSize(2);
    }

    @Test
    void recoversStaleRunningRunToInterruptedOnFirstRead() throws Exception {
        UUID conv = createId();
        List<SseEvent> firstRound = postSse("/api/conversations/" + conv + "/messages", Map.of("text", "第一轮"));
        Map<String, Object> firstAssistant = (Map<String, Object>) firstRound.stream()
                .filter(e -> e.event.equals("assistant_completed")).findFirst().orElseThrow()
                .data.get("assistantEntry");

        // 模拟旧进程在 startRun 提交后崩溃：JSONL 已有用户 Entry，数据库留下 RUNNING Run。
        // seq=4：首轮成功后 Title Entry 已占用 seq 3（Stage 2 起成功 Run 生成标题）
        UUID staleRunId = UUID.randomUUID();
        UUID userEntryId = UUID.randomUUID();
        appendRawUser(conv, userEntryId, 4, firstAssistant.get("id").toString(), "崩溃后的问题", staleRunId);
        jdbcTemplate.update(
                "INSERT INTO conversation_runs (id, conversation_id, trigger_entry_id, status, error_code, started_at, ended_at)"
                        + " VALUES (?, ?, ?, 'RUNNING', NULL, CURRENT_TIMESTAMP, NULL)",
                staleRunId, conv, userEntryId);
        jdbcTemplate.update(
                "UPDATE conversations SET active_leaf_entry_id = ?, last_confirmed_seq = 4 WHERE id = ?",
                userEntryId, conv);

        // 首次读取：遗留 RUNNING 恢复为 INTERRUPTED，可识别并重试
        Map<String, Object> detail = open(conv.toString());
        Map<String, Object> pending = (Map<String, Object>) detail.get("pendingRun");
        assertThat(pending).isNotNull();
        assertThat(pending.get("status")).isEqualTo("INTERRUPTED");

        // 重试成功，用户 Entry 不重复
        List<SseEvent> retried = postSse(
                "/api/conversations/" + conv + "/runs/" + pending.get("id") + "/retry", null);
        assertThat(retried).extracting(SseEvent::event).contains("run_completed");
        Map<String, Object> afterRetry = open(conv.toString());
        assertThat(((List<?>) afterRetry.get("activePath"))
                .stream()
                .map(this::entryOf)
                .filter(e -> "USER_MESSAGE".equals(e.get("type")))
                .filter(e -> "崩溃后的问题".equals(payloadOf(e).get("text")))
                .count()).isEqualTo(1);
    }

    // ---------- S1-02：成功持久化与 SSE 传输分离 ----------

    /**
     * 成功提交点之后（assistant_completed 写出时）Listener 抛异常：传输中断只结束连接，
     * Run 保持 SUCCEEDED，JSONL 仍只有一个完整 Assistant，open 不返回 pending retry。
     */
    @Test
    void transportFailureInAssistantCompletedKeepsSucceededRun() throws IOException {
        UUID conv = conversationService.create().id();
        TransportFailureListener listener = new TransportFailureListener(
                jdbcTemplate, conv, true, false);

        conversationService.send(conv, "你好", listener);

        // 回调确实抛出了传输异常，但 send 正常返回：业务状态不被降级
        assertThat(listener.threwInCallback).isTrue();
        assertThat(listener.succeededVisibleInCallback).isTrue();
        assertThat(listener.leafAdvancedInCallback).isTrue();
        assertThat(listener.failedEventSeen).isFalse();
        // 事务提交后才发出成功事件：回调时数据库已能观察到 SUCCEEDED Run 与推进后的叶子

        Map<String, Object> detail = open(conv.toString());
        assertThat(detail.get("pendingRun")).isNull();
        List<?> path = (List<?>) detail.get("activePath");
        assertThat(path).hasSize(2);
        assertThat(path.get(1)).extracting("type").isEqualTo("ASSISTANT_MESSAGE");
        assertThat(Files.readAllLines(fileOf(conv))).hasSize(4);
    }

    /**
     * run_completed 写出失败同样保持权威状态：失败更新不会被调用，
     * 数据库 Run 仍是 SUCCEEDED 且叶子指向 Assistant。
     */
    @Test
    void transportFailureInRunCompletedKeepsSucceededRun() {
        UUID conv = conversationService.create().id();
        TransportFailureListener listener = new TransportFailureListener(
                jdbcTemplate, conv, false, true);

        conversationService.send(conv, "你好", listener);

        assertThat(listener.threwInCallback).isTrue();
        assertThat(listener.succeededVisibleInCallback).isTrue();
        assertThat(listener.leafAdvancedInCallback).isTrue();
        assertThat(listener.failedEventSeen).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM conversation_runs WHERE conversation_id = ?",
                String.class, conv)).isEqualTo("SUCCEEDED");

        Map<String, Object> detail = open(conv.toString());
        assertThat(detail.get("pendingRun")).isNull();
        assertThat((List<?>) detail.get("activePath")).hasSize(2);
    }

    /**
     * 模拟成功事件写出失败的监听器：在指定回调中查询数据库（证明 SSE 发生在事务提交后），
     * 然后抛 RuntimeException 模拟传输中断；不实现任何断言逻辑，只记录事实。
     */
    private static final class TransportFailureListener implements RunStreamListener {

        private final JdbcTemplate jdbc;
        private final UUID conversationId;
        private final boolean failAtAssistantCompleted;
        private final boolean failAtRunCompleted;

        volatile boolean threwInCallback;
        volatile boolean succeededVisibleInCallback;
        volatile boolean leafAdvancedInCallback;
        volatile boolean failedEventSeen;

        TransportFailureListener(
                JdbcTemplate jdbc, UUID conversationId,
                boolean failAtAssistantCompleted, boolean failAtRunCompleted
        ) {
            this.jdbc = jdbc;
            this.conversationId = conversationId;
            this.failAtAssistantCompleted = failAtAssistantCompleted;
            this.failAtRunCompleted = failAtRunCompleted;
        }

        @Override
        public void onRunStarted(RunStarted event) {
        }

        @Override
        public void onCompactionCompleted(CompactionCompleted event) {
        }

        @Override
        public void onAssistantDelta(AssistantDelta event) {
        }

        @Override
        public void onAssistantCompleted(AssistantCompleted event) {
            probeDb(event.assistantEntry().id());
            if (failAtAssistantCompleted) {
                threwInCallback = true;
                throw new RuntimeException("模拟 assistant_completed 写出失败");
            }
        }

        @Override
        public void onTitleUpdated(TitleUpdated event) {
        }

        @Override
        public void onRunCompleted(RunCompleted event) {
            probeDb(event.conversation().activeLeafEntryId());
            if (failAtRunCompleted) {
                threwInCallback = true;
                throw new RuntimeException("模拟 run_completed 写出失败");
            }
        }

        @Override
        public void onRunFailed(RunFailed event) {
            failedEventSeen = true;
        }

        // 成功事件回调时数据库必须已可见 SUCCEEDED Run 与推进后的叶子，证明 SSE 不在事务内
        private void probeDb(UUID expectedLeafId) {
            Integer succeeded = jdbc.queryForObject(
                    "SELECT count(*) FROM conversation_runs"
                            + " WHERE conversation_id = ? AND status = 'SUCCEEDED'",
                    Integer.class, conversationId);
            succeededVisibleInCallback = succeeded != null && succeeded > 0;
            UUID leaf = jdbc.queryForObject(
                    "SELECT active_leaf_entry_id FROM conversations WHERE id = ?",
                    UUID.class, conversationId);
            leafAdvancedInCallback = expectedLeafId.equals(leaf);
        }
    }

    // ---------- 错误与边界 ----------

    @Test
    void mapsPreStreamErrorsToJsonAndKeepsTerminalExclusive() {
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

        // 流内失败以 run_failed 结束：终态后不再有业务事件
        AGENT.failWith(new AgentExecutionException(AgentErrorCode.CHAT_MODEL_FAILED, "模型调用失败"));
        List<SseEvent> failed = postSse("/api/conversations/" + conv + "/messages", Map.of("text", "正常文本"));
        assertThat(failed.get(failed.size() - 1).event).isEqualTo("run_failed");
    }

    @Test
    void serializesSameConversationAndParallelizesDifferentConversations() throws Exception {
        UUID conv = createId();
        AGENT.blockNextCall();

        // 第一次发送进入 Agent 并阻塞；同一 Conversation 的第二次发送在队列中等待
        CompletableFuture<List<SseEvent>> first = asyncSend(conv, "第一问");
        awaitUntil(() -> AGENT.requestCount() == 1);
        CompletableFuture<List<SseEvent>> second = asyncSend(conv, "第二问");
        Thread.sleep(400);
        assertThat(AGENT.requestCount()).isEqualTo(1);

        AGENT.releaseBlockedCalls();
        assertThat(first.get(10, TimeUnit.SECONDS)).extracting(SseEvent::event).contains("run_completed");
        assertThat(second.get(10, TimeUnit.SECONDS)).extracting(SseEvent::event).contains("run_completed");
        assertThat(AGENT.requestCount()).isEqualTo(2);
        Map<String, Object> detail = open(conv.toString());
        assertThat((List<?>) detail.get("activePath")).hasSize(4);

        // 不同 Conversation 不被全局锁串行：A 阻塞时 B 仍可完成
        UUID convA = createId();
        UUID convB = createId();
        int callsBefore = AGENT.requestCount();
        AGENT.blockNextCall();
        CompletableFuture<List<SseEvent>> futureA = asyncSend(convA, "A 的问题");
        awaitUntil(() -> AGENT.requestCount() == callsBefore + 1);
        List<SseEvent> futureB = postSse("/api/conversations/" + convB + "/messages", Map.of("text", "B 的问题"));
        assertThat(futureB).extracting(SseEvent::event).contains("run_completed");
        assertThat(AGENT.requestCount()).isEqualTo(callsBefore + 2);
        AGENT.releaseBlockedCalls();
        assertThat(futureA.get(10, TimeUnit.SECONDS)).extracting(SseEvent::event).contains("run_completed");
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

    /** POST 并解析完整 SSE 响应（请求线程阻塞直到流结束）。 */
    private List<SseEvent> postSse(String url, Object body) {
        ResponseEntity<String> response;
        if (body == null) {
            response = rest.exchange(url, HttpMethod.POST, null, String.class);
        } else {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            response = rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        }
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return parseSse(response.getBody());
    }

    private CompletableFuture<List<SseEvent>> asyncSend(UUID conversationId, String text) {
        return CompletableFuture.supplyAsync(() -> postSse(
                "/api/conversations/" + conversationId + "/messages", Map.of("text", text)));
    }

    private ResponseEntity<String> postJson(String url, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    // ---------- SSE 解析辅助 ----------

    record SseEvent(String event, Map<String, Object> data) {
    }

    private List<SseEvent> parseSse(String body) {
        List<SseEvent> events = new ArrayList<>();
        for (String block : body.split("\n\n")) {
            if (block.isBlank()) {
                continue;
            }
            String event = null;
            String data = null;
            for (String line : block.split("\n")) {
                if (line.startsWith("event: ")) {
                    event = line.substring("event: ".length());
                } else if (line.startsWith("data: ")) {
                    data = line.substring("data: ".length());
                }
            }
            assertThat(event).as("SSE 帧缺少 event: %s", block).isNotNull();
            assertThat(data).as("SSE 帧缺少 data: %s", block).isNotNull();
            events.add(new SseEvent(event, parse(data)));
        }
        return events;
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

    // ---------- 测试数据辅助 ----------

    @SuppressWarnings("unchecked")
    private Map<String, Object> entryOf(Object entry) {
        return (Map<String, Object>) entry;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payloadOf(Map<String, Object> entry) {
        return (Map<String, Object>) entry.get("payload");
    }

    private static List<AgentMessage> messagesOf(AgentRequest request) {
        return request.modelVisibleMessages();
    }

    private AgentRequest agentRequest(int index) {
        return AGENT.requests().get(index);
    }

    private String summaryInputOf(int index) {
        return AGENT.summaryRequests().get(index).messages().get(0).text();
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

    /**
     * 确定性 Agent 三接口：主回答流式（记录请求、可阻塞、可失败、可溢出重试）、
     * 结构化摘要（合法七标题 + 输入痕迹）、标题生成（可注入失败）。
     */
    static class DeterministicAgent implements AgentStreamSession, AgentSummaryService, AgentTitleService {

        private final List<AgentRequest> requests = new CopyOnWriteArrayList<>();
        private final List<AgentSummaryRequest> summaryRequests = new CopyOnWriteArrayList<>();
        private final List<CountDownLatch> gates = new CopyOnWriteArrayList<>();
        private volatile AgentExecutionException failure;
        private volatile AgentExecutionException summaryFailure;
        private volatile boolean titleFailure;
        /** 主调用连续抛出 CONTEXT_OVERFLOW 的次数；每次消耗 1。 */
        private volatile int overflowTimes;
        /** 压缩成功后主调用失败（验证 Compaction 叶子可重试）。 */
        private volatile boolean failMainAfterCompaction;
        /** 输出第一个 delta 后失败（验证已输出 delta 不压缩重试）。 */
        private volatile boolean failAfterDelta;
        /** 主调用固定用量：totalTokens 作为压缩检测的 usage 锚点。 */
        private volatile AgentUsage usage = new AgentUsage(1000L, 200L, 1200L);
        private volatile List<AgentCitation> citations = List.of();

        @Override
        public void stream(AgentRequest request, AgentStreamListener listener) {
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
                listener.onError(currentFailure);
                return;
            }
            if (overflowTimes > 0) {
                overflowTimes--;
                listener.onError(new AgentExecutionException(
                        AgentErrorCode.CONTEXT_OVERFLOW, "context length exceeded"));
                return;
            }
            if (failAfterDelta) {
                listener.onDelta("测试");
                listener.onError(new AgentExecutionException(
                        AgentErrorCode.CHAT_MODEL_FAILED, "delta 之后失败"));
                return;
            }
            if (failMainAfterCompaction) {
                listener.onError(new AgentExecutionException(
                        AgentErrorCode.CHAT_MODEL_FAILED, "压缩后主调用失败"));
                return;
            }
            listener.onDelta("测试");
            listener.onDelta("回答");
            listener.onComplete(new AgentResult("测试回答", "test-provider", "test-model", usage, citations));
        }

        @Override
        public AgentSummaryResult summarize(AgentSummaryRequest request) {
            summaryRequests.add(request);
            AgentExecutionException currentFailure = summaryFailure;
            if (currentFailure != null) {
                throw currentFailure;
            }
            // 合法结构：固定七标题 + 截断的输入痕迹（便于断言增量摘要包含 previousSummary，
            // 又不让摘要文本随输入膨胀导致压缩后重计量超限——真实模型摘要会收敛）
            StringBuilder summary = new StringBuilder();
            for (String heading : SummaryTemplate.FIXED_HEADINGS) {
                summary.append("## ").append(heading).append("\n内容\n");
            }
            String input = request.messages().get(0).text();
            String trace = input.length() <= 80 ? input : input.substring(0, 80);
            summary.append("<!-- 输入痕迹: ").append(trace).append(" -->");
            return new AgentSummaryResult(summary.toString(), new AgentUsage(100L, 50L, 150L));
        }

        @Override
        public AgentTitleResult generateTitle(AgentTitleRequest request) {
            if (titleFailure) {
                return new AgentTitleResult(null, "test-provider", "test-model");
            }
            return new AgentTitleResult("模型生成的标题", "test-provider", "test-model");
        }

        List<AgentRequest> requests() {
            return List.copyOf(requests);
        }

        List<AgentSummaryRequest> summaryRequests() {
            return List.copyOf(summaryRequests);
        }

        int requestCount() {
            return requests.size();
        }

        /** 下一次主调用阻塞，直到 {@link #releaseBlockedCalls()}。 */
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

        void completeWithCitations(List<AgentCitation> nextCitations) {
            citations = nextCitations == null ? List.of() : List.copyOf(nextCitations);
        }

        void reset() {
            requests.clear();
            summaryRequests.clear();
            gates.clear();
            failure = null;
            summaryFailure = null;
            titleFailure = false;
            overflowTimes = 0;
            failMainAfterCompaction = false;
            failAfterDelta = false;
            usage = new AgentUsage(1000L, 200L, 1200L);
            citations = List.of();
        }
    }
}

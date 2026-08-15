package com.yuyu.salmonmind.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.yuyu.salmonmind.conversation.api.AssistantMessagePayload;
import com.yuyu.salmonmind.conversation.api.CompactionPayload;
import com.yuyu.salmonmind.conversation.api.ConversationDetail;
import com.yuyu.salmonmind.conversation.api.ConversationService;
import com.yuyu.salmonmind.conversation.api.ConversationSummary;
import com.yuyu.salmonmind.conversation.api.Entry;
import com.yuyu.salmonmind.conversation.api.Run;
import com.yuyu.salmonmind.conversation.api.TitlePayload;
import com.yuyu.salmonmind.conversation.api.TokenUsage;
import com.yuyu.salmonmind.conversation.api.UserMessagePayload;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Conversation 持久化集成测试：临时数据目录 + Testcontainers PostgreSQL，
 * 通过 conversation::api 验证创建、列表、重启读取、JSONL 领先数据库后的恢复修复
 * 与孤儿文件不展示。制造数据库落后状态使用测试侧 SQL/JdbcTemplate，
 * 不导入 Entity 或 Mapper；测试间数据按随机 Conversation ID 隔离，不依赖回滚。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:tc:postgresql:17.10-alpine:///salmon_mind",
                "spring.datasource.username=test",
                "spring.datasource.password=test"
        }
)
class ConversationPersistenceIntegrationTest {

    private static final Path DATA_DIR = Path.of(
            System.getProperty("java.io.tmpdir"), "salmon-mind-conv-test-" + UUID.randomUUID());

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("salmon.conversation.data-dir", () -> DATA_DIR.toString());
    }

    @Autowired
    private ConversationService service;
    @Autowired
    private JdbcTemplate jdbcTemplate;

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

    @Test
    void createsConversationWithDatabaseRowAndJsonlHeader() throws Exception {
        ConversationSummary summary = service.create();

        String title = jdbcTemplate.queryForObject(
                "SELECT title FROM conversations WHERE id = ?", String.class, summary.id());
        assertThat(title).isEqualTo("新对话");
        Integer formatVersion = jdbcTemplate.queryForObject(
                "SELECT history_format_version FROM conversations WHERE id = ?", Integer.class, summary.id());
        assertThat(formatVersion).isEqualTo(1);
        UUID leaf = jdbcTemplate.queryForObject(
                "SELECT active_leaf_entry_id FROM conversations WHERE id = ?", UUID.class, summary.id());
        assertThat(leaf).isNull();
        Long lastSeq = jdbcTemplate.queryForObject(
                "SELECT last_confirmed_seq FROM conversations WHERE id = ?", Long.class, summary.id());
        assertThat(lastSeq).isZero();

        assertThat(fileOf(summary.id())).exists();
        String firstLine = Files.readAllLines(fileOf(summary.id())).get(0);
        assertThat(firstLine).contains("\"conversationId\":\"" + summary.id() + "\"");
    }

    @Test
    void listsNewestFirstAndHidesOrphanDirectories() throws Exception {
        ConversationSummary older = service.create();
        ConversationSummary newer = service.create();

        List<ConversationSummary> all = service.list();
        // TIMESTAMPTZ 微秒截断会导致 record 整体不相等，按 id 比较顺序
        List<UUID> ids = all.stream().map(ConversationSummary::id).toList();
        assertThat(ids).contains(newer.id(), older.id());
        assertThat(ids.indexOf(newer.id())).isLessThan(ids.indexOf(older.id()));

        // 孤儿文件：有 JSONL 目录但无数据库行，不得展示
        UUID orphanId = UUID.randomUUID();
        Files.createDirectories(fileOf(orphanId).getParent());
        Files.writeString(fileOf(orphanId),
                "{\"type\":\"conversation\",\"formatVersion\":1,\"conversationId\":\"" + orphanId
                        + "\",\"createdAt\":\"2026-08-01T00:00:00Z\"}\n");
        assertThat(service.list())
                .extracting(ConversationSummary::id)
                .doesNotContain(orphanId);
    }

    @Test
    void recoversWhenJsonlIsAheadAfterRestart() {
        ConversationSummary summary = service.create();
        UUID conversationId = summary.id();

        // 模拟旧进程已写入 JSONL 但数据库事务未完成
        UUID runId = UUID.randomUUID();
        Entry u1 = userEntry(conversationId, 1, null, "你好，世界", runId);
        Entry a1 = assistantEntry(conversationId, 2, u1.id(), "回答你", runId);
        appendRaw(conversationId, u1);
        appendRaw(conversationId, a1);

        // 重启：同数据目录重新打开，reconcile 以 JSONL 为权威推进数据库索引
        ConversationDetail detail = service.open(conversationId);

        assertThat(detail.activePath()).containsExactly(u1, a1);
        assertThat(detail.pendingRun()).isNull();
        // Stage 2 起标题只来自模型 Title Entry，不再从首条用户消息截断
        assertThat(detail.conversation().title()).isEqualTo("新对话");
        assertThat(detail.conversation().activeLeafEntryId()).isEqualTo(a1.id());
        assertThat(detail.conversation().lastConfirmedSeq()).isEqualTo(2);

        // 数据库索引已推进：活动叶子、确认序号与 Run 终态
        UUID leaf = jdbcTemplate.queryForObject(
                "SELECT active_leaf_entry_id FROM conversations WHERE id = ?", UUID.class, conversationId);
        assertThat(leaf).isEqualTo(a1.id());
        Long lastSeq = jdbcTemplate.queryForObject(
                "SELECT last_confirmed_seq FROM conversations WHERE id = ?", Long.class, conversationId);
        assertThat(lastSeq).isEqualTo(2L);
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM conversation_runs WHERE id = ?", String.class, runId);
        assertThat(status).isEqualTo(Run.RunStatus.SUCCEEDED.name());
        UUID trigger = jdbcTemplate.queryForObject(
                "SELECT trigger_entry_id FROM conversation_runs WHERE id = ?", UUID.class, runId);
        assertThat(trigger).isEqualTo(u1.id());
        Instant endedAt = jdbcTemplate.queryForObject(
                "SELECT ended_at FROM conversation_runs WHERE id = ?", Instant.class, runId);
        assertThat(endedAt).isNotNull();
    }

    @Test
    void rebuildsInterruptedRunForUnansweredUserEntry() {
        ConversationSummary summary = service.create();
        UUID conversationId = summary.id();

        UUID runId = UUID.randomUUID();
        Entry u1 = userEntry(conversationId, 1, null, "待回答的问题", runId);
        appendRaw(conversationId, u1);

        ConversationDetail detail = service.open(conversationId);

        // 待回答用户 Entry 缺少 Run 行：重建为 INTERRUPTED，活动叶子仍是用户 Entry
        assertThat(detail.activePath()).containsExactly(u1);
        assertThat(detail.pendingRun()).isNotNull();
        assertThat(detail.pendingRun().status()).isEqualTo(Run.RunStatus.INTERRUPTED);
        assertThat(detail.pendingRun().triggerEntryId()).isEqualTo(u1.id());
        assertThat(detail.conversation().activeLeafEntryId()).isEqualTo(u1.id());
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM conversation_runs WHERE id = ?", String.class, runId);
        assertThat(status).isEqualTo(Run.RunStatus.INTERRUPTED.name());
    }

    @Test
    void repairsCompactionIndexWhenDatabaseIsWrong() {
        ConversationSummary summary = service.create();
        UUID conversationId = summary.id();

        Entry u1 = userEntry(conversationId, 1, null, "第一问", UUID.randomUUID());
        Entry a1 = assistantEntry(conversationId, 2, u1.id(), "回答", UUID.randomUUID());
        Entry c1 = compactionEntry(conversationId, 3, a1.id(), a1.id());
        appendRaw(conversationId, u1);
        appendRaw(conversationId, a1);
        appendRaw(conversationId, c1);

        ConversationDetail detail = service.open(conversationId);
        assertThat(detail.conversation().latestCompactionEntryId()).isEqualTo(c1.id());
        assertThat(detail.conversation().latestCompactionSeq()).isEqualTo(c1.seq());
        assertThat(detail.conversation().latestCompactionByteOffset()).isNotNull();

        // 数据库压缩索引被写错：打开时必须按 JSONL 校验并修复
        jdbcTemplate.update(
                "UPDATE conversations SET latest_compaction_entry_id = ?, latest_compaction_seq = ?,"
                        + " latest_compaction_byte_offset = ? WHERE id = ?",
                UUID.randomUUID(), 99L, 99L, conversationId);

        ConversationDetail repaired = service.open(conversationId);
        assertThat(repaired.conversation().latestCompactionEntryId()).isEqualTo(c1.id());
        assertThat(repaired.conversation().latestCompactionSeq()).isEqualTo(c1.seq());
    }

    @Test
    void clearsCompactionIndexWhenJsonlHasNone() {
        ConversationSummary summary = service.create();
        UUID conversationId = summary.id();

        appendRaw(conversationId, userEntry(conversationId, 1, null, "第一问", UUID.randomUUID()));

        // 数据库残留压缩索引但 JSONL 无 Compaction Entry：修复为 NULL
        jdbcTemplate.update(
                "UPDATE conversations SET latest_compaction_entry_id = ?, latest_compaction_seq = ?,"
                        + " latest_compaction_byte_offset = ? WHERE id = ?",
                UUID.randomUUID(), 1L, 10L, conversationId);

        ConversationDetail detail = service.open(conversationId);
        assertThat(detail.conversation().latestCompactionEntryId()).isNull();
        assertThat(detail.conversation().latestCompactionSeq()).isNull();
        assertThat(detail.conversation().latestCompactionByteOffset()).isNull();
    }

    @Test
    void clearsCompactionIndexWhenLatestCompactionIsNotOnActivePath() {
        ConversationSummary summary = service.create();
        UUID conversationId = summary.id();

        // 主路径曾经压缩，之后从旧叶子开出分支：分支路径上没有 Compaction
        Entry u1 = userEntry(conversationId, 1, null, "第一问", UUID.randomUUID());
        Entry a1 = assistantEntry(conversationId, 2, u1.id(), "回答", UUID.randomUUID());
        Entry c1 = compactionEntry(conversationId, 3, a1.id(), a1.id());
        Entry branch = userEntry(conversationId, 4, a1.id(), "分支问题", UUID.randomUUID());
        appendRaw(conversationId, u1);
        appendRaw(conversationId, a1);
        appendRaw(conversationId, c1);
        appendRaw(conversationId, branch);

        // 数据库指针仍指向主路径上的 Compaction：不在当前 Active Path，必须修复为 NULL
        jdbcTemplate.update(
                "UPDATE conversations SET latest_compaction_entry_id = ?, latest_compaction_seq = ?,"
                        + " latest_compaction_byte_offset = ? WHERE id = ?",
                c1.id(), c1.seq(), 10L, conversationId);

        ConversationDetail detail = service.open(conversationId);
        assertThat(detail.conversation().activeLeafEntryId()).isEqualTo(branch.id());
        assertThat(detail.conversation().latestCompactionEntryId()).isNull();
        assertThat(detail.conversation().latestCompactionSeq()).isNull();
        assertThat(detail.conversation().latestCompactionByteOffset()).isNull();
        // 修复已写回数据库，第二次打开不再变化
        ConversationDetail again = service.open(conversationId);
        assertThat(again.conversation().latestCompactionEntryId()).isNull();
    }

    @Test
    void advancesCompactionIndexToLatestOnActivePath() {
        ConversationSummary summary = service.create();
        UUID conversationId = summary.id();

        // 两次压缩都在当前路径上：数据库只保存最新三元组
        Entry u1 = userEntry(conversationId, 1, null, "第一问", UUID.randomUUID());
        Entry a1 = assistantEntry(conversationId, 2, u1.id(), "回答", UUID.randomUUID());
        Entry c1 = compactionEntry(conversationId, 3, a1.id(), a1.id());
        Entry u2 = userEntry(conversationId, 4, c1.id(), "第二问", UUID.randomUUID());
        Entry a2 = assistantEntry(conversationId, 5, u2.id(), "回答二", UUID.randomUUID());
        Entry c2 = compactionEntry(conversationId, 6, a2.id(), a2.id());
        appendRaw(conversationId, u1);
        appendRaw(conversationId, a1);
        appendRaw(conversationId, c1);
        appendRaw(conversationId, u2);
        appendRaw(conversationId, a2);
        appendRaw(conversationId, c2);

        // 数据库指针停留在第一次压缩：必须推进到当前路径上的最新 Compaction
        jdbcTemplate.update(
                "UPDATE conversations SET latest_compaction_entry_id = ?, latest_compaction_seq = ?,"
                        + " latest_compaction_byte_offset = ? WHERE id = ?",
                c1.id(), c1.seq(), 10L, conversationId);

        ConversationDetail detail = service.open(conversationId);
        assertThat(detail.conversation().latestCompactionEntryId()).isEqualTo(c2.id());
        assertThat(detail.conversation().latestCompactionSeq()).isEqualTo(c2.seq());
        assertThat(detail.conversation().latestCompactionByteOffset()).isNotNull();
    }

    @Test
    void repairsTitleFromLatestTitleEntryWithoutAdvancingActiveLeaf() {
        ConversationSummary summary = service.create();
        UUID conversationId = summary.id();

        // JSONL 领先：User、Assistant 与 Title Entry 已落盘，数据库仍停留在创建态
        Entry u1 = userEntry(conversationId, 1, null, "第一问", UUID.randomUUID());
        Entry a1 = assistantEntry(conversationId, 2, u1.id(), "回答", UUID.randomUUID());
        Entry t1 = titleEntry(conversationId, 3, a1.id(), "模型标题");
        appendRaw(conversationId, u1);
        appendRaw(conversationId, a1);
        appendRaw(conversationId, t1);

        ConversationDetail detail = service.open(conversationId);
        // 标题从最新 Title Entry 修复
        assertThat(detail.conversation().title()).isEqualTo("模型标题");
        // Title 不推进 Active Path：叶子仍是 Assistant，seq 包含 Title
        assertThat(detail.conversation().activeLeafEntryId()).isEqualTo(a1.id());
        assertThat(detail.conversation().lastConfirmedSeq()).isEqualTo(3);
        assertThat(detail.activePath()).containsExactly(u1, a1);
        // 修复已写回数据库
        String dbTitle = jdbcTemplate.queryForObject(
                "SELECT title FROM conversations WHERE id = ?", String.class, conversationId);
        assertThat(dbTitle).isEqualTo("模型标题");
        Long dbSeq = jdbcTemplate.queryForObject(
                "SELECT last_confirmed_seq FROM conversations WHERE id = ?", Long.class, conversationId);
        assertThat(dbSeq).isEqualTo(3L);
    }

    // 模拟旧进程已落盘但数据库未提交的写入：直接追加原始 JSONL 行
    private void appendRaw(UUID conversationId, Entry entry) throws RuntimeException {
        try {
            Files.writeString(fileOf(conversationId), entryLine(entry) + "\n",
                    java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    private String entryLine(Entry entry) {
        StringBuilder line = new StringBuilder();
        line.append("{\"formatVersion\":").append(entry.formatVersion());
        line.append(",\"conversationId\":\"").append(entry.conversationId());
        line.append("\",\"id\":\"").append(entry.id());
        line.append("\",\"seq\":").append(entry.seq());
        if (entry.parentId() == null) {
            line.append(",\"parentId\":null");
        } else {
            line.append(",\"parentId\":\"").append(entry.parentId()).append('"');
        }
        line.append(",\"type\":\"").append(typeName(entry.type()));
        line.append("\",\"createdAt\":\"").append(entry.createdAt()).append('"');
        line.append(",\"payload\":").append(payloadLine(entry));
        line.append('}');
        return line.toString();
    }

    private String payloadLine(Entry entry) {
        return switch (entry.payload()) {
            case UserMessagePayload p -> "{\"text\":\"" + p.text() + "\",\"runId\":\"" + p.runId() + "\"}";
            case AssistantMessagePayload p -> {
                StringBuilder payload = new StringBuilder();
                payload.append("{\"text\":\"").append(p.text());
                payload.append("\",\"runId\":\"").append(p.runId());
                payload.append("\",\"provider\":\"").append(p.provider());
                payload.append("\",\"model\":\"").append(p.model()).append('"');
                if (p.usage() != null) {
                    payload.append(",\"usage\":{\"promptTokens\":").append(p.usage().promptTokens());
                    payload.append(",\"completionTokens\":").append(p.usage().completionTokens());
                    payload.append(",\"totalTokens\":").append(p.usage().totalTokens()).append('}');
                }
                payload.append('}');
                yield payload.toString();
            }
            case CompactionPayload p -> "{\"summary\":\"" + p.summary()
                    + "\",\"coveredThroughEntryId\":\"" + p.coveredThroughEntryId()
                    + "\",\"retainedTail\":[],\"tokensBefore\":" + p.tokensBefore() + "}";
            case TitlePayload p -> "{\"title\":\"" + p.title()
                    + "\",\"sourceRunId\":\"" + p.sourceRunId()
                    + "\",\"sourceAssistantEntryId\":\"" + p.sourceAssistantEntryId()
                    + "\",\"provider\":\"" + p.provider()
                    + "\",\"model\":\"" + p.model() + "\"}";
        };
    }

    private static String typeName(Entry.EntryType type) {
        return switch (type) {
            case USER_MESSAGE -> "user_message";
            case ASSISTANT_MESSAGE -> "assistant_message";
            case COMPACTION -> "compaction";
            case TITLE -> "title";
        };
    }

    private static Path fileOf(UUID conversationId) {
        return DATA_DIR.resolve("conversations").resolve(conversationId.toString()).resolve("events.jsonl");
    }

    private Entry userEntry(UUID conversationId, long seq, UUID parentId, String text, UUID runId) {
        return new Entry(1, conversationId, UUID.randomUUID(), seq, parentId,
                Entry.EntryType.USER_MESSAGE, Instant.parse("2026-08-01T00:00:00Z"),
                new UserMessagePayload(text, runId));
    }

    private Entry assistantEntry(UUID conversationId, long seq, UUID parentId, String text, UUID runId) {
        return new Entry(1, conversationId, UUID.randomUUID(), seq, parentId,
                Entry.EntryType.ASSISTANT_MESSAGE, Instant.parse("2026-08-01T00:00:00Z"),
                new AssistantMessagePayload(text, runId, "test-provider", "test-model",
                        new TokenUsage(10L, 5L, 15L)));
    }

    private Entry compactionEntry(UUID conversationId, long seq, UUID parentId, UUID coveredThroughEntryId) {
        return new Entry(1, conversationId, UUID.randomUUID(), seq, parentId,
                Entry.EntryType.COMPACTION, Instant.parse("2026-08-01T00:00:00Z"),
                new CompactionPayload("摘要", coveredThroughEntryId, List.of(), 100L, null));
    }

    private Entry titleEntry(UUID conversationId, long seq, UUID parentId, String title) {
        return new Entry(1, conversationId, UUID.randomUUID(), seq, parentId,
                Entry.EntryType.TITLE, Instant.parse("2026-08-01T00:00:00Z"),
                new TitlePayload(title, UUID.randomUUID(), parentId, "test-provider", "test-model"));
    }
}

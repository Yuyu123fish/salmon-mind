package com.yuyu.salmonmind.conversation.infrastructure.jsonl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.yuyu.salmonmind.conversation.api.AssistantMessagePayload;
import com.yuyu.salmonmind.conversation.api.CompactionPayload;
import com.yuyu.salmonmind.conversation.api.ConversationException;
import com.yuyu.salmonmind.conversation.api.Entry;
import com.yuyu.salmonmind.conversation.api.TitlePayload;
import com.yuyu.salmonmind.conversation.api.TokenUsage;
import com.yuyu.salmonmind.conversation.api.UserMessagePayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JSONL 历史 Adapter 聚焦测试：追加顺序、Active Path、末行截断修复、
 * 中间损坏拒绝、Compaction 偏移校验与 Header 身份校验。
 */
class JsonlConversationHistoryRepositoryTest {

    @TempDir
    Path tempDir;

    private final UUID conversationId = UUID.randomUUID();
    private JsonlCodec codec;
    private JsonlConversationHistoryRepository store;

    @BeforeEach
    void setUp() {
        codec = new JsonlCodec();
        store = new JsonlConversationHistoryRepository(tempDir, codec);
    }

    @Test
    void appendsInOrderAndBuildsActivePath() {
        store.create(conversationId, Instant.parse("2026-08-01T00:00:00Z"));

        Entry u1 = user(1, null, "第一问");
        Entry u2 = user(2, u1.id(), "第二问");
        Entry a2 = assistant(3, u2.id(), "回答");
        store.append(conversationId, u1);
        store.append(conversationId, u2);
        store.append(conversationId, a2);

        var history = store.read(conversationId);
        assertThat(history.entries()).containsExactly(u1, u2, a2);
        assertThat(history.activePath(a2.id())).containsExactly(u1, u2, a2);
        // 从中间叶子回溯只含其祖先链
        assertThat(history.activePath(u2.id())).containsExactly(u1, u2);
    }

    @Test
    void repairsTornTailAndAllowsFurtherAppends() throws Exception {
        store.create(conversationId, Instant.parse("2026-08-01T00:00:00Z"));
        Entry u1 = user(1, null, "第一问");
        store.append(conversationId, u1);
        // 模拟进程中断：末行 JSON 截断
        appendRaw("{\"formatVersion\":1,\"conversationId\":\"" + conversationId + "\",\"id\":\"" + UUID.randomUUID()
                + "\",\"seq\":2,\"parentId\":null,\"type\":\"user_message\",\"createdAt\":\"2026-08-01T00:00:00Z\",\"payload\":{\"text\":\"半截");

        var repaired = store.read(conversationId);
        assertThat(repaired.entries()).containsExactly(u1);
        // 修复已写回：再次读取干净，且可以继续追加
        assertThat(store.read(conversationId).entries()).containsExactly(u1);
        Entry u2 = user(2, u1.id(), "第二问");
        store.append(conversationId, u2);
        assertThat(store.read(conversationId).entries()).containsExactly(u1, u2);
    }

    @Test
    void rejectsCorruptionInMiddleLines() throws Exception {
        store.create(conversationId, Instant.parse("2026-08-01T00:00:00Z"));
        store.append(conversationId, user(1, null, "第一问"));
        // 中间行是完整但非法的 JSON（不是 Entry 结构）
        appendRaw("{\"bad\":1}");
        store.append(conversationId, user(2, UUID.randomUUID(), "第三问"));

        assertThatThrownBy(() -> store.read(conversationId))
                .isInstanceOfSatisfying(ConversationException.class, ex ->
                        assertThat(ex.code())
                                .isEqualTo(ConversationException.ConversationErrorCode.CONVERSATION_HISTORY_CORRUPTED));
    }

    @Test
    void rejectsCompleteButInvalidLastLine() throws Exception {
        store.create(conversationId, Instant.parse("2026-08-01T00:00:00Z"));
        store.append(conversationId, user(1, null, "第一问"));
        appendRaw("{\"bad\":1}");

        assertThatThrownBy(() -> store.read(conversationId))
                .isInstanceOfSatisfying(ConversationException.class, ex ->
                        assertThat(ex.code())
                                .isEqualTo(ConversationException.ConversationErrorCode.CONVERSATION_HISTORY_CORRUPTED));
    }

    @Test
    void validatesCompactionByteOffset() throws Exception {
        store.create(conversationId, Instant.parse("2026-08-01T00:00:00Z"));
        Entry u1 = user(1, null, "第一问");
        Entry a1 = assistant(2, u1.id(), "回答");
        Entry compaction = compaction(3, a1.id(), a1.id(), List.of(u1, a1));
        store.append(conversationId, u1);
        store.append(conversationId, a1);
        store.append(conversationId, compaction);

        var history = store.read(conversationId);
        int index = history.entries().indexOf(compaction);
        long offset = history.byteOffsets().get(index);

        assertThat(store.validateCompaction(conversationId, compaction.id(), compaction.seq(), offset)).isTrue();
        // 错误偏移、错误 id、越界偏移都不被采纳
        assertThat(store.validateCompaction(conversationId, compaction.id(), compaction.seq(), offset + 1)).isFalse();
        assertThat(store.validateCompaction(conversationId, UUID.randomUUID(), compaction.seq(), offset)).isFalse();
        assertThat(store.validateCompaction(conversationId, compaction.id(), compaction.seq(), Long.MAX_VALUE)).isFalse();
    }

    @Test
    void rejectsHeaderIdentityMismatch() {
        store.create(conversationId, Instant.parse("2026-08-01T00:00:00Z"));
        UUID other = UUID.randomUUID();

        assertThatThrownBy(() -> store.read(other))
                .isInstanceOfSatisfying(ConversationException.class, ex ->
                        assertThat(ex.code())
                                .isEqualTo(ConversationException.ConversationErrorCode.CONVERSATION_HISTORY_CORRUPTED));
    }

    @Test
    void rejectsNonSequentialSeq() throws Exception {
        store.create(conversationId, Instant.parse("2026-08-01T00:00:00Z"));
        Entry u1 = user(2, null, "seq 从 2 开始");
        store.append(conversationId, u1);

        assertThatThrownBy(() -> store.read(conversationId))
                .isInstanceOfSatisfying(ConversationException.class, ex ->
                        assertThat(ex.code())
                                .isEqualTo(ConversationException.ConversationErrorCode.CONVERSATION_HISTORY_CORRUPTED));
    }

    @Test
    void supportsCompactionPayloadRoundTrip() {
        store.create(conversationId, Instant.parse("2026-08-01T00:00:00Z"));
        Entry u1 = user(1, null, "第一问");
        Entry a1 = assistant(2, u1.id(), "回答");
        store.append(conversationId, u1);
        store.append(conversationId, a1);
        Entry compaction = compaction(3, a1.id(), a1.id(), List.of(u1, a1));
        store.append(conversationId, compaction);

        var history = store.read(conversationId);
        CompactionPayload payload = (CompactionPayload) history.entries().get(2).payload();
        assertThat(payload.coveredThroughEntryId()).isEqualTo(a1.id());
        assertThat(payload.retainedTail()).containsExactly(u1, a1);
        assertThat(payload.tokensBefore()).isEqualTo(120L);
    }

    @Test
    void titleEntryRoundTripsAndDoesNotAdvanceActivePath() {
        store.create(conversationId, Instant.parse("2026-08-01T00:00:00Z"));
        Entry u1 = user(1, null, "第一问");
        Entry a1 = assistant(2, u1.id(), "回答");
        Entry title = title(3, a1.id(), "对话标题");
        store.append(conversationId, u1);
        store.append(conversationId, a1);
        store.append(conversationId, title);

        var history = store.read(conversationId);
        assertThat(history.entries()).containsExactly(u1, a1, title);
        TitlePayload payload = (TitlePayload) history.entries().get(2).payload();
        assertThat(payload.title()).isEqualTo("对话标题");
        assertThat(payload.sourceAssistantEntryId()).isEqualTo(a1.id());
        assertThat(payload.sourceRunId()).isNotNull();
        assertThat(payload.provider()).isEqualTo("test-provider");
        // Title 不推进 Active Path：从助手叶子回溯不包含 Title Entry
        assertThat(history.activePath(a1.id())).containsExactly(u1, a1);
        assertThat(history.latestTitleEntry()).isEqualTo(title);
    }

    @Test
    void findsLatestTitleEntryFromFullHistory() {
        store.create(conversationId, Instant.parse("2026-08-01T00:00:00Z"));
        Entry u1 = user(1, null, "第一问");
        Entry a1 = assistant(2, u1.id(), "回答");
        Entry t1 = title(3, a1.id(), "旧标题");
        store.append(conversationId, u1);
        store.append(conversationId, a1);
        store.append(conversationId, t1);
        // 第二次成功交互后标题被更新：Title 是元数据事件，可多次追加，最新一条为准
        Entry u2 = user(4, a1.id(), "第二问");
        Entry a2 = assistant(5, u2.id(), "回答二");
        Entry t2 = title(6, a2.id(), "新标题");
        store.append(conversationId, u2);
        store.append(conversationId, a2);
        store.append(conversationId, t2);

        var history = store.read(conversationId);
        assertThat(history.latestTitleEntry()).isEqualTo(t2);
        assertThat(history.activePath(a2.id())).containsExactly(u1, a1, u2, a2);
    }

    @Test
    void latestCompactionIsLocatedOnlyOnActivePath() {
        store.create(conversationId, Instant.parse("2026-08-01T00:00:00Z"));
        Entry u1 = user(1, null, "第一问");
        Entry a1 = assistant(2, u1.id(), "回答");
        Entry u2 = user(3, a1.id(), "第二问");
        Entry a2 = assistant(4, u2.id(), "回答二");
        // 压缩发生在主路径上
        Entry c1 = compaction(5, a2.id(), a2.id(), List.of(u1, a1, u2, a2));
        store.append(conversationId, u1);
        store.append(conversationId, a1);
        store.append(conversationId, u2);
        store.append(conversationId, a2);
        store.append(conversationId, c1);
        // 之后从旧叶子 a1 开出分支：物理 seq 连续，但 parent 链回到 a1
        Entry branch = user(6, a1.id(), "分支问题");
        store.append(conversationId, branch);

        var history = store.read(conversationId);
        // 分支路径不含压缩：不能把主路径的 Compaction 当作分支的"最新压缩"
        assertThat(history.latestCompactionOnPath(branch.id())).isNull();
        // 主路径包含压缩
        assertThat(history.latestCompactionOnPath(c1.id())).isEqualTo(c1);
        assertThat(history.activePath(branch.id())).containsExactly(u1, a1, branch);
    }

    private Entry title(long seq, UUID parentId, String text) {
        return new Entry(1, conversationId, UUID.randomUUID(), seq, parentId,
                Entry.EntryType.TITLE, Instant.parse("2026-08-01T00:00:00Z"),
                new TitlePayload(text, UUID.randomUUID(), parentId, "test-provider", "test-model"));
    }

    private void appendRaw(String line) throws Exception {
        Files.writeString(
                store.fileOf(conversationId),
                line + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
    }

    private Entry user(long seq, UUID parentId, String text) {
        return new Entry(1, conversationId, UUID.randomUUID(), seq, parentId,
                Entry.EntryType.USER_MESSAGE, Instant.parse("2026-08-01T00:00:00Z"),
                new UserMessagePayload(text, UUID.randomUUID()));
    }

    private Entry assistant(long seq, UUID parentId, String text) {
        return new Entry(1, conversationId, UUID.randomUUID(), seq, parentId,
                Entry.EntryType.ASSISTANT_MESSAGE, Instant.parse("2026-08-01T00:00:00Z"),
                new AssistantMessagePayload(text, UUID.randomUUID(), "test-provider", "test-model",
                        new TokenUsage(10L, 5L, 15L)));
    }

    private Entry compaction(long seq, UUID parentId, UUID coveredThroughEntryId, List<Entry> tail) {
        return new Entry(1, conversationId, UUID.randomUUID(), seq, parentId,
                Entry.EntryType.COMPACTION, Instant.parse("2026-08-01T00:00:00Z"),
                new CompactionPayload("摘要", coveredThroughEntryId, tail, 120L, null));
    }
}

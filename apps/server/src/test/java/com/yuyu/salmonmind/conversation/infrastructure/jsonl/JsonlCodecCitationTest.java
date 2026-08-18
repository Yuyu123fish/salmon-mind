package com.yuyu.salmonmind.conversation.infrastructure.jsonl;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.salmonmind.conversation.api.AssistantMessagePayload;
import com.yuyu.salmonmind.conversation.api.LocalCitationPayload;
import com.yuyu.salmonmind.conversation.api.LocalRetrievedSourcePayload;
import com.yuyu.salmonmind.conversation.api.WebCitationPayload;
import com.yuyu.salmonmind.conversation.api.WebRetrievedSourcePayload;
import com.yuyu.salmonmind.conversation.api.Entry;
import com.yuyu.salmonmind.conversation.domain.ConversationHistory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Citation 可选字段的 v1 JSONL 向前兼容测试。 */
class JsonlCodecCitationTest {

    @Test
    void roundTripsLocalAndWebCitationsAndReadsOldEntryWithoutField() throws Exception {
        UUID conversationId = UUID.randomUUID();
        AssistantMessagePayload payload = new AssistantMessagePayload(
                "回答 [L1] [W1]", UUID.randomUUID(), "provider", "model", null,
                List.of(
                        new LocalCitationPayload("L1", UUID.randomUUID(), UUID.randomUUID(), "manual.md", "p1",
                                "支持本地资料"),
                        new WebCitationPayload("W1", "BOCHA", "网页", "https://example.com", "example.com",
                                "昨天", Instant.parse("2026-08-17T00:00:00Z"), "网页摘要支持该事实")),
                List.of(
                        new LocalRetrievedSourcePayload("L1", UUID.randomUUID(), UUID.randomUUID(), "manual.md",
                                "p1", Instant.parse("2026-08-17T00:00:00Z"), "LOCAL_EVIDENCE", "本地证据摘录"),
                        new WebRetrievedSourcePayload("W1", "BOCHA", "网页", "https://example.com",
                                "example.com", "昨天", Instant.parse("2026-08-17T00:00:00Z"),
                                "WEB_SEARCH_SUMMARY", "搜索摘要")),
                List.of());
        Entry entry = new Entry(ConversationHistory.FORMAT_VERSION, conversationId, UUID.randomUUID(), 1,
                null, Entry.EntryType.ASSISTANT_MESSAGE, Instant.parse("2026-08-17T00:00:00Z"), payload);
        JsonlCodec codec = new JsonlCodec();

        String httpJson = new ObjectMapper().findAndRegisterModules()
                .writeValueAsString(payload.citations());
        assertThat(httpJson).contains("\"kind\":\"local\"", "\"kind\":\"web\"");

        Entry decoded = codec.decodeEntry(codec.encodeEntry(entry));
        Entry oldEntry = new Entry(ConversationHistory.FORMAT_VERSION, conversationId, UUID.randomUUID(), 2,
                null, Entry.EntryType.ASSISTANT_MESSAGE, Instant.parse("2026-08-17T00:00:00Z"),
                new AssistantMessagePayload("旧回答", UUID.randomUUID(), "provider", "model", null));
        Entry oldDecoded = codec.decodeEntry(codec.encodeEntry(oldEntry));

        assertThat(((AssistantMessagePayload) decoded.payload()).citations()).hasSize(2);
        assertThat(((AssistantMessagePayload) decoded.payload()).citations().get(1))
                .isInstanceOf(WebCitationPayload.class);
        assertThat(((AssistantMessagePayload) decoded.payload()).citations().get(0).citationNote())
                .isEqualTo("支持本地资料");
        assertThat(((AssistantMessagePayload) decoded.payload()).retrievedSources()).hasSize(2);
        assertThat(((AssistantMessagePayload) decoded.payload()).retrievedSources().get(1))
                .isInstanceOf(WebRetrievedSourcePayload.class);
        assertThat(codec.encodeEntry(entry)).contains("\"retrievedSources\"", "\"sourceExcerpt\"");
        assertThat(((AssistantMessagePayload) oldDecoded.payload()).citations()).isEmpty();
        assertThat(((AssistantMessagePayload) oldDecoded.payload()).retrievedSources()).isEmpty();
    }
}

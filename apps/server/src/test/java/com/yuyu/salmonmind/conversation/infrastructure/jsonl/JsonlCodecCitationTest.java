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
                                "p1", Instant.parse("2026-08-17T00:00:00Z"), "LOCAL_EVIDENCE", "本地证据摘录",
                                "call-local", 1, null),
                        new WebRetrievedSourcePayload("W1", "BOCHA", "网页", "https://example.com",
                                "example.com", "昨天", Instant.parse("2026-08-17T00:00:00Z"),
                                "WEB_SEARCH_SUMMARY", "搜索摘要", "call-web", 2, 4)),
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
        assertThat(decoded.payload()).isInstanceOf(AssistantMessagePayload.class);
        var decodedSources = ((AssistantMessagePayload) decoded.payload()).retrievedSources();
        assertThat(decodedSources.get(0).originToolCallId()).isEqualTo("call-local");
        assertThat(decodedSources.get(0).resultPosition()).isEqualTo(1);
        assertThat(decodedSources.get(1).providerRank()).isEqualTo(4);
        assertThat(codec.encodeEntry(entry)).contains("\"retrievedSources\"", "\"sourceExcerpt\"");
        assertThat(((AssistantMessagePayload) oldDecoded.payload()).citations()).isEmpty();
        assertThat(((AssistantMessagePayload) oldDecoded.payload()).retrievedSources()).isEmpty();
    }

    @Test
    void ignoresMalformedOptionalSourceDisplayMetadata() throws Exception {
        UUID conversationId = UUID.randomUUID();
        String line = """
                {"formatVersion":1,"conversationId":"%s","id":"%s","seq":1,
                 "type":"assistant_message","createdAt":"2026-08-17T00:00:00Z",
                 "payload":{"text":"回答 [W1]","runId":"%s","provider":"provider","model":"model",
                 "citations":[],"retrievedSources":[
                 {"kind":"web","referenceId":"W1","provider":"BOCHA","title":"网页",
                  "url":"https://example.com","site":"example.com","retrievedAt":"2026-08-17T00:00:00Z",
                  "excerptKind":"WEB_SEARCH_SUMMARY","originToolCallId":17,"resultPosition":0,"providerRank":-1}]}}
                """.formatted(conversationId, UUID.randomUUID(), UUID.randomUUID())
                .replaceAll("\\s+", " ").trim();

        Entry decoded = new JsonlCodec().decodeEntry(line);

        AssistantMessagePayload payload = (AssistantMessagePayload) decoded.payload();
        assertThat(payload.text()).isEqualTo("回答 [W1]");
        assertThat(payload.retrievedSources()).hasSize(1);
        assertThat(payload.retrievedSources().getFirst().originToolCallId()).isNull();
        assertThat(payload.retrievedSources().getFirst().resultPosition()).isNull();
        assertThat(payload.retrievedSources().getFirst().providerRank()).isNull();
    }
}

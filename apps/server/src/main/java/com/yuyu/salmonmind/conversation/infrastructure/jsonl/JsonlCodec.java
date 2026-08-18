package com.yuyu.salmonmind.conversation.infrastructure.jsonl;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.io.JsonEOFException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyu.salmonmind.conversation.api.AssistantMessagePayload;
import com.yuyu.salmonmind.conversation.api.CitationPayload;
import com.yuyu.salmonmind.conversation.api.CompactionPayload;
import com.yuyu.salmonmind.conversation.api.Entry;
import com.yuyu.salmonmind.conversation.api.EntryPayload;
import com.yuyu.salmonmind.conversation.api.LocalCitationPayload;
import com.yuyu.salmonmind.conversation.api.LocalRetrievedSourcePayload;
import com.yuyu.salmonmind.conversation.api.RetrievedSourcePayload;
import com.yuyu.salmonmind.conversation.api.RunTraceItemPayload;
import com.yuyu.salmonmind.conversation.api.WebCitationPayload;
import com.yuyu.salmonmind.conversation.api.WebRetrievedSourcePayload;
import com.yuyu.salmonmind.conversation.api.TokenUsage;
import com.yuyu.salmonmind.conversation.api.TitlePayload;
import com.yuyu.salmonmind.conversation.api.UserMessagePayload;
import com.yuyu.salmonmind.conversation.domain.ConversationHistory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JSONL v1 的 Header / Entry 编解码。格式合同集中在单行 JSON 上，
 * 解析失败按「末行 JSON 截断」与「损坏」分类，由调用方决定修复或拒绝。
 */
@Component
class JsonlCodec {

    private final ObjectMapper mapper = new ObjectMapper();

    String encodeHeader(ConversationHistory.Header header) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "conversation");
        node.put("formatVersion", header.formatVersion());
        node.put("conversationId", header.conversationId().toString());
        node.put("createdAt", header.createdAt().toString());
        return writeString(node);
    }

    ConversationHistory.Header decodeHeader(String line) {
        JsonNode node;
        try {
            node = parseWhole(line);
        } catch (TornTailException ex) {
            // Header 行截断说明文件无效，不是可修复的末行写入中断
            throw corrupted("Header 行 JSON 截断");
        }
        if (!"conversation".equals(text(node, "type")) || !node.has("formatVersion")) {
            throw corrupted("Header 行缺少 conversation type 或 formatVersion");
        }
        int formatVersion = node.get("formatVersion").asInt();
        if (formatVersion != ConversationHistory.FORMAT_VERSION) {
            throw corrupted("不支持的 history formatVersion: " + formatVersion);
        }
        UUID conversationId = uuid(node, "conversationId");
        Instant createdAt = instant(node, "createdAt");
        return new ConversationHistory.Header(formatVersion, conversationId, createdAt);
    }

    String encodeEntry(Entry entry) {
        return writeString(encodeEntryNode(entry));
    }

    Entry decodeEntry(String line) {
        return decodeEntryNode(parseWhole(line));
    }

    private ObjectNode encodeEntryNode(Entry entry) {
        ObjectNode node = mapper.createObjectNode();
        node.put("formatVersion", entry.formatVersion());
        node.put("conversationId", entry.conversationId().toString());
        node.put("id", entry.id().toString());
        node.put("seq", entry.seq());
        if (entry.parentId() == null) {
            node.putNull("parentId");
        } else {
            node.put("parentId", entry.parentId().toString());
        }
        node.put("type", typeName(entry.type()));
        node.put("createdAt", entry.createdAt().toString());
        node.set("payload", encodePayload(entry.payload()));
        return node;
    }

    private JsonNode encodePayload(EntryPayload payload) {
        return switch (payload) {
            case UserMessagePayload p -> {
                ObjectNode node = mapper.createObjectNode();
                node.put("text", p.text());
                node.put("runId", p.runId().toString());
                yield node;
            }
            case AssistantMessagePayload p -> {
                ObjectNode node = mapper.createObjectNode();
                node.put("text", p.text());
                node.put("runId", p.runId().toString());
                node.put("provider", p.provider());
                node.put("model", p.model());
                if (p.usage() != null) {
                    node.set("usage", encodeUsage(p.usage()));
                }
                if (!p.citations().isEmpty()) {
                    ArrayNode citations = node.putArray("citations");
                    for (CitationPayload citation : p.citations()) {
                        citations.add(encodeCitation(citation));
                    }
                }
                if (!p.retrievedSources().isEmpty()) {
                    ArrayNode sources = node.putArray("retrievedSources");
                    for (RetrievedSourcePayload source : p.retrievedSources()) {
                        sources.add(encodeRetrievedSource(source));
                    }
                }
                if (!p.trace().isEmpty()) {
                    ArrayNode trace = node.putArray("trace");
                    for (RunTraceItemPayload item : p.trace()) {
                        trace.add(encodeTraceItem(item));
                    }
                }
                yield node;
            }
            case CompactionPayload p -> {
                ObjectNode node = mapper.createObjectNode();
                node.put("summary", p.summary());
                node.put("coveredThroughEntryId", p.coveredThroughEntryId().toString());
                ArrayNode tail = node.putArray("retainedTail");
                for (Entry e : p.retainedTail()) {
                    tail.add(encodeEntryNode(e));
                }
                node.put("tokensBefore", p.tokensBefore());
                if (p.usage() != null) {
                    node.set("usage", encodeUsage(p.usage()));
                }
                yield node;
            }
            case TitlePayload p -> {
                ObjectNode node = mapper.createObjectNode();
                node.put("title", p.title());
                node.put("sourceRunId", p.sourceRunId().toString());
                node.put("sourceAssistantEntryId", p.sourceAssistantEntryId().toString());
                node.put("provider", p.provider());
                node.put("model", p.model());
                yield node;
            }
        };
    }

    private ObjectNode encodeUsage(TokenUsage usage) {
        ObjectNode node = mapper.createObjectNode();
        if (usage.promptTokens() != null) {
            node.put("promptTokens", usage.promptTokens());
        }
        if (usage.completionTokens() != null) {
            node.put("completionTokens", usage.completionTokens());
        }
        if (usage.totalTokens() != null) {
            node.put("totalTokens", usage.totalTokens());
        }
        return node;
    }

    private Entry decodeEntryNode(JsonNode node) {
        if (!node.has("formatVersion") || !node.has("conversationId") || !node.has("id")
                || !node.has("seq") || !node.has("type") || !node.has("createdAt") || !node.has("payload")) {
            throw corrupted("Entry 缺少固定公共字段");
        }
        int formatVersion = node.get("formatVersion").asInt();
        if (formatVersion != ConversationHistory.FORMAT_VERSION) {
            throw corrupted("Entry 的 formatVersion 不受支持: " + formatVersion);
        }
        UUID conversationId = uuid(node, "conversationId");
        UUID id = uuid(node, "id");
        long seq = node.get("seq").asLong();
        if (seq < 0) {
            throw corrupted("Entry 的 seq 为负: " + seq);
        }
        UUID parentId = node.hasNonNull("parentId") ? uuid(node, "parentId") : null;
        Entry.EntryType type = typeOf(text(node, "type"));
        Instant createdAt = instant(node, "createdAt");
        EntryPayload payload = decodePayload(type, node.get("payload"));
        return new Entry(formatVersion, conversationId, id, seq, parentId, type, createdAt, payload);
    }

    private EntryPayload decodePayload(Entry.EntryType type, JsonNode node) {
        return switch (type) {
            case USER_MESSAGE -> new UserMessagePayload(text(node, "text"), uuid(node, "runId"));
            case ASSISTANT_MESSAGE -> {
                TokenUsage usage = node.hasNonNull("usage") ? decodeUsage(node.get("usage")) : null;
                List<CitationPayload> citations = decodeCitations(node.get("citations"));
                List<RetrievedSourcePayload> retrievedSources = decodeRetrievedSources(node.get("retrievedSources"));
                List<RunTraceItemPayload> trace = decodeTrace(node.get("trace"));
                yield new AssistantMessagePayload(
                        text(node, "text"), uuid(node, "runId"), text(node, "provider"), text(node, "model"),
                        usage, citations, retrievedSources, trace);
            }
            case COMPACTION -> {
                TokenUsage usage = node.hasNonNull("usage") ? decodeUsage(node.get("usage")) : null;
                List<Entry> tail = new ArrayList<>();
                JsonNode tailNode = node.get("retainedTail");
                if (tailNode == null || !tailNode.isArray()) {
                    throw corrupted("compaction 缺少 retainedTail 数组");
                }
                for (JsonNode item : tailNode) {
                    tail.add(decodeEntryNode(item));
                }
                yield new CompactionPayload(
                        text(node, "summary"),
                        uuid(node, "coveredThroughEntryId"),
                        List.copyOf(tail),
                        node.hasNonNull("tokensBefore") ? node.get("tokensBefore").asLong() : null,
                        usage);
            }
            case TITLE -> new TitlePayload(
                    text(node, "title"),
                    uuid(node, "sourceRunId"),
                    uuid(node, "sourceAssistantEntryId"),
                    text(node, "provider"),
                    text(node, "model"));
        };
    }

    private TokenUsage decodeUsage(JsonNode node) {
        return new TokenUsage(
                node.hasNonNull("promptTokens") ? node.get("promptTokens").asLong() : null,
                node.hasNonNull("completionTokens") ? node.get("completionTokens").asLong() : null,
                node.hasNonNull("totalTokens") ? node.get("totalTokens").asLong() : null);
    }

    private ObjectNode encodeCitation(CitationPayload citation) {
        ObjectNode node = mapper.createObjectNode();
        node.put("referenceId", citation.referenceId());
        if (citation.citationNote() != null) {
            node.put("citationNote", citation.citationNote());
        }
        switch (citation) {
            case LocalCitationPayload local -> {
                node.put("kind", "local");
                node.put("evidenceId", local.evidenceId().toString());
                node.put("revisionId", local.revisionId().toString());
                node.put("documentName", local.documentName());
                node.put("location", local.location());
            }
            case WebCitationPayload web -> {
                node.put("kind", "web");
                node.put("provider", web.provider());
                node.put("title", web.title());
                node.put("url", web.url());
                node.put("site", web.site());
                if (web.dateLabel() != null) {
                    node.put("dateLabel", web.dateLabel());
                }
                node.put("retrievedAt", web.retrievedAt().toString());
            }
        }
        return node;
    }

    private ObjectNode encodeTraceItem(RunTraceItemPayload item) {
        ObjectNode node = mapper.createObjectNode();
        node.put("kind", item.kind() == RunTraceItemPayload.Kind.REASONING ? "reasoning" : "tool");
        node.put("truncated", item.truncated());
        if (item.kind() == RunTraceItemPayload.Kind.REASONING) {
            node.put("text", item.text());
            return node;
        }
        node.put("toolCallId", item.toolCallId());
        node.put("toolName", item.toolName());
        node.put("status", switch (item.toolStatus()) {
            case RUNNING -> "running";
            case COMPLETED -> "completed";
            case FAILED -> "failed";
        });
        node.put("safeSummary", item.safeSummary());
        if (item.stableErrorCode() != null) {
            node.put("stableErrorCode", item.stableErrorCode());
        }
        return node;
    }

    private ObjectNode encodeRetrievedSource(RetrievedSourcePayload source) {
        ObjectNode node = mapper.createObjectNode();
        node.put("referenceId", source.referenceId());
        node.put("kind", source.kind());
        node.put("retrievedAt", source.retrievedAt().toString());
        node.put("excerptKind", source.excerptKind());
        if (source.sourceExcerpt() != null) {
            node.put("sourceExcerpt", source.sourceExcerpt());
        }
        switch (source) {
            case LocalRetrievedSourcePayload local -> {
                node.put("evidenceId", local.evidenceId().toString());
                node.put("revisionId", local.revisionId().toString());
                node.put("documentName", local.documentName());
                node.put("location", local.location());
            }
            case WebRetrievedSourcePayload web -> {
                node.put("provider", web.provider());
                node.put("title", web.title());
                node.put("url", web.url());
                node.put("site", web.site());
                if (web.dateLabel() != null) {
                    node.put("dateLabel", web.dateLabel());
                }
            }
        }
        return node;
    }

    private List<CitationPayload> decodeCitations(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw corrupted("assistant citations 不是数组");
        }
        List<CitationPayload> citations = new ArrayList<>();
        for (JsonNode item : node) {
            String kind = text(item, "kind");
            String referenceId = text(item, "referenceId");
            if ("local".equals(kind)) {
                citations.add(new LocalCitationPayload(referenceId, uuid(item, "evidenceId"),
                        uuid(item, "revisionId"), text(item, "documentName"), text(item, "location"),
                        optionalText(item, "citationNote")));
            } else if ("web".equals(kind)) {
                citations.add(new WebCitationPayload(referenceId, text(item, "provider"), text(item, "title"),
                        text(item, "url"), text(item, "site"), optionalText(item, "dateLabel"),
                        instant(item, "retrievedAt"), optionalText(item, "citationNote")));
            } else {
                throw corrupted("未知 Citation kind: " + kind);
            }
        }
        return List.copyOf(citations);
    }

    private List<RetrievedSourcePayload> decodeRetrievedSources(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw corrupted("assistant retrievedSources 不是数组");
        }
        List<RetrievedSourcePayload> sources = new ArrayList<>();
        for (JsonNode item : node) {
            String kind = text(item, "kind");
            String referenceId = text(item, "referenceId");
            String excerptKind = text(item, "excerptKind");
            Instant retrievedAt = instant(item, "retrievedAt");
            if (referenceId == null || !referenceId.matches("[LW][1-9][0-9]*")
                    || excerptKind == null || retrievedAt == null) {
                throw corrupted("retrieved source 身份字段缺失");
            }
            if ("local".equals(kind)) {
                UUID evidenceId = uuid(item, "evidenceId");
                UUID revisionId = uuid(item, "revisionId");
                String documentName = text(item, "documentName");
                String location = text(item, "location");
                if (evidenceId == null || revisionId == null || documentName == null || location == null) {
                    throw corrupted("local retrieved source 身份字段损坏");
                }
                sources.add(new LocalRetrievedSourcePayload(
                        referenceId, evidenceId, revisionId, documentName, location, retrievedAt,
                        excerptKind, optionalText(item, "sourceExcerpt")));
            } else if ("web".equals(kind)) {
                String provider = text(item, "provider");
                String title = text(item, "title");
                String url = text(item, "url");
                String site = text(item, "site");
                if (provider == null || title == null || url == null || site == null) {
                    throw corrupted("web retrieved source 身份字段损坏");
                }
                sources.add(new WebRetrievedSourcePayload(
                        referenceId, provider, title, url, site, optionalText(item, "dateLabel"),
                        retrievedAt, excerptKind, optionalText(item, "sourceExcerpt")));
            } else {
                throw corrupted("未知 Retrieved Source kind: " + kind);
            }
        }
        return List.copyOf(sources);
    }

    private List<RunTraceItemPayload> decodeTrace(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw corrupted("assistant trace 不是数组");
        }
        List<RunTraceItemPayload> trace = new ArrayList<>();
        for (JsonNode item : node) {
            boolean truncated = optionalBoolean(item, "truncated", false);
            switch (text(item, "kind")) {
                case "reasoning" -> trace.add(RunTraceItemPayload.reasoning(
                        text(item, "text"), truncated));
                case "tool" -> trace.add(RunTraceItemPayload.tool(
                        text(item, "toolCallId"),
                        text(item, "toolName"),
                        switch (text(item, "status")) {
                            case "running" -> RunTraceItemPayload.ToolStatus.RUNNING;
                            case "completed" -> RunTraceItemPayload.ToolStatus.COMPLETED;
                            case "failed" -> RunTraceItemPayload.ToolStatus.FAILED;
                            default -> throw corrupted("未知 Tool Trace status");
                        },
                        text(item, "safeSummary"),
                        optionalText(item, "stableErrorCode"),
                        truncated));
                default -> throw corrupted("未知 Trace kind");
            }
        }
        return List.copyOf(trace);
    }

    // 解析整行：JSON 语法截断（EOF 未闭合）抛 TornTailException，其余解析错误视为损坏
    private JsonNode parseWhole(String line) {
        try (JsonParser parser = mapper.getFactory().createParser(line)) {
            JsonNode node = mapper.readTree(parser);
            if (parser.nextToken() != null) {
                throw corrupted("单行包含多个 JSON 值");
            }
            return node;
        } catch (JsonEOFException ex) {
            throw new TornTailException();
        } catch (JsonProcessingException ex) {
            throw corrupted("JSON 语法错误", ex);
        } catch (IOException ex) {
            throw corrupted("JSON 读取失败", ex);
        }
    }

    private String writeString(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Entry 序列化失败", ex);
        }
    }

    private static String typeName(Entry.EntryType type) {
        return switch (type) {
            case USER_MESSAGE -> "user_message";
            case ASSISTANT_MESSAGE -> "assistant_message";
            case COMPACTION -> "compaction";
            case TITLE -> "title";
        };
    }

    private static Entry.EntryType typeOf(String name) {
        return switch (name) {
            case "user_message" -> Entry.EntryType.USER_MESSAGE;
            case "assistant_message" -> Entry.EntryType.ASSISTANT_MESSAGE;
            case "compaction" -> Entry.EntryType.COMPACTION;
            case "title" -> Entry.EntryType.TITLE;
            default -> throw corrupted("未知 Entry type: " + name);
        };
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw corrupted("缺少文本字段: " + field);
        }
        return value.asText();
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw corrupted("字段不是文本: " + field);
        }
        return value.asText();
    }

    private static boolean optionalBoolean(JsonNode node, String field, boolean defaultValue) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (!value.isBoolean()) {
            throw corrupted("字段不是布尔值: " + field);
        }
        return value.asBoolean();
    }

    private static UUID uuid(JsonNode node, String field) {
        String value = text(node, field);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw corrupted("字段不是合法 UUID: " + field);
        }
    }

    private static Instant instant(JsonNode node, String field) {
        String value = text(node, field);
        try {
            return Instant.parse(value);
        } catch (java.time.format.DateTimeParseException ex) {
            throw corrupted("字段不是 ISO-8601 时间: " + field);
        }
    }

    private static JsonlCorruptedException corrupted(String message) {
        return new JsonlCorruptedException(message);
    }

    private static JsonlCorruptedException corrupted(String message, Throwable cause) {
        return new JsonlCorruptedException(message, cause);
    }

    /** 行损坏，必须拒绝。 */
    static class JsonlCorruptedException extends RuntimeException {
        JsonlCorruptedException(String message) {
            super(message);
        }

        JsonlCorruptedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 末行 JSON 语法截断：属于未确认写入，调用方可以安全删除该行后修复。 */
    static class TornTailException extends RuntimeException {
        TornTailException() {
            super("末行 JSON 截断");
        }
    }
}

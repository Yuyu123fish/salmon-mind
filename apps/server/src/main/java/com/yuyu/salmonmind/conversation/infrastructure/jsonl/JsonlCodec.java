package com.yuyu.salmonmind.conversation.infrastructure.jsonl;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.io.JsonEOFException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyu.salmonmind.conversation.api.AssistantMessagePayload;
import com.yuyu.salmonmind.conversation.api.CompactionPayload;
import com.yuyu.salmonmind.conversation.api.Entry;
import com.yuyu.salmonmind.conversation.api.EntryPayload;
import com.yuyu.salmonmind.conversation.api.TokenUsage;
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
                yield new AssistantMessagePayload(
                        text(node, "text"), uuid(node, "runId"), text(node, "provider"), text(node, "model"), usage);
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
        };
    }

    private TokenUsage decodeUsage(JsonNode node) {
        return new TokenUsage(
                node.hasNonNull("promptTokens") ? node.get("promptTokens").asLong() : null,
                node.hasNonNull("completionTokens") ? node.get("completionTokens").asLong() : null,
                node.hasNonNull("totalTokens") ? node.get("totalTokens").asLong() : null);
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
        };
    }

    private static Entry.EntryType typeOf(String name) {
        return switch (name) {
            case "user_message" -> Entry.EntryType.USER_MESSAGE;
            case "assistant_message" -> Entry.EntryType.ASSISTANT_MESSAGE;
            case "compaction" -> Entry.EntryType.COMPACTION;
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

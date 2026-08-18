package com.yuyu.salmonmind.conversation.infrastructure.jsonl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyu.salmonmind.conversation.api.Entry;
import com.yuyu.salmonmind.conversation.domain.ConversationHistory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Conversation 快照的显式 JSON codec。快照包含完整 Header、Entries、Entry 字节偏移和 JSONL 版本，
 * 不使用 Java 原生序列化；任何 schema、身份或基本结构不符都只会导致缓存 Miss。
 */
@Component
class ConversationSnapshotCodec {

    static final int SCHEMA_VERSION = 1;

    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonlCodec jsonlCodec;

    ConversationSnapshotCodec(JsonlCodec jsonlCodec) {
        this.jsonlCodec = jsonlCodec;
    }

    byte[] encode(UUID conversationId, JsonlAuthorityVersion authorityVersion, ConversationHistory history) {
        if (!conversationId.equals(history.header().conversationId())) {
            throw corrupted("快照 Header 身份不一致");
        }
        validateEntries(conversationId, history.entries());
        if (history.entries().size() != history.byteOffsets().size()) {
            throw corrupted("快照 Entries 与 byteOffsets 数量不一致");
        }

        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("conversationId", conversationId.toString());
        root.set("authorityVersion", encodeAuthorityVersion(authorityVersion));
        root.set("header", jsonlCodec.encodeHeaderNode(history.header()));

        ArrayNode entries = root.putArray("entries");
        for (Entry entry : history.entries()) {
            entries.add(jsonlCodec.encodeEntryNode(entry));
        }

        ArrayNode offsets = root.putArray("entryByteOffsets");
        for (Long offset : history.byteOffsets()) {
            if (offset == null || offset < 0) {
                throw corrupted("快照 byteOffset 非法");
            }
            offsets.add(offset);
        }
        try {
            return mapper.writeValueAsBytes(root);
        } catch (JsonProcessingException ex) {
            throw new SnapshotCorruptedException("快照序列化失败", ex);
        }
    }

    Snapshot decode(byte[] payload) {
        try {
            JsonNode root;
            try (JsonParser parser = mapper.getFactory().createParser(payload)) {
                root = mapper.readTree(parser);
                if (parser.nextToken() != null) {
                    throw corrupted("快照包含多个 JSON 值");
                }
            }
            if (root == null || !root.isObject()) {
                throw corrupted("快照根节点不是对象");
            }
            if (root.path("schemaVersion").asInt(-1) != SCHEMA_VERSION) {
                throw corrupted("快照 schema version 不受支持");
            }
            UUID conversationId = uuid(root, "conversationId");
            JsonlAuthorityVersion authorityVersion = decodeAuthorityVersion(root.get("authorityVersion"));
            JsonNode headerNode = root.get("header");
            if (headerNode == null || !headerNode.isObject()) {
                throw corrupted("快照缺少 Header");
            }
            ConversationHistory.Header header = jsonlCodec.decodeHeaderNode(headerNode);
            if (!conversationId.equals(header.conversationId())) {
                throw corrupted("快照 Header 身份不一致");
            }

            JsonNode entriesNode = root.get("entries");
            JsonNode offsetsNode = root.get("entryByteOffsets");
            if (entriesNode == null || !entriesNode.isArray()
                    || offsetsNode == null || !offsetsNode.isArray()
                    || entriesNode.size() != offsetsNode.size()) {
                throw corrupted("快照 Entries 与 byteOffsets 结构不一致");
            }

            List<Entry> entries = new java.util.ArrayList<>();
            for (JsonNode entryNode : entriesNode) {
                entries.add(jsonlCodec.decodeEntryNode(entryNode));
            }
            validateEntries(conversationId, entries);

            List<Long> offsets = new java.util.ArrayList<>();
            long previous = -1;
            for (JsonNode offsetNode : offsetsNode) {
                if (!offsetNode.isIntegralNumber() || offsetNode.asLong() < 0
                        || offsetNode.asLong() <= previous) {
                    throw corrupted("快照 byteOffsets 不连续");
                }
                previous = offsetNode.asLong();
                offsets.add(previous);
            }
            return new Snapshot(
                    conversationId,
                    authorityVersion,
                    new ConversationHistory(header, List.copyOf(entries), List.copyOf(offsets)));
        } catch (SnapshotCorruptedException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw new SnapshotCorruptedException("快照解码失败", ex);
        }
    }

    private ObjectNode encodeAuthorityVersion(JsonlAuthorityVersion version) {
        ObjectNode node = mapper.createObjectNode();
        node.put("size", version.size());
        node.put("lastModifiedNanos", version.lastModifiedNanos());
        if (version.fileKey() == null) {
            node.putNull("fileKey");
        } else {
            node.put("fileKey", version.fileKey());
        }
        return node;
    }

    private JsonlAuthorityVersion decodeAuthorityVersion(JsonNode node) {
        if (node == null || !node.isObject()
                || !node.has("size") || !node.has("lastModifiedNanos")) {
            throw corrupted("快照缺少 Authority Version");
        }
        long size = node.get("size").asLong(-1);
        long lastModifiedNanos = node.get("lastModifiedNanos").asLong(-1);
        if (!node.get("size").isIntegralNumber() || size < 0
                || !node.get("lastModifiedNanos").isIntegralNumber() || lastModifiedNanos < 0) {
            throw corrupted("快照 Authority Version 非法");
        }
        String fileKey = node.hasNonNull("fileKey") ? text(node, "fileKey") : null;
        return new JsonlAuthorityVersion(size, lastModifiedNanos, fileKey);
    }

    private void validateEntries(UUID conversationId, List<Entry> entries) {
        Set<UUID> ids = new HashSet<>();
        long expectedSeq = 1;
        for (Entry entry : entries) {
            if (!conversationId.equals(entry.conversationId())
                    || entry.formatVersion() != ConversationHistory.FORMAT_VERSION
                    || entry.seq() != expectedSeq
                    || !ids.add(entry.id())) {
                throw corrupted("快照 Entry 身份或 seq 非法");
            }
            expectedSeq++;
        }
    }

    private static UUID uuid(JsonNode node, String field) {
        String value = text(node, field);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw corrupted("快照字段不是合法 UUID: " + field);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw corrupted("快照缺少文本字段: " + field);
        }
        return value.asText();
    }

    private static SnapshotCorruptedException corrupted(String message) {
        return new SnapshotCorruptedException(message);
    }

    record Snapshot(UUID conversationId, JsonlAuthorityVersion authorityVersion, ConversationHistory history) {
    }

    static class SnapshotCorruptedException extends RuntimeException {
        SnapshotCorruptedException(String message) {
            super(message);
        }

        SnapshotCorruptedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

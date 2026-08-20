package com.yuyu.salmonmind.agent.infrastructure.reactagent;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.salmonmind.codebase.api.CodebaseErrorCode;
import com.yuyu.salmonmind.codebase.api.CodebaseException;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * 独立的临时调用链 Tool。它只接受节点身份、相对位置和边，不接受源码或 source hash；
 * 源码覆盖与 hash 由同一个 Run 的 ReadFile 证据和 codebase prepare 再次核验。
 */
final class CallChainToolCallback implements org.springframework.ai.tool.ToolCallback {

    static final String NAME = "stage_call_chain";
    private static final Set<String> FIELDS = Set.of("name", "nodes", "edges", "allowUserNameOverride");
    private static final Set<String> NODE_FIELDS = Set.of(
            "key", "language", "qualifiedSymbol", "signature", "path",
            "startLine", "endLine", "summary");
    private static final Set<String> EDGE_FIELDS = Set.of("from", "to");
    private static final String SCHEMA = """
            {"type":"object","properties":{"name":{"type":"string","minLength":1,"maxLength":120},"allowUserNameOverride":{"type":"boolean","default":false},"nodes":{"type":"array","minItems":2,"maxItems":12,"items":{"type":"object","properties":{"key":{"type":"string","minLength":1,"maxLength":80},"language":{"type":"string","minLength":1,"maxLength":40},"qualifiedSymbol":{"type":"string","minLength":1,"maxLength":500},"signature":{"type":"string","minLength":1,"maxLength":2000},"path":{"type":"string","minLength":1,"maxLength":512},"startLine":{"type":"integer","minimum":1},"endLine":{"type":"integer","minimum":1},"summary":{"type":"string","maxLength":500}},"required":["key","language","qualifiedSymbol","signature","path","startLine","endLine","summary"],"additionalProperties":false}},"edges":{"type":"array","minItems":1,"maxItems":24,"items":{"type":"object","properties":{"from":{"type":"string","minLength":1,"maxLength":80},"to":{"type":"string","minLength":1,"maxLength":80}},"required":["from","to"],"additionalProperties":false}}},"required":["name","nodes","edges"],"additionalProperties":false}
            """;

    private final ObjectMapper mapper;

    CallChainToolCallback(ObjectMapper mapper) {
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(NAME)
                .description("把本次已读到的两个或更多相关方法整理成一条临时调用链；只接受相对路径和结构化节点，不保存源码。")
                .inputSchema(SCHEMA)
                .build();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        try {
            JsonNode root = parseObject(toolInput, FIELDS);
            CodebaseRunContext context = contextOf(toolContext);
            if (context == null) {
                return failure("CODEBASE_UNAVAILABLE");
            }
            List<CodebaseRunContext.DraftNode> nodes = parseNodes(root.get("nodes"));
            List<CodebaseRunContext.DraftEdge> edges = parseEdges(root.get("edges"));
            CodebaseRunContext.StageSummary summary = context.stage(
                    requiredText(root, "name", 120), nodes, edges,
                    optionalBoolean(root, "allowUserNameOverride", false));
            return "{\"status\":\"SUCCESS\",\"reason\":\"DRAFT_STAGED\","
                    + "\"sourceKind\":\"CODEBASE\",\"provider\":\"CODEBASE\","
                    + "\"operation\":\"" + NAME + "\",\"name\":" + quote(summary.name())
                    + ",\"nodeCount\":" + summary.nodeCount()
                    + ",\"edgeCount\":" + summary.edgeCount() + "}";
        } catch (InvalidInput ex) {
            return failure(CodebaseErrorCode.INVALID_QUERY.name());
        } catch (CodebaseException ex) {
            return failure(ex);
        } catch (RuntimeException ex) {
            return failure("CODEBASE_UNAVAILABLE");
        }
    }

    private List<CodebaseRunContext.DraftNode> parseNodes(JsonNode value) {
        if (value == null || !value.isArray() || value.size() < 2 || value.size() > 12) {
            throw new InvalidInput();
        }
        List<CodebaseRunContext.DraftNode> result = new ArrayList<>();
        for (JsonNode node : value) {
            ensureObjectFields(node, NODE_FIELDS);
            result.add(new CodebaseRunContext.DraftNode(
                    requiredText(node, "key", 80), requiredText(node, "language", 40),
                    requiredText(node, "qualifiedSymbol", 500), requiredText(node, "signature", 2_000),
                    requiredText(node, "path", 512), requiredInt(node, "startLine"),
                    requiredInt(node, "endLine"), requiredTextAllowBlank(node, "summary", 500)));
        }
        return List.copyOf(result);
    }

    private List<CodebaseRunContext.DraftEdge> parseEdges(JsonNode value) {
        if (value == null || !value.isArray() || value.isEmpty() || value.size() > 24) {
            throw new InvalidInput();
        }
        List<CodebaseRunContext.DraftEdge> result = new ArrayList<>();
        for (JsonNode edge : value) {
            ensureObjectFields(edge, EDGE_FIELDS);
            result.add(new CodebaseRunContext.DraftEdge(
                    requiredText(edge, "from", 80), requiredText(edge, "to", 80)));
        }
        return List.copyOf(result);
    }

    private JsonNode parseObject(String input, Set<String> fields) {
        if (input == null) {
            throw new InvalidInput();
        }
        try (JsonParser parser = mapper.getFactory().createParser(input)) {
            JsonNode root = mapper.readTree(parser);
            if (root == null || !root.isObject() || parser.nextToken() != null) {
                throw new InvalidInput();
            }
            ensureObjectFields(root, fields);
            return root;
        } catch (InvalidInput ex) {
            throw ex;
        } catch (Exception ex) {
            throw new InvalidInput();
        }
    }

    private void ensureObjectFields(JsonNode value, Set<String> fields) {
        if (value == null || !value.isObject()) {
            throw new InvalidInput();
        }
        Iterator<String> names = value.fieldNames();
        while (names.hasNext()) {
            if (!fields.contains(names.next())) {
                throw new InvalidInput();
            }
        }
    }

    private String requiredText(JsonNode root, String field, int maximum) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual()) {
            throw new InvalidInput();
        }
        String text = value.asText().trim();
        if (text.isBlank() || text.length() > maximum || text.indexOf('\u0000') >= 0) {
            throw new InvalidInput();
        }
        return text;
    }

    private String requiredTextAllowBlank(JsonNode root, String field, int maximum) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || value.asText().length() > maximum
                || value.asText().indexOf('\u0000') >= 0) {
            throw new InvalidInput();
        }
        return value.asText().trim();
    }

    private int requiredInt(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 1) {
            throw new InvalidInput();
        }
        return value.asInt();
    }

    private boolean optionalBoolean(JsonNode root, String field, boolean defaultValue) {
        JsonNode value = root.get(field);
        if (value == null) {
            return defaultValue;
        }
        if (!value.isBoolean()) {
            throw new InvalidInput();
        }
        return value.asBoolean();
    }

    private CodebaseRunContext contextOf(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object value = toolContext.getContext().get(CodebaseRunContext.METADATA_KEY);
        return value instanceof CodebaseRunContext context ? context : null;
    }

    private String failure(String reason) {
        return "{\"status\":\"UNAVAILABLE\",\"reason\":\"" + reason
                + "\",\"sourceKind\":\"CODEBASE\",\"provider\":\"CODEBASE\","
                + "\"operation\":\"" + NAME + "\",\"items\":[]}";
    }

    private String failure(CodebaseException exception) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode result = mapper.createObjectNode();
            result.put("status", "UNAVAILABLE");
            result.put("reason", exception.code().name());
            result.put("sourceKind", "CODEBASE");
            result.put("provider", "CODEBASE");
            result.put("operation", NAME);
            Object missing = exception.details().get("missing");
            if (missing != null) {
                result.set("missing", mapper.valueToTree(missing));
            }
            result.putArray("items");
            return mapper.writeValueAsString(result);
        } catch (Exception ex) {
            return failure(exception.code().name());
        }
    }

    private String quote(String value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "\"\"";
        }
    }

    private static final class InvalidInput extends RuntimeException {
    }
}

package com.yuyu.salmonmind.agent.infrastructure.reactagent;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.salmonmind.agent.api.AgentToolRequestDetail;

import java.util.Locale;
import java.util.Set;

/**
 * 在 ToolInterceptor 边界把模型参数投影为安全展示合同。
 * 只有白名单字段会穿过该边界；解析失败、未知工具和输入越界都自然退化为无详情。
 */
final class ToolRequestDetailProjector {

    private static final int MAX_QUERY_LENGTH = 2_000;
    private static final int MAX_QUERY_SUMMARY_LENGTH = 512;
    private static final int DEFAULT_COUNT = 5;
    private static final Set<String> FRESHNESS_VALUES = Set.of("any", "day", "week", "month", "year");

    private ToolRequestDetailProjector() {
    }

    /**
     * 从公开 Tool Call 参数生成白名单详情；任何解析、类型或边界失败均返回空值。
     *
     * @param toolName 工具定义名，只允许三个现有检索工具
     * @param arguments 框架传入的 JSON 参数，不得被调用方改写后重新落盘
     * @param mapper 仅用于解析 JSON，不参与额外字段推断
     * @return 可安全跨事件边界传递的详情，或 {@code null}
     */
    static AgentToolRequestDetail project(String toolName, String arguments, ObjectMapper mapper) {
        if (!isKnownTool(toolName) || arguments == null || mapper == null) {
            return null;
        }
        try {
            JsonNode root;
            try (JsonParser parser = mapper.getFactory().createParser(arguments)) {
                root = mapper.readTree(parser);
                if (parser.nextToken() != null) {
                    return null;
                }
            }
            if (root == null || !root.isObject()) {
                return null;
            }
            JsonNode queryNode = root.get("query");
            if (queryNode == null || !queryNode.isTextual()) {
                return null;
            }
            String query = queryNode.asText();
            if (query.isBlank() || query.length() > MAX_QUERY_LENGTH) {
                return null;
            }
            String normalized = normalize(query);
            if (normalized.isEmpty()) {
                return null;
            }
            BoundedSummary summary = bound(normalized, MAX_QUERY_SUMMARY_LENGTH);
            if ("search_local_knowledge".equals(toolName)) {
                return new AgentToolRequestDetail(summary.text(), summary.truncated(),
                        null, false, null, false);
            }

            boolean freshnessDefaulted = !root.has("freshness");
            String freshness = freshnessDefaulted ? "any" : normalizedFreshness(root.get("freshness"));
            if (freshness == null) {
                return null;
            }
            boolean countDefaulted = !root.has("count");
            Integer count = countDefaulted ? DEFAULT_COUNT : integralCount(root.get("count"));
            if (count == null) {
                return null;
            }
            return new AgentToolRequestDetail(summary.text(), summary.truncated(),
                    freshness, freshnessDefaulted, count, countDefaulted);
        } catch (Exception ignored) {
            // 参数属于不可信输入；展示边界失败时不回退到原始 JSON。
            return null;
        }
    }

    private static boolean isKnownTool(String toolName) {
        return "search_local_knowledge".equals(toolName)
                || "search_web_bocha".equals(toolName)
                || "search_web_searchapi".equals(toolName);
    }

    private static String normalizedFreshness(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String value = node.asText().strip().toLowerCase(Locale.ROOT);
        return FRESHNESS_VALUES.contains(value) ? value : null;
    }

    private static Integer integralCount(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
            return null;
        }
        int value = node.intValue();
        return value >= 1 && value <= 10 ? value : null;
    }

    /** 把控制字符和连续空白归一化为一个空格，避免把不可见内容写入事件或历史。 */
    private static String normalize(String value) {
        StringBuilder result = new StringBuilder(value.length());
        boolean pendingSpace = false;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isISOControl(codePoint) || Character.isWhitespace(codePoint)) {
                if (!result.isEmpty()) {
                    pendingSpace = true;
                }
                continue;
            }
            if (pendingSpace) {
                result.append(' ');
                pendingSpace = false;
            }
            result.appendCodePoint(codePoint);
        }
        return result.toString().strip();
    }

    private static BoundedSummary bound(String value, int maximumCodePoints) {
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints <= maximumCodePoints) {
            return new BoundedSummary(value, false);
        }
        int end = value.offsetByCodePoints(0, maximumCodePoints);
        return new BoundedSummary(value.substring(0, end), true);
    }

    private record BoundedSummary(String text, boolean truncated) {
    }
}

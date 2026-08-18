package com.yuyu.salmonmind.agent.infrastructure.reactagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyu.salmonmind.agent.api.AgentCitation;
import com.yuyu.salmonmind.agent.api.AgentLocalCitation;
import com.yuyu.salmonmind.agent.api.AgentWebCitation;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一个 Agent Run 独有的来源注册表。工具结果在进入模型前登记来源并获得 L/W 标记，
 * 注册表不写入 Redis 或 JSONL；流结束时只按最终正文中的精确标记取出 Citation。
 * 结果超出字符或累计 token 预算时只保留完整 item，未存活的本轮来源不会成为 Citation。
 */
final class RunSourceRegistry {

    static final String METADATA_KEY = "salmon:agent:source-registry";
    private static final Pattern REFERENCE = Pattern.compile(
            "(?<![A-Za-z0-9_])\\[(L|W)([1-9][0-9]*)](?![A-Za-z0-9_])");

    private final ObjectMapper mapper;
    private final Map<String, AgentCitation> citations = new LinkedHashMap<>();
    private final Map<String, String> identities = new HashMap<>();
    private int localSequence;
    private int webSequence;

    RunSourceRegistry(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 登记并按完整 item 控制 source-bearing 结果大小。普通测试工具返回 null，继续沿用
     * 原有字符边界；生产来源工具始终得到合法 JSON，超限时从尾部删除完整 item。
     */
    synchronized Decoration decorate(String result, int maxChars) {
        return decorate(result, maxChars, Long.MAX_VALUE);
    }

    /**
     * 登记来源并同时执行单结果字符上限与当前 Run 剩余 token 上限。两种上限都只能删除
     * 尾部完整 item；如果连不带 item 的 envelope 也放不进剩余预算，调用方会返回有界
     * 工具失败，刚登记但未存活的来源在本方法末尾被撤销。
     */
    synchronized Decoration decorate(String result, int maxChars, long maxTokens) {
        if (result == null || result.isBlank()) {
            return null;
        }
        JsonNode parsed;
        try {
            parsed = mapper.readTree(result);
        } catch (Exception ex) {
            return null;
        }
        if (parsed == null || !parsed.isObject() || !parsed.path("sourceKind").isTextual()) {
            return null;
        }
        ObjectNode envelope = (ObjectNode) parsed.deepCopy();
        ArrayNode items = envelope.withArray("items");
        String kind = envelope.path("sourceKind").asText();
        String provider = text(envelope, "provider");
        Set<String> newlyRegistered = new HashSet<>();
        ArrayNode decoratedItems = mapper.createArrayNode();
        for (JsonNode item : items) {
            ObjectNode decorated = item != null && item.isObject()
                    ? (ObjectNode) item.deepCopy() : null;
            if (decorated == null) {
                continue;
            }
            Registration registration = "LOCAL".equals(kind)
                    ? registerLocal(decorated)
                    : "WEB".equals(kind) ? registerWeb(provider, decorated) : null;
            if (registration == null) {
                continue;
            }
            decorated.put("referenceId", registration.referenceId());
            if (registration.newlyRegistered()) {
                newlyRegistered.add(registration.referenceId());
            }
            decoratedItems.add(decorated);
        }
        envelope.set("items", decoratedItems);
        boolean truncated = false;
        while ((serializedLength(envelope) > maxChars
                || ToolLifecycleInterceptor.estimateToolResultTokens(serialize(envelope)) > maxTokens)
                && decoratedItems.size() > 0) {
            decoratedItems.remove(decoratedItems.size() - 1);
            truncated = true;
        }
        Set<String> survivingReferences = new HashSet<>();
        for (JsonNode item : decoratedItems) {
            String referenceId = text(item, "referenceId");
            if (referenceId != null) {
                survivingReferences.add(referenceId);
            }
        }
        for (String referenceId : newlyRegistered) {
            if (!survivingReferences.contains(referenceId)) {
                citations.remove(referenceId);
                identities.values().removeIf(referenceId::equals);
            }
        }
        if (truncated) {
            envelope.put("truncated", true);
        }
        String serialized = serialize(envelope);
        long estimatedTokens = ToolLifecycleInterceptor.estimateToolResultTokens(serialized);
        return new Decoration(
                serialized,
                provider,
                decoratedItems.size(),
                truncated,
                "DEGRADED".equals(envelope.path("status").asText()),
                estimatedTokens,
                estimatedTokens <= maxTokens);
    }

    /** 从最终完整回答中按首次出现顺序核对精确 L/W 标记，未知 ID 不产生 Citation。 */
    synchronized List<AgentCitation> citationsFor(String answer) {
        if (answer == null || answer.isEmpty()) {
            return List.of();
        }
        Set<String> references = new LinkedHashSet<>();
        Matcher matcher = REFERENCE.matcher(answer);
        while (matcher.find()) {
            references.add(matcher.group(1) + matcher.group(2));
        }
        return references.stream()
                .map(citations::get)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private Registration registerLocal(ObjectNode item) {
        UUID evidenceId = uuid(item, "evidenceId");
        UUID revisionId = uuid(item, "revisionId");
        if (evidenceId == null || revisionId == null
                || blank(item, "documentName") || blank(item, "location")) {
            return null;
        }
        String identity = "LOCAL:" + evidenceId;
        String existing = identities.get(identity);
        if (existing != null) {
            return new Registration(existing, false);
        }
        String referenceId = "L" + (++localSequence);
        identities.put(identity, referenceId);
        citations.put(referenceId, new AgentLocalCitation(referenceId, evidenceId, revisionId,
                text(item, "documentName"), text(item, "location")));
        return new Registration(referenceId, true);
    }

    private Registration registerWeb(String provider, ObjectNode item) {
        String title = text(item, "title");
        String url = normalizeUrl(text(item, "url"));
        String site = text(item, "site");
        String retrievedAtText = text(item, "retrievedAt");
        if (provider == null || provider.isBlank() || title == null || title.isBlank()
                || url == null || site == null || site.isBlank() || retrievedAtText == null) {
            return null;
        }
        Instant retrievedAt;
        try {
            retrievedAt = Instant.parse(retrievedAtText);
        } catch (RuntimeException ex) {
            return null;
        }
        String identity = "WEB:" + provider + ":" + url;
        String existing = identities.get(identity);
        if (existing != null) {
            return new Registration(existing, false);
        }
        String referenceId = "W" + (++webSequence);
        identities.put(identity, referenceId);
        citations.put(referenceId, new AgentWebCitation(referenceId, provider, title, url, site,
                text(item, "dateLabel"), retrievedAt));
        return new Registration(referenceId, true);
    }

    private int serializedLength(ObjectNode envelope) {
        return serialize(envelope).length();
    }

    private String serialize(ObjectNode envelope) {
        try {
            return mapper.writeValueAsString(envelope);
        } catch (Exception ex) {
            return "{\"status\":\"UNAVAILABLE\",\"reason\":\"INVALID_RESPONSE\","
                    + "\"sourceKind\":\"UNKNOWN\",\"items\":[]}";
        }
    }

    private static String normalizeUrl(String value) {
        if (value == null || value.isBlank() || value.chars().anyMatch(Character::isISOControl)) {
            return null;
        }
        try {
            URI uri = new URI(value.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || uri.getRawUserInfo() != null
                    || !("http".equalsIgnoreCase(scheme)
                    || "https".equalsIgnoreCase(scheme))) {
                return null;
            }
            int port = uri.getPort();
            if (("http".equalsIgnoreCase(scheme) && port == 80)
                    || ("https".equalsIgnoreCase(scheme) && port == 443)) {
                port = -1;
            }
            StringBuilder normalized = new StringBuilder()
                    .append(scheme.toLowerCase(Locale.ROOT)).append("://")
                    .append(host.toLowerCase(Locale.ROOT));
            if (port >= 0) {
                normalized.append(':').append(port);
            }
            if (uri.getRawPath() != null && !uri.getRawPath().isEmpty()) {
                normalized.append(uri.getRawPath());
            } else {
                normalized.append('/');
            }
            if (uri.getRawQuery() != null) {
                normalized.append('?').append(uri.getRawQuery());
            }
            return normalized.toString();
        } catch (URISyntaxException ex) {
            return null;
        }
    }

    private static boolean blank(JsonNode node, String field) {
        String value = text(node, field);
        return value == null || value.isBlank();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static UUID uuid(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    record Decoration(
            String result,
            String provider,
            int sourceCount,
            boolean truncated,
            boolean degraded,
            long estimatedTokens,
            boolean withinTokenBudget
    ) {
    }

    private record Registration(String referenceId, boolean newlyRegistered) {
    }
}

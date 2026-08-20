package com.yuyu.salmonmind.agent.infrastructure.reactagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyu.salmonmind.agent.api.AgentCitation;
import com.yuyu.salmonmind.agent.api.AgentLocalCitation;
import com.yuyu.salmonmind.agent.api.AgentLocalRetrievedSource;
import com.yuyu.salmonmind.agent.api.AgentRetrievedSource;
import com.yuyu.salmonmind.agent.api.AgentToolOutcomeDetail;
import com.yuyu.salmonmind.agent.api.AgentWebCitation;
import com.yuyu.salmonmind.agent.api.AgentWebRetrievedSource;

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
 * 结果超出字符预算时只保留完整 item，未存活的本轮来源不会成为 Citation；输入 token
 * 由 RunContextMeter 在模型调用前统一计量，这里不再维护跨工具的累计 token Gate。
 */
final class RunSourceRegistry {

    static final String METADATA_KEY = "salmon:agent:source-registry";
    static final int MAX_RETRIEVED_SOURCES = 32;
    static final int MAX_SOURCE_EXCERPT_CHARS = 800;
    private static final Pattern REFERENCE = Pattern.compile(
            "(?<![A-Za-z0-9_])\\[(L|W)([1-9][0-9]*)](?![A-Za-z0-9_])");
    private static final Set<String> STABLE_REASON_CODES = Set.of(
            "NONE", "NO_READY_DOCUMENTS", "COMPLETE", "NO_MATCH", "VECTOR_UNAVAILABLE",
            "RERANK_UNAVAILABLE", "INDEX_UNAVAILABLE", "READY_SCOPE_TOO_LARGE", "INVALID_QUERY",
            "RETRIEVAL_UNAVAILABLE", "TOOL_BUDGET_EXCEEDED", "NOT_CONFIGURED", "AUTH_FAILED",
            "RATE_LIMITED", "TIMEOUT", "PROVIDER_FAILED", "INVALID_RESPONSE", "USER_DISABLED",
            "TOOL_CALL_BUDGET_EXCEEDED", "TOOL_CONTEXT_BUDGET_EXCEEDED",
            "RESOLVED", "ITEM_LIMIT", "RESPONSE_LIMIT", "SCAN_LIMIT", "CANDIDATE_LIMIT",
            "INVALID_ABSOLUTE_PATH", "PATH_NOT_DIRECTORY", "PATH_NOT_READABLE", "NOT_GIT_REPOSITORY",
            "BARE_REPOSITORY_NOT_SUPPORTED", "SENSITIVE_FILE_DENIED", "UNSUPPORTED_TEXT_FILE",
            "CODEBASE_UNAVAILABLE", "REPOSITORY_NOT_SELECTED", "REPOSITORY_SELECTION_REQUIRED",
            "MULTIPLE_REPOSITORIES_NOT_SUPPORTED", "REFERENCE_NOT_FOUND",
            "PATH_NOT_FOUND", "PATH_OUTSIDE_REPOSITORY", "REPOSITORY_NOT_FOUND",
            "REPOSITORY_UNAVAILABLE", "GIT_NOT_AVAILABLE", "GIT_QUERY_FAILED",
            "GIT_QUERY_TIMEOUT", "CODEBASE_DATA_CORRUPTED", "CODEBASE_DATA_UNAVAILABLE",
            "CODEBASE_INTERNAL_ERROR", "CODEBASE_ACCESS_DISABLED", "DRAFT_STAGED",
            "CALL_CHAIN_NOT_FOUND", "CALL_CHAIN_DELETED", "CALL_CHAIN_NAME_INVALID",
            "CALL_CHAIN_DRAFT_INVALID", "CALL_CHAIN_EVIDENCE_INSUFFICIENT",
            "CALL_CHAIN_PENDING_NOT_FOUND", "CALL_CHAIN_REPOSITORY_CHANGED",
            "CALL_CHAIN_REVISION_UPDATE_REQUIRED", "CALL_CHAIN_DATA_ROOT_CONFLICT",
            "CALL_CHAIN_IDENTITY_CONFLICT", "CALL_CHAIN_MATCH_AMBIGUOUS",
            "CODEBASE_DISCOVERY_BUDGET_RESERVED");

    private final ObjectMapper mapper;
    private final Map<String, AgentCitation> citations = new LinkedHashMap<>();
    private final Map<String, AgentRetrievedSource> sources = new LinkedHashMap<>();
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
        return decorate(result, maxChars, null);
    }

    /**
     * 为生产拦截器登记来源。toolCallId 只用于冻结首次召回位置，不进入 Tool Result。
     */
    synchronized Decoration decorate(String result, int maxChars, String toolCallId) {
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
        AgentToolOutcomeDetail.ResultStatus resultStatus = resultStatus(envelope);
        String stableReasonCode = stableReasonCode(text(envelope, "reason"));
        if ("CODEBASE".equals(kind)) {
            return decorateCodebase(envelope, maxChars, resultStatus, stableReasonCode);
        }
        Set<String> newlyRegistered = new LinkedHashSet<>();
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
        boolean truncated = envelope.path("truncated").asBoolean(false);
        while ((serializedLength(envelope) > maxChars || sourceLimitExceeded(decoratedItems, newlyRegistered))
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
                sources.remove(referenceId);
                identities.values().removeIf(referenceId::equals);
            }
        }
        int resultPosition = 0;
        for (JsonNode item : decoratedItems) {
            resultPosition++;
            String referenceId = text(item, "referenceId");
            if (referenceId == null || !newlyRegistered.contains(referenceId)) {
                continue;
            }
            AgentRetrievedSource source = sources.get(referenceId);
            if (source != null && source.originToolCallId() == null && source.resultPosition() == null) {
                sources.put(referenceId, withOrigin(source, toolCallId, resultPosition));
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
                resultStatus == AgentToolOutcomeDetail.ResultStatus.DEGRADED,
                estimatedTokens,
                resultStatus,
                stableReasonCode,
                Set.copyOf(newlyRegistered));
    }

    private Decoration decorateCodebase(
            ObjectNode envelope,
            int maxChars,
            AgentToolOutcomeDetail.ResultStatus resultStatus,
            String stableReasonCode
    ) {
        ArrayNode originalItems = envelope.withArray("items");
        ArrayNode boundedItems = mapper.createArrayNode();
        for (JsonNode item : originalItems) {
            if (item != null && item.isObject()) {
                boundedItems.add(item.deepCopy());
            }
        }
        envelope.set("items", boundedItems);
        boolean truncated = envelope.path("truncated").asBoolean(false);
        while (serializedLength(envelope) > maxChars
                && boundedItems.size() > 0) {
            boundedItems.remove(boundedItems.size() - 1);
            truncated = true;
        }
        updateReadCoverage(envelope, boundedItems, truncated);
        envelope.put("resultCount", boundedItems.size());
        JsonNode coverageNode = envelope.get("coverage");
        if (coverageNode != null && coverageNode.isObject()) {
            ObjectNode coverage = (ObjectNode) coverageNode;
            coverage.put("resultCount", boundedItems.size());
            coverage.put("truncated", truncated);
            if (truncated && !coverage.has("truncationReason")) {
                coverage.put("truncationReason", "TOOL_RESULT_LIMIT");
            }
        }
        if (truncated) {
            envelope.put("truncated", true);
            if (!envelope.has("truncationReason")) {
                envelope.put("truncationReason", "TOOL_RESULT_LIMIT");
            }
        }
        if (!envelope.has("continuation")) {
            envelope.putNull("continuation");
        }
        String serialized = serialize(envelope);
        long estimatedTokens = ToolLifecycleInterceptor.estimateToolResultTokens(serialized);
        return new Decoration(
                serialized,
                "CODEBASE",
                null,
                truncated,
                resultStatus == AgentToolOutcomeDetail.ResultStatus.DEGRADED || truncated,
                estimatedTokens,
                resultStatus,
                stableReasonCode,
                Set.of());
    }

    /** ReadFile 被裁剪后同步真实首尾行，避免模型按原请求范围误判证据完整性。 */
    private void updateReadCoverage(ObjectNode envelope, ArrayNode items, boolean truncated) {
        if (!"read_repository_file".equals(envelope.path("operation").asText())
                || items.isEmpty()) {
            return;
        }
        int startLine = items.get(0).path("line").asInt(-1);
        int endLine = items.get(items.size() - 1).path("line").asInt(-1);
        if (startLine < 1 || endLine < startLine) {
            return;
        }
        envelope.put("startLine", startLine);
        envelope.put("endLine", endLine);
        String path = envelope.path("path").asText(items.get(0).path("path").asText());
        String continuation = truncated && !path.isBlank() ? path + ":" + (endLine + 1) : null;
        if (continuation == null) {
            envelope.putNull("continuation");
        } else {
            envelope.put("continuation", continuation);
        }
        JsonNode coverageNode = envelope.get("coverage");
        if (coverageNode != null && coverageNode.isObject()) {
            ObjectNode coverage = (ObjectNode) coverageNode;
            coverage.put("startLine", startLine);
            coverage.put("endLine", endLine);
            if (continuation == null) {
                coverage.putNull("continuation");
            } else {
                coverage.put("continuation", continuation);
            }
        }
    }

    /**
     * 调用方丢弃本次结果时撤销本次新登记来源；只有真正送入模型的结果才能进入历史来源。
     * 已存在来源不受影响，避免一次失败工具调用抹掉前序调用的来源身份。
     */
    synchronized void rollback(Decoration decoration) {
        if (decoration == null) {
            return;
        }
        for (String referenceId : decoration.newlyRegisteredReferences()) {
            if (sources.remove(referenceId) != null) {
                citations.remove(referenceId);
                identities.values().removeIf(referenceId::equals);
            }
        }
    }

    /** 从最终完整回答中按首次出现顺序核对精确 L/W 标记，未知 ID 不产生 Citation。 */
    synchronized List<AgentCitation> citationsFor(String answer) {
        if (answer == null || answer.isEmpty()) {
            return List.of();
        }
        Set<String> references = new LinkedHashSet<>();
        Matcher matcher = REFERENCE.matcher(answer);
        Map<String, String> notes = new LinkedHashMap<>();
        while (matcher.find()) {
            String referenceId = matcher.group(1) + matcher.group(2);
            references.add(referenceId);
            notes.putIfAbsent(referenceId, CitationNoteExtractor.extract(
                    answer, matcher.start(), matcher.end()));
        }
        return references.stream()
                .map(citations::get)
                .filter(java.util.Objects::nonNull)
                .map(citation -> withCitationNote(citation, notes.get(citation.referenceId())))
                .toList();
    }

    /** 返回本轮实际存活并交给模型的全部来源，顺序由首次登记位置决定。 */
    synchronized List<AgentRetrievedSource> retrievedSources() {
        return List.copyOf(sources.values());
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
        sources.put(referenceId, new AgentLocalRetrievedSource(
                referenceId, evidenceId, revisionId, text(item, "documentName"), text(item, "location"),
                retrievedAt(item), "LOCAL_EVIDENCE", sourceExcerpt(item, "text"), null, null, null));
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
        sources.put(referenceId, new AgentWebRetrievedSource(
                referenceId, provider, title, url, site, text(item, "dateLabel"), retrievedAt,
                "WEB_SEARCH_SUMMARY", sourceExcerpt(item, "snippet"), null, null,
                positiveInteger(item, "providerRank")));
        return new Registration(referenceId, true);
    }

    private static AgentToolOutcomeDetail.ResultStatus resultStatus(ObjectNode envelope) {
        String value = text(envelope, "status");
        if (value == null) {
            return null;
        }
        try {
            return AgentToolOutcomeDetail.ResultStatus.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String stableReasonCode(String value) {
        return value != null && STABLE_REASON_CODES.contains(value) ? value : null;
    }

    private static Integer positiveInteger(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            return null;
        }
        int result = value.intValue();
        return result > 0 ? result : null;
    }

    private static AgentRetrievedSource withOrigin(
            AgentRetrievedSource source, String toolCallId, int resultPosition
    ) {
        return switch (source) {
            case AgentLocalRetrievedSource local -> new AgentLocalRetrievedSource(
                    local.referenceId(), local.evidenceId(), local.revisionId(), local.documentName(),
                    local.location(), local.retrievedAt(), local.excerptKind(), local.sourceExcerpt(),
                    toolCallId, resultPosition, null);
            case AgentWebRetrievedSource web -> new AgentWebRetrievedSource(
                    web.referenceId(), web.provider(), web.title(), web.url(), web.site(), web.dateLabel(),
                    web.retrievedAt(), web.excerptKind(), web.sourceExcerpt(), toolCallId, resultPosition,
                    web.providerRank());
        };
    }

    private boolean sourceLimitExceeded(ArrayNode items, Set<String> newlyRegistered) {
        Set<String> survivingNewSources = new HashSet<>();
        for (JsonNode item : items) {
            String referenceId = text(item, "referenceId");
            if (referenceId != null && newlyRegistered.contains(referenceId)) {
                survivingNewSources.add(referenceId);
            }
        }
        return sources.size() - newlyRegistered.size() + survivingNewSources.size() > MAX_RETRIEVED_SOURCES;
    }

    private static AgentCitation withCitationNote(AgentCitation citation, String note) {
        return switch (citation) {
            case AgentLocalCitation local -> new AgentLocalCitation(
                    local.referenceId(), local.evidenceId(), local.revisionId(),
                    local.documentName(), local.location(), note);
            case AgentWebCitation web -> new AgentWebCitation(
                    web.referenceId(), web.provider(), web.title(), web.url(), web.site(),
                    web.dateLabel(), web.retrievedAt(), note);
        };
    }

    private static String sourceExcerpt(JsonNode item, String field) {
        String value = text(item, field);
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.replaceAll("\\p{Cc}", " ")
                .replaceAll("\\s+", " ").trim();
        return CitationNoteExtractor.limit(normalized, MAX_SOURCE_EXCERPT_CHARS);
    }

    private static Instant retrievedAt(JsonNode item) {
        String value = text(item, "retrievedAt");
        if (value != null) {
            try {
                return Instant.parse(value);
            } catch (RuntimeException ignored) {
                // 旧本地 Tool Fixture 没有时间时仍保持来源身份兼容；生产 Tool 会写入时间。
            }
        }
        return Instant.now();
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
            Integer sourceCount,
            boolean truncated,
            boolean degraded,
            long estimatedTokens,
            AgentToolOutcomeDetail.ResultStatus resultStatus,
            String stableReasonCode,
            Set<String> newlyRegisteredReferences
    ) {

        /** 兼容旧测试名称；新事件使用 resultTruncated 语义。 */
        boolean resultTruncated() {
            return truncated;
        }
    }

    private record Registration(String referenceId, boolean newlyRegistered) {
    }
}

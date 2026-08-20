package com.yuyu.salmonmind.agent.infrastructure.reactagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.salmonmind.codebase.api.CallChainEdgeInput;
import com.yuyu.salmonmind.codebase.api.CallChainNodeInput;
import com.yuyu.salmonmind.codebase.api.CallChainPrepareRequest;
import com.yuyu.salmonmind.codebase.api.CodebaseErrorCode;
import com.yuyu.salmonmind.codebase.api.CodebaseException;
import com.yuyu.salmonmind.codebase.api.CodebaseService;
import com.yuyu.salmonmind.codebase.api.RepositoryObservation;
import com.yuyu.salmonmind.codebase.api.RepositoryResolution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 一个 Agent Run 内的临时代码库绑定、最终 ReadFile 证据和调用链草稿。
 *
 * <p>实例只通过 RunnableConfig metadata 传递，生命周期与主 Run 相同，不写入 Conversation、
 * Redis 或全局缓存。ReadFile 证据只在结果经过拦截器裁剪并提交到当前 Run 预算后登记，
 * 因而 prepare 只能使用模型本次真正见过的逐行文本。</p>
 */
final class CodebaseRunContext {

    static final String METADATA_KEY = "salmon:agent:codebase-context";

    private final CodebaseService service;
    private final ObjectMapper mapper;
    private final RepositoryResolution defaultResolution;
    private final RepositoryObservation defaultObservation;
    private final Map<String, Map<Integer, String>> readLines = new LinkedHashMap<>();
    private Binding binding;
    private Draft draft;

    CodebaseRunContext(CodebaseService service) {
        this(service, new ObjectMapper());
    }

    CodebaseRunContext(CodebaseService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
        this.defaultResolution = snapshotActive(service);
        this.defaultObservation = defaultResolution.status() == RepositoryResolution.Status.RESOLVED
                ? observationOf(defaultResolution.repository()) : null;
    }

    synchronized Selection select(String reference) {
        if (service == null) {
            return Selection.resolution(RepositoryResolution.notFound("CODEBASE_UNAVAILABLE"));
        }
        if (reference == null || reference.isBlank()) {
            return bindDefault();
        }
        RepositoryResolution resolution = service.resolveRepository(reference);
        return bindResolution(resolution, "EXPLICIT_REFERENCE", null);
    }

    /** 将 Run 创建时的 Active 快照绑定为默认仓库；不会重新读取当前 catalog。 */
    synchronized Selection bindDefault() {
        return bindResolution(defaultResolution, "ACTIVE_REPOSITORY", defaultObservation);
    }

    private Selection bindResolution(
            RepositoryResolution resolution,
            String selectionSource,
            RepositoryObservation snapshotObservation
    ) {
        if (resolution == null) {
            return Selection.resolution(RepositoryResolution.notFound("CODEBASE_UNAVAILABLE"));
        }
        if (resolution.status() != RepositoryResolution.Status.RESOLVED) {
            return Selection.resolution(resolution);
        }
        RepositoryResolution.ResolvedRepository resolved = resolution.repository();
        if (binding != null && !binding.repositoryId().equals(resolved.id())) {
            return Selection.conflict();
        }
        if (binding == null) {
            binding = new Binding(resolved.id(), resolved.name(), selectionSource,
                    snapshotObservation == null ? observationOf(resolved) : snapshotObservation);
        }
        return Selection.bound(resolution, binding);
    }

    private static RepositoryResolution snapshotActive(CodebaseService service) {
        if (service == null) {
            return RepositoryResolution.notFound("CODEBASE_UNAVAILABLE");
        }
        try {
            RepositoryResolution resolution = service.resolveRepository(null);
            return resolution == null
                    ? RepositoryResolution.notFound("CODEBASE_UNAVAILABLE") : resolution;
        } catch (RuntimeException ex) {
            return RepositoryResolution.notFound("CODEBASE_UNAVAILABLE");
        }
    }

    private static RepositoryObservation observationOf(RepositoryResolution.ResolvedRepository resolved) {
        if (resolved == null) {
            return null;
        }
        return new RepositoryObservation(
                resolved.branch(), resolved.head(), resolved.dirty(),
                resolved.head() == null, false, false,
                0, 0, 0, 0, Instant.now());
    }

    /**
     * 登记已经通过结果大小边界的 ReadFile 实际逐行结果；truncated=true 只表示还有续读，
     * 已返回且连续的行仍然是本次 Run 可用的证据。
     *
     * @param resultJson 拦截器最终返回给模型的 JSON
     */
    synchronized void registerReadFileResult(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            return;
        }
        try {
            JsonNode root = mapper.readTree(resultJson);
            if (root == null || !root.isObject()
                    || !"CODEBASE".equals(root.path("sourceKind").asText())
                    || !"read_repository_file".equals(root.path("operation").asText())
                    || !("SUCCESS".equals(root.path("status").asText())
                    || "DEGRADED".equals(root.path("status").asText()))) {
                return;
            }
            String path = text(root, "path");
            int startLine = positive(root, "startLine");
            int endLine = positive(root, "endLine");
            JsonNode items = root.get("items");
            if (path == null || endLine < startLine || items == null || !items.isArray()
                    || items.isEmpty()) {
                return;
            }
            Map<Integer, String> validated = new LinkedHashMap<>();
            for (int index = 0; index < items.size(); index++) {
                JsonNode item = items.get(index);
                if (item == null || !item.isObject()
                        || !path.equals(text(item, "path"))
                        || item.path("line").asInt(-1) < startLine
                        || item.path("line").asInt(-1) > endLine
                        || !item.has("text") || !item.get("text").isTextual()) {
                    return;
                }
                int line = item.path("line").asInt(-1);
                if (validated.put(line, item.get("text").asText()) != null) {
                    return;
                }
            }
            int actualStart = validated.keySet().iterator().next();
            int actualEnd = actualStart;
            for (int line : validated.keySet()) {
                if (line != actualEnd && line != actualEnd + 1) {
                    return;
                }
                actualEnd = line;
            }
            if (actualEnd - actualStart + 1 != validated.size()) {
                return;
            }
            Map<Integer, String> lines = readLines.computeIfAbsent(
                    normalizePath(path), ignored -> new LinkedHashMap<>());
            lines.putAll(validated);
        } catch (Exception ignored) {
            // 工具结果不是调用链证据时保持普通工具语义，不让一次坏结果终止 Agent。
        }
    }

    /** 一 Run 一草稿；新草稿替换旧草稿，不合并模型可能已经过时的图。 */
    synchronized StageSummary stage(String name, List<DraftNode> nodes, List<DraftEdge> edges) {
        return stage(name, nodes, edges, false);
    }

    synchronized StageSummary stage(
            String name, List<DraftNode> nodes, List<DraftEdge> edges, boolean allowUserNameOverride
    ) {
        if (binding == null) {
            throw failure(CodebaseErrorCode.REPOSITORY_NOT_FOUND, "尚未选择本地仓库");
        }
        validateGraph(name, nodes, edges);
        List<Map<String, Object>> missing = new ArrayList<>();
        for (DraftNode node : nodes) {
            String source = sourceFor(node.path(), node.startLine(), node.endLine());
            if (source == null || source.isBlank()) {
                missing.add(Map.of(
                        "key", node.key(), "path", node.path(),
                        "startLine", node.startLine(), "endLine", node.endLine()));
            }
        }
        if (!missing.isEmpty()) {
            throw new CodebaseException(CodebaseErrorCode.CALL_CHAIN_EVIDENCE_INSUFFICIENT,
                    "调用链节点没有被本次 Run 的完整 ReadFile 证据覆盖",
                    Map.of("missing", List.copyOf(missing)));
        }
        draft = new Draft(name.trim(), List.copyOf(nodes), List.copyOf(edges), allowUserNameOverride);
        return new StageSummary(draft.name(), draft.nodes().size(), draft.edges().size());
    }

    /** 把 Run-local 草稿和最终证据转换成 codebase prepare 请求；没有有效草稿时返回 null。 */
    synchronized CallChainPrepareRequest prepareRequest(UUID conversationId, UUID answerEntryId) {
        if (binding == null || draft == null || conversationId == null || answerEntryId == null) {
            return null;
        }
        List<CallChainNodeInput> nodes = new ArrayList<>();
        for (DraftNode node : draft.nodes()) {
            String source = sourceFor(node.path(), node.startLine(), node.endLine());
            if (source == null || source.isBlank()) {
                return null;
            }
            nodes.add(new CallChainNodeInput(
                    node.key(), node.language(), node.qualifiedSymbol(), node.signature(),
                    node.path(), node.startLine(), node.endLine(), node.summary(), sha256(source)));
        }
        return new CallChainPrepareRequest(
                binding.repositoryId(), binding.observation(), draft.name(), nodes,
                draft.edges().stream().map(edge -> new CallChainEdgeInput(edge.from(), edge.to())).toList(),
                conversationId, answerEntryId, draft.allowUserNameOverride());
    }

    synchronized Binding binding() {
        return binding;
    }

    private String sourceFor(String path, int startLine, int endLine) {
        Map<Integer, String> lines = readLines.get(normalizePath(path));
        if (lines == null) {
            return null;
        }
        List<String> selected = new ArrayList<>();
        for (int line = startLine; line <= endLine; line++) {
            if (!lines.containsKey(line)) {
                return null;
            }
            selected.add(lines.get(line));
        }
        return String.join("\n", selected);
    }

    private void validateGraph(String name, List<DraftNode> nodes, List<DraftEdge> edges) {
        if (name == null || name.isBlank() || name.trim().length() > 120
                || name.contains("\n") || name.contains("\r")
                || nodes == null || nodes.size() < 2 || nodes.size() > 12
                || edges == null || edges.isEmpty() || edges.size() > 24) {
            throw failure(CodebaseErrorCode.CALL_CHAIN_DRAFT_INVALID, "调用链草稿不合法");
        }
        Set<String> keys = new HashSet<>();
        for (DraftNode node : nodes) {
            if (node == null || blankOrLong(node.key(), 80) || !keys.add(node.key().trim())
                    || blankOrLong(node.language(), 40) || blankOrLong(node.qualifiedSymbol(), 500)
                    || blankOrLong(node.signature(), 2_000) || invalidPath(node.path())
                    || node.startLine() < 1 || node.endLine() < node.startLine()
                    || node.endLine() - node.startLine() + 1 > 500
                    || node.summary() == null || node.summary().trim().length() > 500) {
                throw failure(CodebaseErrorCode.CALL_CHAIN_DRAFT_INVALID, "调用链节点字段不合法");
            }
        }
        Set<String> edgeKeys = new HashSet<>();
        for (DraftEdge edge : edges) {
            if (edge == null || edge.from() == null || edge.to() == null
                    || !keys.contains(edge.from()) || !keys.contains(edge.to())
                    || !edgeKeys.add(edge.from() + "\u0000" + edge.to())) {
                throw failure(CodebaseErrorCode.CALL_CHAIN_DRAFT_INVALID, "调用链边不合法");
            }
        }
    }

    private static boolean invalidPath(String raw) {
        if (blankOrLong(raw, 512)) {
            return true;
        }
        String path = normalizePath(raw);
        return path.isBlank() || path.startsWith("/") || path.startsWith("../")
                || path.equals("..") || path.contains(":") || path.contains("\u0000");
    }

    private static String normalizePath(String raw) {
        if (raw == null) {
            return "";
        }
        String path = raw.trim().replace('\\', '/');
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        return path;
    }

    private static boolean blankOrLong(String value, int maximum) {
        return value == null || value.isBlank() || value.trim().length() > maximum
                || value.indexOf('\u0000') >= 0;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static int positive(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.canConvertToInt() && value.asInt() > 0 ? value.asInt() : -1;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte item : digest) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private static CodebaseException failure(CodebaseErrorCode code, String message) {
        return new CodebaseException(code, message);
    }

    record Binding(UUID repositoryId, String repositoryName, String selectionSource,
                   RepositoryObservation observation) {
    }

    record StageSummary(String name, int nodeCount, int edgeCount) {
    }

    record DraftNode(String key, String language, String qualifiedSymbol, String signature,
                     String path, int startLine, int endLine, String summary) {
    }

    record DraftEdge(String from, String to) {
    }

    private record Draft(String name, List<DraftNode> nodes, List<DraftEdge> edges,
                         boolean allowUserNameOverride) {
    }

    record Selection(
            RepositoryResolution resolution,
            Binding binding,
            boolean multipleRepositories
    ) {
        static Selection resolution(RepositoryResolution resolution) {
            return new Selection(resolution, null, false);
        }

        static Selection bound(RepositoryResolution resolution, Binding binding) {
            return new Selection(resolution, binding, false);
        }

        static Selection conflict() {
            return new Selection(RepositoryResolution.notFound(
                    "MULTIPLE_REPOSITORIES_NOT_SUPPORTED"), null, true);
        }
    }
}

package com.yuyu.salmonmind.agent.infrastructure.reactagent;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyu.salmonmind.codebase.api.CodebaseErrorCode;
import com.yuyu.salmonmind.codebase.api.CodebaseException;
import com.yuyu.salmonmind.codebase.api.CodebaseService;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.ChangedPath;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.DirectoryEntry;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.DiffScope;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.EvidenceMetadata;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.GitBlameLine;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.GitBlameResult;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.GitDiffResult;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.GitLogEntry;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.GitLogResult;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.GitShowResult;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.GitStatusEntry;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.GitStatusResult;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.GlobResult;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.GrepMatch;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.GrepResult;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.ListDirectoryResult;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.ReadFileResult;
import com.yuyu.salmonmind.codebase.api.RepositoryResolution;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Agent 侧的代码库 Tool Adapter。
 *
 * <p>每个实例只负责一个清晰的模型 Tool 定义，参数校验、Run Binding、稳定错误和结果
 * 投影共享同一实现；路径、敏感文件、Git ref 和输出范围仍由 {@code codebase::api}
 * 负责。模型永远看不到 Repository ID、绝对工作目录或任意命令入口。</p>
 */
final class CodebaseToolCallback implements ParallelSafeToolCallback {

    static final int MAX_REFERENCE_LENGTH = 2_000;
    static final int MAX_PATH_LENGTH = 512;
    static final int MAX_REF_LENGTH = 512;
    static final int MAX_DIFF_PATHS = 20;

    private static final String SOURCE_KIND = "CODEBASE";
    private static final String PROVIDER = "CODEBASE";

    private final ObjectMapper mapper;
    private final CodebaseService codebase;
    private final RepositoryEvidenceService evidence;
    private final Operation operation;

    CodebaseToolCallback(
            ObjectMapper mapper,
            CodebaseService codebase,
            RepositoryEvidenceService evidence,
            Operation operation
    ) {
        this.mapper = mapper;
        this.codebase = codebase;
        this.evidence = evidence;
        this.operation = operation;
    }

    /** 生产 Agent 固定注册的一个选择 Tool 和九个只读 Evidence Tool。 */
    static List<ToolCallback> productionTools(
            ObjectMapper mapper,
            CodebaseService codebase,
            RepositoryEvidenceService evidence
    ) {
        return List.of(
                new CodebaseToolCallback(mapper, codebase, evidence, Operation.SELECT),
                new CodebaseToolCallback(mapper, codebase, evidence, Operation.LIST),
                new CodebaseToolCallback(mapper, codebase, evidence, Operation.GLOB),
                new CodebaseToolCallback(mapper, codebase, evidence, Operation.GREP),
                new CodebaseToolCallback(mapper, codebase, evidence, Operation.READ),
                new CodebaseToolCallback(mapper, codebase, evidence, Operation.STATUS),
                new CodebaseToolCallback(mapper, codebase, evidence, Operation.DIFF),
                new CodebaseToolCallback(mapper, codebase, evidence, Operation.LOG),
                new CodebaseToolCallback(mapper, codebase, evidence, Operation.SHOW),
                new CodebaseToolCallback(mapper, codebase, evidence, Operation.BLAME));
    }

    static boolean isCodebaseToolName(String toolName) {
        if (toolName == null) {
            return false;
        }
        for (Operation value : Operation.values()) {
            if (value.name.equals(toolName)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(operation.name)
                .description(operation.description)
                .inputSchema(operation.schema)
                .build();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        try {
            JsonNode root = parseObject(toolInput, operation.fields);
            return operation == Operation.SELECT
                    ? select(root, toolContext)
                    : query(root, toolContext);
        } catch (InvalidInput ex) {
            return failure(operation.name, CodebaseErrorCode.INVALID_QUERY.name());
        } catch (CodebaseException ex) {
            return failure(operation.name, ex.code().name());
        } catch (RuntimeException ex) {
            return failure(operation.name, "CODEBASE_UNAVAILABLE");
        }
    }

    private String select(JsonNode root, ToolContext toolContext) {
        CodebaseRunContext context = contextOf(toolContext);
        if (context == null) {
            return failure(operation.name, "CODEBASE_UNAVAILABLE");
        }
        String reference = optionalText(root, "reference", MAX_REFERENCE_LENGTH);
        CodebaseRunContext.Selection selection = context.select(reference);
        if (selection.multipleRepositories()) {
            return failure(operation.name, "MULTIPLE_REPOSITORIES_NOT_SUPPORTED");
        }
        RepositoryResolution resolution = selection.resolution();
        if (resolution.status() == RepositoryResolution.Status.SELECTION_REQUIRED) {
            return failure(operation.name, resolution.reason(), resolution.candidates(),
                    resolution.candidatesTruncated());
        }
        if (resolution.status() != RepositoryResolution.Status.RESOLVED) {
            return failure(operation.name, resolution.reason());
        }
        return resolved(resolution.repository());
    }

    private String query(JsonNode root, ToolContext toolContext) {
        CodebaseRunContext context = contextOf(toolContext);
        if (context == null || context.binding() == null) {
            return failure(operation.name, "REPOSITORY_NOT_SELECTED");
        }
        CodebaseRunContext.Binding binding = context.binding();
        try {
            return switch (operation) {
                case LIST -> list(root, binding.repositoryId());
                case GLOB -> glob(root, binding.repositoryId());
                case GREP -> grep(root, binding.repositoryId());
                case READ -> read(root, binding.repositoryId());
                case STATUS -> status(binding.repositoryId());
                case DIFF -> diff(root, binding.repositoryId());
                case LOG -> log(root, binding.repositoryId());
                case SHOW -> show(root, binding.repositoryId());
                case BLAME -> blame(root, binding.repositoryId());
                case SELECT -> failure(operation.name, CodebaseErrorCode.INVALID_QUERY.name());
            };
        } catch (CodebaseException ex) {
            return failure(operation.name, ex.code().name());
        }
    }

    private String list(JsonNode root, java.util.UUID repositoryId) {
        String path = optionalText(root, "path", MAX_PATH_LENGTH);
        Integer limit = optionalInteger(root, "limit", 0, 500);
        return writeList(evidence.listDirectory(
                new RepositoryEvidenceService.ListDirectoryQuery(repositoryId, path, limit)));
    }

    private String glob(JsonNode root, java.util.UUID repositoryId) {
        String pattern = requiredText(root, "pattern", MAX_PATH_LENGTH);
        Integer limit = optionalInteger(root, "limit", 0, 500);
        return writeGlob(evidence.glob(new RepositoryEvidenceService.GlobQuery(repositoryId, pattern, limit)));
    }

    private String grep(JsonNode root, java.util.UUID repositoryId) {
        String pattern = requiredText(root, "pattern", MAX_PATH_LENGTH);
        boolean fixedString = optionalBoolean(root, "fixedString", true);
        boolean ignoreCase = optionalBoolean(root, "ignoreCase", false);
        Integer contextLines = optionalInteger(root, "contextLines", 0, 3);
        Integer limit = optionalInteger(root, "limit", 1, 500);
        return writeGrep(evidence.grep(new RepositoryEvidenceService.GrepQuery(
                repositoryId, pattern, fixedString, ignoreCase, contextLines, limit)));
    }

    private String read(JsonNode root, java.util.UUID repositoryId) {
        String path = requiredText(root, "path", MAX_PATH_LENGTH);
        Integer startLine = optionalInteger(root, "startLine", 1, Integer.MAX_VALUE);
        Integer lineCount = optionalInteger(root, "lineCount", 1, 500);
        return writeRead(evidence.readFile(new RepositoryEvidenceService.ReadFileQuery(
                repositoryId, path, startLine, lineCount)));
    }

    private String status(java.util.UUID repositoryId) {
        return writeStatus(evidence.gitStatus(new RepositoryEvidenceService.GitStatusQuery(repositoryId)));
    }

    private String diff(JsonNode root, java.util.UUID repositoryId) {
        String scopeValue = requiredText(root, "scope", 32).toUpperCase(Locale.ROOT);
        DiffScope scope;
        try {
            scope = DiffScope.valueOf(scopeValue);
        } catch (IllegalArgumentException ex) {
            throw new InvalidInput();
        }
        String leftRef = optionalText(root, "leftRef", MAX_REF_LENGTH);
        String rightRef = optionalText(root, "rightRef", MAX_REF_LENGTH);
        List<String> paths = optionalStringArray(root, "paths", MAX_DIFF_PATHS, MAX_PATH_LENGTH);
        if (paths.isEmpty()) {
            throw new InvalidInput();
        }
        return writeDiff(evidence.gitDiff(new RepositoryEvidenceService.GitDiffQuery(
                repositoryId, scope, leftRef, rightRef, paths)));
    }

    private String log(JsonNode root, java.util.UUID repositoryId) {
        String path = optionalText(root, "path", MAX_PATH_LENGTH);
        String ref = optionalText(root, "ref", MAX_REF_LENGTH);
        Integer limit = optionalInteger(root, "limit", 0, 100);
        Integer skip = optionalInteger(root, "skip", 0, 1_000);
        return writeLog(evidence.gitLog(new RepositoryEvidenceService.GitLogQuery(
                repositoryId, path, ref, limit, skip)));
    }

    private String show(JsonNode root, java.util.UUID repositoryId) {
        String ref = requiredText(root, "ref", MAX_REF_LENGTH);
        String path = optionalText(root, "path", MAX_PATH_LENGTH);
        return writeShow(evidence.gitShow(new RepositoryEvidenceService.GitShowQuery(repositoryId, ref, path)));
    }

    private String blame(JsonNode root, java.util.UUID repositoryId) {
        String path = requiredText(root, "path", MAX_PATH_LENGTH);
        String ref = optionalText(root, "ref", MAX_REF_LENGTH);
        Integer startLine = optionalInteger(root, "startLine", 1, Integer.MAX_VALUE);
        Integer lineCount = optionalInteger(root, "lineCount", 1, 400);
        return writeBlame(evidence.gitBlame(new RepositoryEvidenceService.GitBlameQuery(
                repositoryId, path, ref, startLine, lineCount)));
    }

    private String resolved(RepositoryResolution.ResolvedRepository repository) {
        ObjectNode result = envelope("SUCCESS", "RESOLVED", Operation.SELECT.name);
        putRepository(result, repository.name(), repository.branch(), repository.head(), repository.dirty());
        result.put("candidateCount", 1);
        result.put("resultCount", 1);
        result.put("truncated", false);
        result.putNull("truncationReason");
        result.putNull("continuation");
        ObjectNode coverage = result.putObject("coverage");
        coverage.put("summary", "repository-selection");
        coverage.put("candidateCount", 1);
        coverage.put("resultCount", 1);
        coverage.put("truncated", false);
        coverage.putNull("truncationReason");
        coverage.putNull("continuation");
        ObjectNode item = result.withArray("items").addObject();
        item.put("name", repository.name());
        item.put("status", repository.status());
        item.put("accessible", repository.accessible());
        if (repository.branch() != null) {
            item.put("branch", repository.branch());
        }
        if (repository.head() != null) {
            item.put("head", repository.head());
        }
        item.put("dirty", repository.dirty());
        return serialize(result);
    }

    private String writeList(ListDirectoryResult value) {
        ObjectNode result = fromMetadata(value.metadata(), value.entries().size());
        ArrayNode items = result.withArray("items");
        for (DirectoryEntry entry : value.entries()) {
            ObjectNode item = items.addObject();
            item.put("path", entry.path());
            item.put("name", entry.name());
            item.put("directory", entry.directory());
            item.put("ignored", entry.ignored());
        }
        return serialize(result);
    }

    private String writeGlob(GlobResult value) {
        ObjectNode result = fromMetadata(value.metadata(), value.paths().size());
        ArrayNode items = result.withArray("items");
        for (String path : value.paths()) {
            items.addObject().put("path", path);
        }
        return serialize(result);
    }

    private String writeGrep(GrepResult value) {
        ObjectNode result = fromMetadata(value.metadata(), value.matches().size());
        ArrayNode items = result.withArray("items");
        for (GrepMatch match : value.matches()) {
            ObjectNode item = items.addObject();
            item.put("path", match.path());
            item.put("line", match.line());
            item.put("column", match.column());
            item.put("text", match.text());
            writeStrings(item.putArray("contextBefore"), match.contextBefore());
            writeStrings(item.putArray("contextAfter"), match.contextAfter());
        }
        return serialize(result);
    }

    private String writeRead(ReadFileResult value) {
        ObjectNode result = fromMetadata(value.metadata(), lineCount(value.content()));
        result.put("path", value.path());
        result.put("ignored", value.ignored());
        result.put("startLine", value.startLine());
        result.put("endLine", value.endLine());
        ArrayNode items = result.withArray("items");
        if (value.content() != null && !value.content().isEmpty()) {
            String[] lines = value.content().split("\\R", -1);
            for (int index = 0; index < lines.length; index++) {
                ObjectNode item = items.addObject();
                item.put("path", value.path());
                item.put("line", value.startLine() + index);
                item.put("text", lines[index]);
            }
        }
        return serialize(result);
    }

    private String writeStatus(GitStatusResult value) {
        ObjectNode result = fromMetadata(value.metadata(), value.entries().size());
        result.put("branch", value.branch());
        putNullable(result, "head", value.head());
        result.put("unborn", value.unborn());
        result.put("detached", value.detached());
        result.put("shallow", value.shallow());
        result.put("stagedCount", value.stagedCount());
        result.put("unstagedCount", value.unstagedCount());
        result.put("untrackedCount", value.untrackedCount());
        result.put("sensitiveChangedCount", value.sensitiveChangedCount());
        ArrayNode items = result.withArray("items");
        for (GitStatusEntry entry : value.entries()) {
            ObjectNode item = items.addObject();
            item.put("path", entry.path());
            item.put("kind", entry.kind());
        }
        return serialize(result);
    }

    private String writeDiff(GitDiffResult value) {
        ObjectNode result = fromMetadata(value.metadata(), lineCount(value.patch()));
        result.put("scope", value.scope().name());
        putNullable(result, "leftCommit", value.leftCommit());
        putNullable(result, "rightCommit", value.rightCommit());
        writeStrings(result.putArray("paths"), value.paths());
        ArrayNode items = result.withArray("items");
        if (value.patch() != null && !value.patch().isEmpty()) {
            String[] lines = value.patch().split("\\R", -1);
            String currentPath = value.paths().size() == 1 ? value.paths().getFirst() : null;
            for (int index = 0; index < lines.length; index++) {
                currentPath = diffPath(currentPath, lines[index], value.paths());
                ObjectNode item = items.addObject();
                item.put("line", index + 1);
                item.put("text", lines[index]);
                if (currentPath != null) {
                    item.put("path", currentPath);
                }
            }
        }
        return serialize(result);
    }

    private String writeLog(GitLogResult value) {
        ObjectNode result = fromMetadata(value.metadata(), value.entries().size());
        ArrayNode items = result.withArray("items");
        for (GitLogEntry entry : value.entries()) {
            ObjectNode item = items.addObject();
            item.put("commit", entry.commit());
            putInstant(item, "authoredAt", entry.authoredAt());
            item.put("author", entry.author());
            item.put("subject", entry.subject());
        }
        return serialize(result);
    }

    private String writeShow(GitShowResult value) {
        ObjectNode result = fromMetadata(value.metadata(), value.content() == null || value.content().isEmpty()
                ? value.changedPaths().size() : lineCount(value.content()));
        result.put("commit", value.commit());
        putNullable(result, "author", value.author());
        putInstant(result, "authoredAt", value.authoredAt());
        putNullable(result, "subject", value.subject());
        ArrayNode items = result.withArray("items");
        if (value.content() != null && !value.content().isEmpty()) {
            String[] lines = value.content().split("\\R", -1);
            String contentPath = showContentPath(value.metadata());
            for (int index = 0; index < lines.length; index++) {
                ObjectNode item = items.addObject();
                if (contentPath != null) {
                    item.put("path", contentPath);
                }
                item.put("line", index + 1);
                item.put("text", lines[index]);
            }
        } else {
            for (ChangedPath path : value.changedPaths()) {
                ObjectNode item = items.addObject();
                item.put("path", path.path());
                item.put("kind", path.kind());
            }
        }
        return serialize(result);
    }

    private String writeBlame(GitBlameResult value) {
        ObjectNode result = fromMetadata(value.metadata(), value.lines().size());
        result.put("path", value.path());
        ArrayNode items = result.withArray("items");
        for (GitBlameLine line : value.lines()) {
            ObjectNode item = items.addObject();
            item.put("path", value.path());
            item.put("commit", line.commit());
            item.put("author", line.author());
            item.put("line", line.line());
            item.put("text", line.text());
        }
        return serialize(result);
    }

    private ObjectNode fromMetadata(EvidenceMetadata metadata, int itemCount) {
        boolean truncated = metadata != null && metadata.truncated();
        int resultCount = metadata == null ? itemCount : metadata.resultCount();
        String reason = truncated && metadata.truncationReason() != null
                ? metadata.truncationReason() : resultCount == 0 ? "NO_MATCH" : "COMPLETE";
        ObjectNode result = envelope(truncated ? "DEGRADED" : resultCount == 0 ? "EMPTY" : "SUCCESS",
                reason, operation.name);
        if (metadata != null) {
            putRepository(result, metadata.repositoryName(), metadata.branch(), metadata.head(), metadata.dirty());
            result.put("candidateCount", metadata.candidateCount());
            result.put("resultCount", itemCount);
            result.put("truncated", truncated);
            putNullable(result, "truncationReason", metadata.truncationReason());
            putNullable(result, "continuation", metadata.continuation());
            ObjectNode coverage = result.putObject("coverage");
            coverage.put("summary", metadata.querySummary());
            coverage.put("candidateCount", metadata.candidateCount());
            coverage.put("resultCount", itemCount);
            coverage.put("truncated", truncated);
            putNullable(coverage, "truncationReason", metadata.truncationReason());
            putNullable(coverage, "continuation", metadata.continuation());
        }
        return result;
    }

    private ObjectNode envelope(String status, String reason, String operation) {
        ObjectNode result = mapper.createObjectNode();
        result.put("status", status);
        result.put("reason", reason);
        result.put("sourceKind", SOURCE_KIND);
        result.put("provider", PROVIDER);
        result.put("operation", operation);
        result.putArray("items");
        return result;
    }

    private String failure(String operation, String reason) {
        return serialize(failureNode(operation, reason, List.of(), false));
    }

    private String failure(
            String operation,
            String reason,
            List<RepositoryResolution.Candidate> candidates,
            boolean candidatesTruncated
    ) {
        return serialize(failureNode(operation, reason, candidates, candidatesTruncated));
    }

    private ObjectNode failureNode(
            String operation,
            String reason,
            List<RepositoryResolution.Candidate> candidates,
            boolean candidatesTruncated
    ) {
        ObjectNode result = envelope("UNAVAILABLE", reason, operation);
        result.put("candidateCount", candidates.size());
        result.put("resultCount", 0);
        result.put("truncated", candidatesTruncated);
        if (candidatesTruncated) {
            result.put("truncationReason", "CANDIDATE_LIMIT");
        }
        ObjectNode coverage = result.putObject("coverage");
        coverage.put("summary", reason);
        coverage.put("candidateCount", candidates.size());
        coverage.put("resultCount", 0);
        coverage.put("truncated", candidatesTruncated);
        if (candidatesTruncated) {
            coverage.put("truncationReason", "CANDIDATE_LIMIT");
        }
        coverage.putNull("continuation");
        ArrayNode items = result.withArray("items");
        for (RepositoryResolution.Candidate candidate : candidates) {
            ObjectNode item = items.addObject();
            item.put("name", candidate.name());
            item.put("path", candidate.path());
            item.put("accessible", candidate.accessible());
        }
        return result;
    }

    private void putRepository(ObjectNode target, String name, String branch, String head, boolean dirty) {
        putNullable(target, "repositoryName", name);
        putNullable(target, "branch", branch);
        putNullable(target, "head", head);
        target.put("dirty", dirty);
    }

    private void putNullable(ObjectNode target, String field, String value) {
        if (value == null) {
            target.putNull(field);
        } else {
            target.put(field, value);
        }
    }

    private void putInstant(ObjectNode target, String field, Instant value) {
        putNullable(target, field, value == null ? null : value.toString());
    }

    private String diffPath(String currentPath, String line, List<String> paths) {
        for (String path : paths) {
            if (line.equals("diff --git a/" + path + " b/" + path)
                    || line.equals("--- a/" + path)
                    || line.equals("+++ b/" + path)) {
                return path;
            }
        }
        return currentPath;
    }

    private String showContentPath(EvidenceMetadata metadata) {
        if (metadata == null || metadata.querySummary() == null) {
            return null;
        }
        String summary = metadata.querySummary();
        String prefix = "git-show:";
        int commitEnd = summary.indexOf(':', prefix.length());
        if (!summary.startsWith(prefix) || commitEnd < 0 || commitEnd == summary.length() - 1) {
            return null;
        }
        String path = summary.substring(commitEnd + 1);
        return path.isBlank() || "null".equals(path) ? null : path;
    }

    private int lineCount(String value) {
        return value == null || value.isEmpty() ? 0 : value.split("\\R", -1).length;
    }

    private void writeStrings(ArrayNode target, List<String> values) {
        for (String value : values) {
            target.add(value == null ? "" : value);
        }
    }

    private String serialize(ObjectNode result) {
        try {
            return mapper.writeValueAsString(result);
        } catch (Exception ex) {
            // 序列化失败时不能再次调用本方法，避免异常兜底递归；operation 只来自固定枚举。
            return "{\"status\":\"UNAVAILABLE\",\"reason\":\"INVALID_RESPONSE\","
                    + "\"sourceKind\":\"CODEBASE\",\"provider\":\"CODEBASE\","
                    + "\"operation\":\"" + operation.name + "\",\"items\":[]}";
        }
    }

    private CodebaseRunContext contextOf(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object value = toolContext.getContext().get(CodebaseRunContext.METADATA_KEY);
        return value instanceof CodebaseRunContext context ? context : null;
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
            Iterator<String> names = root.fieldNames();
            while (names.hasNext()) {
                if (!fields.contains(names.next())) {
                    throw new InvalidInput();
                }
            }
            return root;
        } catch (InvalidInput ex) {
            throw ex;
        } catch (Exception ex) {
            throw new InvalidInput();
        }
    }

    private String requiredText(JsonNode root, String field, int maxLength) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual()) {
            throw new InvalidInput();
        }
        String text = value.asText();
        if (text.isBlank() || text.length() > maxLength || text.indexOf('\0') >= 0) {
            throw new InvalidInput();
        }
        return text;
    }

    private String optionalText(JsonNode root, String field, int maxLength) {
        JsonNode value = root.get(field);
        if (value == null) {
            return null;
        }
        if (!value.isTextual()) {
            throw new InvalidInput();
        }
        String text = value.asText().trim();
        if (text.length() > maxLength || text.indexOf('\0') >= 0) {
            throw new InvalidInput();
        }
        return text.isBlank() ? null : text;
    }

    private Integer optionalInteger(JsonNode root, String field, int minimum, int maximum) {
        JsonNode value = root.get(field);
        if (value == null) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new InvalidInput();
        }
        int number = value.intValue();
        if (number < minimum || number > maximum) {
            throw new InvalidInput();
        }
        return number;
    }

    private boolean optionalBoolean(JsonNode root, String field, boolean defaultValue) {
        JsonNode value = root.get(field);
        if (value == null) {
            return defaultValue;
        }
        if (!value.isBoolean()) {
            throw new InvalidInput();
        }
        return value.booleanValue();
    }

    private List<String> optionalStringArray(JsonNode root, String field, int maximumItems, int maxLength) {
        JsonNode value = root.get(field);
        if (value == null) {
            return List.of();
        }
        if (!value.isArray() || value.size() > maximumItems) {
            throw new InvalidInput();
        }
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode item : value) {
            if (!item.isTextual()) {
                throw new InvalidInput();
            }
            String path = item.asText().trim();
            if (path.isBlank() || path.length() > maxLength || !seen.add(path)) {
                throw new InvalidInput();
            }
            result.add(path);
        }
        return List.copyOf(result);
    }

    enum Operation {
        SELECT(
                "select_local_repository",
                "选择本地仓库；引用可以是已注册名称、别名、Search Root 直接子目录或绝对路径。只读、结果可能要求用户从候选中选择。",
                Set.of("reference"),
                "{\"type\":\"object\",\"properties\":{\"reference\":{\"type\":\"string\",\"maxLength\":2000}},\"additionalProperties\":false}"),
        LIST(
                "list_repository_directory",
                "先选择仓库后列出仓库相对目录的直接子项；只读，结果可能截断。",
                Set.of("path", "limit"),
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"maxLength\":512},\"limit\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":500}},\"additionalProperties\":false}"),
        GLOB(
                "glob_repository_files",
                "先选择仓库后按受限 Glob 查找文件；只读，结果可能截断。",
                Set.of("pattern", "limit"),
                "{\"type\":\"object\",\"properties\":{\"pattern\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":512},\"limit\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":500}},\"required\":[\"pattern\"],\"additionalProperties\":false}"),
        GREP(
                "grep_repository",
                "先选择仓库后在受控候选文件中搜索文本或 POSIX extended regex；只读，结果可能截断。",
                Set.of("pattern", "fixedString", "ignoreCase", "contextLines", "limit"),
                "{\"type\":\"object\",\"properties\":{\"pattern\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":512},\"fixedString\":{\"type\":\"boolean\"},\"ignoreCase\":{\"type\":\"boolean\"},\"contextLines\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":3},\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":500}},\"required\":[\"pattern\"],\"additionalProperties\":false}"),
        READ(
                "read_repository_file",
                "先选择仓库后按 1-based 行号分页读取允许的 UTF-8 文本；只读，结果可能截断。",
                Set.of("path", "startLine", "lineCount"),
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":512},\"startLine\":{\"type\":\"integer\",\"minimum\":1},\"lineCount\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":500}},\"required\":[\"path\"],\"additionalProperties\":false}"),
        STATUS(
                "git_repository_status",
                "先选择仓库后查看只读 Git 工作树状态；敏感路径只计数不回显。",
                Set.of(),
                "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"),
        DIFF(
                "git_repository_diff",
                "先选择仓库后查看显式范围和路径的只读 Git diff；不接受原始 Git 参数，结果可能截断。",
                Set.of("scope", "leftRef", "rightRef", "paths"),
                "{\"type\":\"object\",\"properties\":{\"scope\":{\"type\":\"string\",\"enum\":[\"WORKTREE\",\"STAGED\",\"COMMITS\"]},\"leftRef\":{\"type\":\"string\",\"maxLength\":512},\"rightRef\":{\"type\":\"string\",\"maxLength\":512},\"paths\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":20,\"items\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":512}}},\"required\":[\"scope\",\"paths\"],\"additionalProperties\":false}"),
        LOG(
                "git_repository_log",
                "先选择仓库后查看有界只读 Git 提交元数据；结果可能截断。",
                Set.of("path", "ref", "limit", "skip"),
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"maxLength\":512},\"ref\":{\"type\":\"string\",\"maxLength\":512},\"limit\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":100},\"skip\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":1000}},\"additionalProperties\":false}"),
        SHOW(
                "git_repository_show",
                "先选择仓库后查看一个已验证提交的只读元数据或显式路径内容；结果可能截断。",
                Set.of("ref", "path"),
                "{\"type\":\"object\",\"properties\":{\"ref\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":512},\"path\":{\"type\":\"string\",\"maxLength\":512}},\"required\":[\"ref\"],\"additionalProperties\":false}"),
        BLAME(
                "git_repository_blame",
                "先选择仓库后查看连续 blame 行；只读，结果只是最后修改线索，不等于设计者或责任人。",
                Set.of("path", "ref", "startLine", "lineCount"),
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":512},\"ref\":{\"type\":\"string\",\"maxLength\":512},\"startLine\":{\"type\":\"integer\",\"minimum\":1},\"lineCount\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":400}},\"required\":[\"path\"],\"additionalProperties\":false}");

        private final String name;
        private final String description;
        private final Set<String> fields;
        private final String schema;

        Operation(String name, String description, Set<String> fields, String schema) {
            this.name = name;
            this.description = description;
            this.fields = fields;
            this.schema = schema;
        }
    }

    private static final class InvalidInput extends RuntimeException {
    }
}

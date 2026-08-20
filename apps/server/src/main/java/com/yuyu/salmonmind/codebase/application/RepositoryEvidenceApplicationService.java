package com.yuyu.salmonmind.codebase.application;

import com.yuyu.salmonmind.codebase.api.CodebaseErrorCode;
import com.yuyu.salmonmind.codebase.api.CodebaseException;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.ChangedPath;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.DirectoryEntry;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.DiffScope;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.EvidenceMetadata;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.GitBlameLine;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.GitBlameQuery;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.GitBlameResult;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.GitDiffQuery;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.GitDiffResult;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.GitLogEntry;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.GitLogQuery;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.GitLogResult;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.GitShowQuery;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.GitShowResult;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.GitStatusEntry;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.GitStatusQuery;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.GitStatusResult;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.GlobQuery;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.GlobResult;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.GrepMatch;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.GrepQuery;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.GrepResult;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.ListDirectoryQuery;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.ListDirectoryResult;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.ReadFileQuery;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.ReadFileResult;
import com.yuyu.salmonmind.codebase.application.port.GitObservation;
import com.yuyu.salmonmind.codebase.application.port.GitProcessPort;
import com.yuyu.salmonmind.codebase.application.port.GitQueryPort;
import com.yuyu.salmonmind.codebase.application.port.RepositoryLocation;
import com.yuyu.salmonmind.codebase.application.port.RepositoryPathPort;
import com.yuyu.salmonmind.codebase.application.port.ResolvedPath;
import com.yuyu.salmonmind.codebase.domain.SensitiveFilePolicy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 文件与 Git 只读 Evidence 的统一入口。
 *
 * <p>每个查询都先重新解析 Repository、确认真实目标并执行 Sensitive File Policy，之后才调用
 * working tree 或固定 Git 命令。结果带有观察状态和覆盖边界；达到硬上限是成功但不完整，而不是空结果。</p>
 */
@Service
public final class RepositoryEvidenceApplicationService implements RepositoryEvidenceService {

    private static final int DEFAULT_LIST_LIMIT = 200;
    private static final int MAX_LIST_LIMIT = 500;
    private static final int DEFAULT_GREP_LIMIT = 200;
    private static final int MAX_GREP_LIMIT = 500;
    private static final int DEFAULT_READ_LINES = 200;
    private static final int MAX_READ_LINES = 500;
    private static final int MAX_PATTERN_LENGTH = 512;
    private static final int MAX_CANDIDATES = 50_000;
    private static final int MAX_GREP_LINE_CHARS = 2_000;
    private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_LINE_CHARS = 32 * 1024;
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final long MAX_SCAN_BYTES = 128L * 1024 * 1024;
    private static final Duration MAX_SCAN_TIME = Duration.ofSeconds(10);

    private final RepositoryCatalogService catalog;
    private final RepositoryPathPort paths;
    private final SensitiveFilePolicy sensitiveFiles;
    private final GitQueryPort git;

    public RepositoryEvidenceApplicationService(
            RepositoryCatalogService catalog,
            RepositoryPathPort paths,
            SensitiveFilePolicy sensitiveFiles,
            GitQueryPort git
    ) {
        this.catalog = catalog;
        this.paths = paths;
        this.sensitiveFiles = sensitiveFiles;
        this.git = git;
    }

    @Override
    public ListDirectoryResult listDirectory(ListDirectoryQuery query) {
        requireQuery(query);
        RepositoryLocation location = location(query.repositoryId());
        String requested = normalizeLogical(query.relativePath());
        ensureLogicalAllowed(location, requested);
        ResolvedPath directory = paths.resolveTarget(location, requested, true);
        sensitiveFiles.ensureAllowed(location.root(), directory.requestedPath(), directory.real());
        CandidateSet candidates = candidates(location);
        String parent = paths.logicalPath(location.root(), directory.real());
        List<DirectoryEntry> entries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Candidate candidate : candidates.items()) {
            String remainder;
            if (parent.isBlank()) {
                remainder = candidate.path();
            } else if (candidate.path().startsWith(parent + "/")) {
                remainder = candidate.path().substring(parent.length() + 1);
            } else {
                continue;
            }
            int slash = remainder.indexOf('/');
            String directName = slash < 0 ? remainder : remainder.substring(0, slash);
            if (directName.isBlank()) {
                continue;
            }
            String path = parent.isBlank() ? directName : parent + "/" + directName;
            if (seen.add(path)) {
                entries.add(new DirectoryEntry(path, directName, slash >= 0 || Files.isDirectory(candidate.real()), false));
            }
        }
        entries.sort(Comparator.comparing(DirectoryEntry::path));
        int limit = bounded(query.limit(), DEFAULT_LIST_LIMIT, MAX_LIST_LIMIT);
        boolean truncated = candidates.truncated() || entries.size() > limit;
        List<DirectoryEntry> page = entries.stream().limit(limit).toList();
        String continuation = candidates.continuation() != null
                ? candidates.continuation()
                : truncated && !page.isEmpty() ? page.get(page.size() - 1).path() : null;
        return new ListDirectoryResult(metadata(location, "list:" + parent, true, false,
                candidates.items().size(), page.size(), truncated,
                candidates.truncated() ? "CANDIDATE_LIMIT" : entries.size() > limit ? "ITEM_LIMIT" : null,
                continuation), page);
    }

    @Override
    public GlobResult glob(GlobQuery query) {
        requireQuery(query);
        String pattern = requirePattern(query.pattern(), "Glob pattern");
        Pattern matcher = compileGlob(pattern);
        RepositoryLocation location = location(query.repositoryId());
        CandidateSet candidates = candidates(location);
        List<String> matches = candidates.items().stream()
                .map(Candidate::path)
                .filter(path -> matcher.matcher(path).matches())
                .sorted()
                .toList();
        int limit = bounded(query.limit(), DEFAULT_LIST_LIMIT, MAX_LIST_LIMIT);
        boolean truncated = candidates.truncated() || matches.size() > limit;
        List<String> page = matches.stream().limit(limit).toList();
        String continuation = candidates.continuation() != null
                ? candidates.continuation()
                : truncated && !page.isEmpty() ? page.get(page.size() - 1) : null;
        return new GlobResult(metadata(location, "glob:" + pattern, true, false,
                candidates.items().size(), page.size(), truncated,
                candidates.truncated() ? "CANDIDATE_LIMIT" : matches.size() > limit ? "ITEM_LIMIT" : null,
                continuation), page);
    }

    @Override
    public GrepResult grep(GrepQuery query) {
        requireQuery(query);
        String expression = requirePattern(query.pattern(), "Grep pattern");
        Pattern regex = null;
        if (!query.fixedString()) {
            rejectNonPosixRegex(expression);
            try {
                regex = Pattern.compile(expression, query.ignoreCase() ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0);
            } catch (PatternSyntaxException ex) {
                throw new CodebaseException(CodebaseErrorCode.INVALID_QUERY, "Grep pattern 不合法");
            }
        }
        RepositoryLocation location = location(query.repositoryId());
        CandidateSet candidates = candidates(location);
        int limit = positiveBounded(query.limit(), DEFAULT_GREP_LIMIT, MAX_GREP_LIMIT);
        int context = bounded(query.contextLines(), 0, 3);
        List<GrepMatch> matches = new ArrayList<>();
        long started = System.nanoTime();
        long scannedBytes = 0;
        boolean truncated = candidates.truncated();
        String continuation = candidates.continuation();
        outer:
        for (Candidate candidate : candidates.items()) {
            if (Duration.ofNanos(System.nanoTime() - started).compareTo(MAX_SCAN_TIME) >= 0
                    || scannedBytes >= MAX_SCAN_BYTES) {
                truncated = true;
                continuation = candidate.path();
                break;
            }
            long size;
            try {
                size = Files.size(candidate.real());
            } catch (IOException ex) {
                continue;
            }
            if (size > MAX_FILE_BYTES) {
                truncated = true;
                continuation = candidate.path();
                continue;
            }
            if (scannedBytes + size > MAX_SCAN_BYTES) {
                truncated = true;
                continuation = candidate.path();
                break;
            }
            scannedBytes += size;
            TextFile text = readText(candidate.real());
            if (text == null) {
                continue;
            }
            String[] lines = text.lines();
            for (int index = 0; index < lines.length; index++) {
                String line = lines[index];
                int column = query.fixedString()
                        ? indexOf(line, expression, query.ignoreCase())
                        : regex == null ? -1 : firstRegexColumn(regex, line);
                if (column < 0) {
                    continue;
                }
                List<String> before = new ArrayList<>();
                List<String> after = new ArrayList<>();
                for (int contextIndex = Math.max(0, index - context); contextIndex < index; contextIndex++) {
                    before.add(limitText(lines[contextIndex], MAX_GREP_LINE_CHARS));
                }
                for (int contextIndex = index + 1; contextIndex <= Math.min(lines.length - 1, index + context); contextIndex++) {
                    after.add(limitText(lines[contextIndex], MAX_GREP_LINE_CHARS));
                }
                matches.add(new GrepMatch(candidate.path(), index + 1, column + 1,
                        limitText(line, MAX_GREP_LINE_CHARS), before, after));
                if (matches.size() >= limit) {
                    truncated = true;
                    continuation = candidate.path() + ":" + (index + 1);
                    break outer;
                }
            }
        }
        if (!truncated && matches.size() < limit && candidates.items().size() == MAX_CANDIDATES) {
            truncated = true;
        }
        return new GrepResult(metadata(location, "grep:" + expression, true, false,
                candidates.items().size(), matches.size(), truncated,
                truncated ? "SCAN_LIMIT" : null, continuation), matches);
    }

    @Override
    public ReadFileResult readFile(ReadFileQuery query) {
        requireQuery(query);
        RepositoryLocation location = location(query.repositoryId());
        String requested = normalizeLogical(query.relativePath());
        ensureLogicalAllowed(location, requested);
        ResolvedPath target = paths.resolveTarget(location, requested, false);
        sensitiveFiles.ensureAllowed(location.root(), target.requestedPath(), target.real());
        TextFile text = readText(target.real());
        if (text == null) {
            throw new CodebaseException(CodebaseErrorCode.UNSUPPORTED_TEXT_FILE,
                    "目标不是受支持的 UTF-8 文本文件");
        }
        int start = positiveOrDefault(query.startLine(), 1);
        int requestedLines = positiveBounded(query.lineCount(), DEFAULT_READ_LINES, MAX_READ_LINES);
        if (start > text.lines().length + 1) {
            throw new CodebaseException(CodebaseErrorCode.INVALID_QUERY, "起始行号超出文件范围");
        }
        int endExclusive = Math.min(text.lines().length, start - 1 + requestedLines);
        List<String> selected = new ArrayList<>();
        int byteCount = 0;
        boolean truncated = query.lineCount() != null && query.lineCount() > MAX_READ_LINES;
        for (int index = start - 1; index < endExclusive; index++) {
            String line = text.lines()[index];
            int lineBytes = line.getBytes(StandardCharsets.UTF_8).length + (selected.isEmpty() ? 0 : 1);
            if (byteCount + lineBytes > MAX_RESPONSE_BYTES) {
                truncated = true;
                break;
            }
            selected.add(line);
            byteCount += lineBytes;
        }
        if (endExclusive < text.lines().length && selected.size() == requestedLines) {
            truncated = true;
        }
        String content = String.join("\n", selected);
        String continuation = truncated ? target.requestedPath() + ":" + (start + selected.size()) : null;
        boolean ignored = isIgnored(location, target.requestedPath());
        return new ReadFileResult(metadata(location, "read:" + target.requestedPath(), false, ignored,
                1, selected.size(), truncated, truncated ? "RESPONSE_LIMIT" : null, continuation),
                target.requestedPath(), ignored, start, start + selected.size() - 1, content);
    }

    @Override
    public GitStatusResult gitStatus(GitStatusQuery query) {
        requireQuery(query);
        RepositoryLocation location = location(query.repositoryId());
        GitObservation observation = git.observe(location);
        List<GitStatusEntry> entries = observation.items().stream()
                .map(item -> new GitStatusEntry(item.path(), item.kind()))
                .toList();
        return new GitStatusResult(metadata(location, "git-status", true, false,
                        observation.items().size(), entries.size(), observation.items().size() >= 500,
                        observation.items().size() >= 500 ? "ITEM_LIMIT" : null,
                        observation.items().isEmpty() ? null : observation.items().get(observation.items().size() - 1).path()),
                observation.branch(), observation.head(), observation.unborn(), observation.detached(),
                observation.shallow(), observation.stagedCount(), observation.unstagedCount(),
                observation.untrackedCount(), observation.sensitiveChangedCount(), entries);
    }

    @Override
    public GitDiffResult gitDiff(GitDiffQuery query) {
        requireQuery(query);
        if (query.scope() == null || query.paths() == null || query.paths().isEmpty() || query.paths().size() > 20) {
            throw new CodebaseException(CodebaseErrorCode.INVALID_QUERY,
                    "Git diff 必须提供不超过 20 个文件路径");
        }
        RepositoryLocation location = location(query.repositoryId());
        List<String> pathsToQuery = validateGitPaths(location, query.paths());
        String left = null;
        String right = null;
        List<String> arguments = new ArrayList<>();
        arguments.add("diff");
        arguments.add("--no-ext-diff");
        arguments.add("--no-textconv");
        arguments.add("--no-renames");
        arguments.add("--unified=3");
        if (query.scope() == DiffScope.STAGED) {
            arguments.add("--cached");
        } else if (query.scope() == DiffScope.COMMITS) {
            if (query.leftRef() == null || query.rightRef() == null) {
                throw new CodebaseException(CodebaseErrorCode.INVALID_QUERY,
                        "Git commit diff 缺少比较端点");
            }
            left = git.resolveCommit(location, query.leftRef());
            right = git.resolveCommit(location, query.rightRef());
            arguments.add(left);
            arguments.add(right);
        }
        arguments.add("--");
        arguments.addAll(pathsToQuery);
        GitProcessPort.Result result = git.run(location, arguments);
        if (!result.succeeded()) {
            throw new CodebaseException(CodebaseErrorCode.GIT_QUERY_FAILED, "Git diff 查询失败");
        }
        String patch = limitUtf8(result.stdout(), MAX_RESPONSE_BYTES);
        boolean truncated = result.stdoutOverflow()
                || result.stdout().getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES;
        return new GitDiffResult(metadata(location, "git-diff", false, false,
                pathsToQuery.size(), 1, truncated, truncated ? "RESPONSE_LIMIT" : null, null),
                query.scope(), left, right, pathsToQuery, patch);
    }

    @Override
    public GitLogResult gitLog(GitLogQuery query) {
        requireQuery(query);
        RepositoryLocation location = location(query.repositoryId());
        String path = query.path() == null || query.path().isBlank() ? null
                : validateGitPaths(location, List.of(query.path())).getFirst();
        int requestedLimit = bounded(query.limit(), 30, 100);
        int skip = bounded(query.skip(), 0, 1_000);
        String ref = query.ref() == null || query.ref().isBlank() ? null : git.resolveCommit(location, query.ref());
        List<String> arguments = new ArrayList<>(List.of("log", "--no-decorate",
                "--format=%H%x00%aI%x00%an%x00%s%x00", "-n", Integer.toString(requestedLimit + 1),
                "--skip", Integer.toString(skip)));
        if (ref != null) {
            arguments.add(ref);
        }
        if (path != null) {
            arguments.add("--");
            arguments.add(path);
        }
        GitProcessPort.Result result = git.run(location, arguments);
        if (!result.succeeded()) {
            throw new CodebaseException(CodebaseErrorCode.GIT_QUERY_FAILED, "Git log 查询失败");
        }
        List<GitLogEntry> parsed = parseLog(result.stdout());
        boolean truncated = result.stdoutOverflow() || parsed.size() > requestedLimit;
        List<GitLogEntry> page = parsed.stream().limit(requestedLimit).toList();
        return new GitLogResult(metadata(location, "git-log", false, false,
                        page.size(), page.size(), truncated, truncated ? "ITEM_LIMIT" : null,
                        truncated && !page.isEmpty() ? page.get(page.size() - 1).commit() : null), page);
    }

    @Override
    public GitShowResult gitShow(GitShowQuery query) {
        requireQuery(query);
        RepositoryLocation location = location(query.repositoryId());
        String commit = git.resolveCommit(location, query.ref());
        if (query.path() == null || query.path().isBlank()) {
            GitProcessPort.Result header = git.run(location,
                    List.of("show", "--no-patch", "--format=%H%x00%aI%x00%an%x00%s%x00", commit));
            if (!header.succeeded()) {
                throw new CodebaseException(CodebaseErrorCode.GIT_QUERY_FAILED, "Git show 查询失败");
            }
            GitLogEntry metadata = parseLog(header.stdout()).stream().findFirst()
                    .orElseThrow(() -> new CodebaseException(CodebaseErrorCode.GIT_QUERY_FAILED, "Git show 查询失败"));
            GitProcessPort.Result changed = git.run(location,
                    List.of("diff-tree", "--root", "--no-commit-id", "--name-status", "-r", "--no-renames", commit, "--"));
            if (!changed.succeeded()) {
                throw new CodebaseException(CodebaseErrorCode.GIT_QUERY_FAILED, "Git show 查询失败");
            }
            List<ChangedPath> paths = parseChangedPaths(location, changed.stdout());
            return new GitShowResult(metadata(location, "git-show:" + commit, false, false,
                            paths.size(), paths.size(), changed.stdoutOverflow(),
                            changed.stdoutOverflow() ? "RESPONSE_LIMIT" : null, null), commit, metadata.author(),
                    metadata.authoredAt(), metadata.subject(), paths, null);
        }
        String path = validateGitPaths(location, List.of(query.path())).getFirst();
        GitProcessPort.Result result = git.run(location,
                List.of("show", "--no-ext-diff", "--no-textconv", "--no-renames", commit, "--", path));
        if (!result.succeeded()) {
            throw new CodebaseException(CodebaseErrorCode.GIT_QUERY_FAILED, "Git show 查询失败");
        }
        boolean truncated = result.stdoutOverflow()
                || result.stdout().getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES;
        return new GitShowResult(metadata(location, "git-show:" + commit + ":" + path, false, false,
                        1, 1, truncated, truncated ? "RESPONSE_LIMIT" : null, null), commit, null,
                null, null, List.of(), limitUtf8(result.stdout(), MAX_RESPONSE_BYTES));
    }

    @Override
    public GitBlameResult gitBlame(GitBlameQuery query) {
        requireQuery(query);
        RepositoryLocation location = location(query.repositoryId());
        String path = validateGitPaths(location, List.of(query.path())).getFirst();
        ResolvedPath target = paths.resolveTarget(location, path, false);
        sensitiveFiles.ensureAllowed(location.root(), path, target.real());
        TextFile text = readText(target.real());
        if (text == null) {
            throw new CodebaseException(CodebaseErrorCode.UNSUPPORTED_TEXT_FILE,
                    "目标不是受支持的文本文件");
        }
        int start = positiveOrDefault(query.startLine(), 1);
        int count = bounded(query.lineCount(), 200, 400);
        if (start > text.lines().length) {
            throw new CodebaseException(CodebaseErrorCode.INVALID_QUERY, "Blame 行范围不合法");
        }
        int end = Math.min(start + count - 1, text.lines().length);
        List<String> arguments = new ArrayList<>(List.of("blame", "--line-porcelain", "-L",
                start + "," + end));
        if (query.ref() != null && !query.ref().isBlank()) {
            arguments.add(git.resolveCommit(location, query.ref()));
        }
        arguments.add("--");
        arguments.add(path);
        GitProcessPort.Result result = git.run(location, arguments);
        if (!result.succeeded()) {
            throw new CodebaseException(CodebaseErrorCode.GIT_QUERY_FAILED, "Git blame 查询失败");
        }
        List<GitBlameLine> lines = parseBlame(result.stdout());
        boolean truncated = result.stdoutOverflow() || lines.size() >= count && end < text.lines().length;
        return new GitBlameResult(metadata(location, "git-blame:" + path, false, false,
                lines.size(), lines.size(), truncated,
                result.stdoutOverflow() ? "RESPONSE_LIMIT" : truncated ? "LINE_LIMIT" : null,
                null), path, lines);
    }

    private CandidateSet candidates(RepositoryLocation location) {
        GitProcessPort.Result result = git.run(location,
                List.of("ls-files", "--cached", "--others", "--exclude-standard", "-z"));
        if (!result.succeeded()) {
            throw new CodebaseException(CodebaseErrorCode.GIT_QUERY_FAILED,
                    "文件候选集合查询失败");
        }
        List<Candidate> candidates = new ArrayList<>();
        String[] values = result.stdout().split("\\u0000", -1);
        boolean truncated = result.stdoutOverflow();
        String continuation = null;
        int valueCount = result.stdout().endsWith("\u0000") ? values.length : Math.max(0, values.length - 1);
        for (int index = 0; index < valueCount; index++) {
            String value = values[index];
            if (value.isBlank()) {
                continue;
            }
            String path = value.replace('\\', '/');
            if (path.startsWith("/") || path.contains("../") || path.equals("..")) {
                continue;
            }
            Path file = location.root().resolve(path.replace('/', java.io.File.separatorChar)).normalize();
            if (!paths.isWithin(location.root(), file) || !Files.exists(file) || !Files.isRegularFile(file)) {
                continue;
            }
            Path real;
            try {
                real = file.toRealPath();
            } catch (IOException ex) {
                continue;
            }
            if (!paths.isWithin(location.root(), real)
                    || sensitiveFiles.isDenied(location.root(), path, real)) {
                continue;
            }
            candidates.add(new Candidate(path, real));
            if (candidates.size() >= MAX_CANDIDATES) {
                truncated = true;
                continuation = path;
                break;
            }
        }
        if (truncated && continuation == null && !candidates.isEmpty()) {
            continuation = candidates.get(candidates.size() - 1).path();
        }
        candidates.sort(Comparator.comparing(Candidate::path));
        return new CandidateSet(candidates, truncated, continuation);
    }

    private EvidenceMetadata metadata(
            RepositoryLocation location,
            String summary,
            boolean includeUntracked,
            boolean includeIgnored,
            int candidateCount,
            int resultCount,
            boolean truncated,
            String truncationReason,
            String continuation
    ) {
        GitObservation observation = git.observe(location);
        return new EvidenceMetadata(location.id(), location.registration().name(), summary,
                observation.branch(), observation.head(), observation.dirty(), includeUntracked,
                includeIgnored, candidateCount, resultCount, truncated, truncationReason, continuation);
    }

    private RepositoryLocation location(UUID repositoryId) {
        return catalog.resolveRegistered(repositoryId);
    }

    private void ensureLogicalAllowed(RepositoryLocation location, String path) {
        sensitiveFiles.ensureAllowed(location.root(), path, null);
    }

    private List<String> validateGitPaths(RepositoryLocation location, List<String> rawPaths) {
        List<String> result = new ArrayList<>();
        for (String raw : rawPaths) {
            if (raw == null || raw.isBlank() || raw.length() > MAX_PATTERN_LENGTH) {
                throw new CodebaseException(CodebaseErrorCode.INVALID_QUERY, "Git 路径参数不合法");
            }
            String normalized = normalizeLogical(raw);
            Path path;
            try {
                path = Path.of(normalized.replace('/', java.io.File.separatorChar)).normalize();
            } catch (RuntimeException ex) {
                throw new CodebaseException(CodebaseErrorCode.INVALID_QUERY, "Git 路径参数不合法");
            }
            if (path.isAbsolute() || !paths.isWithin(location.root(), location.root().resolve(path).normalize())) {
                throw new CodebaseException(CodebaseErrorCode.PATH_OUTSIDE_REPOSITORY,
                        "路径超出仓库边界");
            }
            if (sensitiveFiles.isDenied(location.root(), normalized, null)) {
                throw new CodebaseException(CodebaseErrorCode.SENSITIVE_FILE_DENIED,
                        "请求访问的文件受到保护");
            }
            Path current = location.root().resolve(path).normalize();
            if (Files.exists(current)) {
                try {
                    Path real = current.toRealPath();
                    if (!paths.isWithin(location.root(), real)) {
                        throw new CodebaseException(CodebaseErrorCode.PATH_OUTSIDE_REPOSITORY,
                                "路径超出仓库边界");
                    }
                    sensitiveFiles.ensureAllowed(location.root(), normalized, real);
                } catch (IOException ex) {
                    throw new CodebaseException(CodebaseErrorCode.PATH_NOT_FOUND, "路径不存在", ex);
                }
            }
            result.add(paths.logicalPath(location.root(), location.root().resolve(path).normalize()));
        }
        return List.copyOf(result);
    }

    private boolean isIgnored(RepositoryLocation location, String path) {
        GitProcessPort.Result result = git.run(location, List.of("check-ignore", "--quiet", "--", path));
        return result.exitCode() == 0;
    }

    private TextFile readText(Path file) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_FILE_BYTES) {
                return null;
            }
            byte[] bytes = Files.readAllBytes(file);
            if (containsNul(bytes)) {
                return null;
            }
            String content;
            try {
                content = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException ex) {
                return null;
            }
            String[] lines = content.split("\\R", -1);
            for (String line : lines) {
                if (line.length() > MAX_LINE_CHARS) {
                    return null;
                }
            }
            return new TextFile(lines);
        } catch (IOException ex) {
            return null;
        }
    }

    private List<GitLogEntry> parseLog(String output) {
        String[] fields = output.split("\\u0000", -1);
        List<GitLogEntry> entries = new ArrayList<>();
        for (int index = 0; index + 3 < fields.length; index += 4) {
            String commit = fields[index].trim();
            if (commit.isBlank() || !commit.matches("[0-9a-fA-F]{40,64}")) {
                continue;
            }
            Instant authoredAt = parseInstant(fields[index + 1].trim());
            if (authoredAt == null) {
                continue;
            }
            entries.add(new GitLogEntry(commit, authoredAt, fields[index + 2].trim(),
                    fields[index + 3].replaceAll("^[\\r\\n]+|[\\r\\n]+$", "")));
        }
        return entries;
    }

    private List<ChangedPath> parseChangedPaths(RepositoryLocation location, String output) {
        List<ChangedPath> result = new ArrayList<>();
        for (String line : output.split("\\R")) {
            int tab = line.indexOf('\t');
            if (tab < 0 || tab + 1 >= line.length()) {
                continue;
            }
            String path = line.substring(tab + 1).replace('\\', '/');
            if (!sensitiveFiles.isDenied(location.root(), path, null)) {
                result.add(new ChangedPath(path, line.substring(0, tab)));
            }
        }
        return result;
    }

    private List<GitBlameLine> parseBlame(String output) {
        List<GitBlameLine> result = new ArrayList<>();
        String commit = null;
        String author = null;
        int line = 0;
        for (String value : output.split("\\R", -1)) {
            Matcher header = Pattern.compile("^([0-9a-fA-F]{40,64}) \\d+ (\\d+)(?: \\d+)?$").matcher(value);
            if (header.matches()) {
                commit = header.group(1);
                line = Integer.parseInt(header.group(2));
                author = null;
            } else if (value.startsWith("author ")) {
                author = value.substring("author ".length());
            } else if (value.startsWith("\t") && commit != null) {
                result.add(new GitBlameLine(commit, author, line, limitText(value.substring(1), MAX_GREP_LINE_CHARS)));
            }
        }
        return result;
    }

    private Pattern compileGlob(String pattern) {
        if (pattern.startsWith("/") || pattern.contains("..") || containsUnsupportedGlobSyntax(pattern)) {
            throw new CodebaseException(CodebaseErrorCode.INVALID_QUERY, "Glob pattern 不合法");
        }
        StringBuilder regex = new StringBuilder("^");
        for (int index = 0; index < pattern.length(); index++) {
            char current = pattern.charAt(index);
            if (current == '*' && index + 1 < pattern.length() && pattern.charAt(index + 1) == '*') {
                index++;
                if (index + 1 < pattern.length() && pattern.charAt(index + 1) == '/') {
                    index++;
                    regex.append("(?:.*/)?");
                } else {
                    regex.append(".*");
                }
            } else if (current == '*') {
                regex.append("[^/]*");
            } else if (current == '?') {
                regex.append("[^/]");
            } else {
                regex.append(Pattern.quote(String.valueOf(current)));
            }
        }
        return Pattern.compile(regex.append('$').toString());
    }

    private boolean containsUnsupportedGlobSyntax(String pattern) {
        // 当前只实现 *、** 和 ?；未实现方言必须报错，不能把用户意图当成普通文件名。
        return pattern.indexOf('{') >= 0 || pattern.indexOf('}') >= 0
                || pattern.indexOf('[') >= 0 || pattern.indexOf(']') >= 0
                || pattern.indexOf('\\') >= 0;
    }

    private String requirePattern(String value, String label) {
        if (value == null || value.isBlank() || value.length() > MAX_PATTERN_LENGTH || value.indexOf('\0') >= 0) {
            throw new CodebaseException(CodebaseErrorCode.INVALID_QUERY, label + " 不合法");
        }
        return value;
    }

    private void rejectNonPosixRegex(String expression) {
        if (expression.contains("(?") || expression.matches(".*\\\\[1-9].*")) {
            throw new CodebaseException(CodebaseErrorCode.INVALID_QUERY,
                    "只支持 POSIX extended regex");
        }
    }

    private String normalizeLogical(String value) {
        return sensitiveFiles.normalizeLogical(value == null ? "" : value).replaceAll("/+", "/");
    }

    private int bounded(Integer requested, int defaultValue, int maximum) {
        if (requested == null) {
            return defaultValue;
        }
        if (requested < 0) {
            throw new CodebaseException(CodebaseErrorCode.INVALID_QUERY, "查询上限不合法");
        }
        return Math.min(requested, maximum);
    }

    private int positiveBounded(Integer requested, int defaultValue, int maximum) {
        int value = bounded(requested, defaultValue, maximum);
        if (value < 1) {
            throw new CodebaseException(CodebaseErrorCode.INVALID_QUERY, "查询上限不合法");
        }
        return value;
    }

    private int positiveOrDefault(Integer requested, int defaultValue) {
        if (requested == null) {
            return defaultValue;
        }
        if (requested < 1) {
            throw new CodebaseException(CodebaseErrorCode.INVALID_QUERY, "行号不合法");
        }
        return requested;
    }

    private void requireQuery(Object query) {
        if (query == null) {
            throw new CodebaseException(CodebaseErrorCode.INVALID_QUERY, "查询参数不能为空");
        }
    }

    private int indexOf(String line, String expression, boolean ignoreCase) {
        if (!ignoreCase) {
            return line.indexOf(expression);
        }
        return line.toLowerCase(Locale.ROOT).indexOf(expression.toLowerCase(Locale.ROOT));
    }

    private int firstRegexColumn(Pattern pattern, String line) {
        Matcher matcher = pattern.matcher(line);
        return matcher.find() ? matcher.start() : -1;
    }

    private String limitText(String value, int maxChars) {
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars);
    }

    private String limitUtf8(String value, int maxBytes) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return value;
        }
        return new String(bytes, 0, maxBytes, StandardCharsets.UTF_8);
    }

    private boolean containsNul(byte[] bytes) {
        for (byte value : bytes) {
            if (value == 0) {
                return true;
            }
        }
        return false;
    }

    private Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(value).toInstant();
            } catch (DateTimeParseException ignoredAgain) {
                return null;
            }
        }
    }

    private record Candidate(String path, Path real) {
    }

    private record CandidateSet(List<Candidate> items, boolean truncated, String continuation) {
    }

    private record TextFile(String[] lines) {
    }
}

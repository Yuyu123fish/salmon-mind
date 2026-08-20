package com.yuyu.salmonmind.codebase.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 后续 Agent 接入使用的只读 Repository Evidence Named Interface。
 *
 * <p>所有查询以 Repository ID 和受限参数表达；实现负责真实路径边界、Sensitive File Policy、
 * ignore 语义、输出上限和 Git 进程安全。Stage 01 不把此接口注册成模型 Tool，也不提供对应 HTTP 端点。</p>
 */
public interface RepositoryEvidenceService {

    /** 在仓库相对目录列出直接子项；结果受数量、敏感路径和真实路径边界约束。 */
    ListDirectoryResult listDirectory(ListDirectoryQuery query);

    /** 按仓库相对 Glob 返回候选文件；结果只来自受控 working-tree 候选集合。 */
    GlobResult glob(GlobQuery query);

    /** 在受控候选文件中执行有界文本搜索；不允许通过 pattern 进入任意命令。 */
    GrepResult grep(GrepQuery query);

    /** 读取单个允许的 UTF-8 文本文件；行号从 1 开始，ignored 文件仍需通过敏感策略。 */
    ReadFileResult readFile(ReadFileQuery query);

    /** 返回当前分支、HEAD、dirty 及敏感变更计数，不回显被拒绝的路径。 */
    GitStatusResult gitStatus(GitStatusQuery query);

    /** 只比较显式路径与已验证 ref，工作树、索引、refs 和对象库保持不变。 */
    GitDiffResult gitDiff(GitDiffQuery query);

    /** 返回有界提交元数据；skip/limit 只控制查询窗口，不改变仓库。 */
    GitLogResult gitLog(GitLogQuery query);

    /** 返回单个已验证提交的元数据，或显式允许路径的有界文本内容。 */
    GitShowResult gitShow(GitShowQuery query);

    /** 返回单个允许文本文件的连续 blame 行，仅作为历史定位线索。 */
    GitBlameResult gitBlame(GitBlameQuery query);

    /** 列出仓库相对目录的直接子项；空路径表示工作树根。 */
    record ListDirectoryQuery(UUID repositoryId, String relativePath, Integer limit) {
    }

    /** 按仓库相对 Glob 模式查找 tracked 与普通未跟踪文件。 */
    record GlobQuery(UUID repositoryId, String pattern, Integer limit) {
    }

    /** 在默认候选集合中执行 fixed string 或 POSIX extended regex 搜索。 */
    record GrepQuery(
            UUID repositoryId,
            String pattern,
            boolean fixedString,
            boolean ignoreCase,
            Integer contextLines,
            Integer limit
    ) {
    }

    /** 读取仓库内 UTF-8 文本的 1-based 行区间；ignore 不等于权限拒绝。 */
    record ReadFileQuery(UUID repositoryId, String relativePath, Integer startLine, Integer lineCount) {
    }

    /** 读取实时 Git 工作树观察状态。 */
    record GitStatusQuery(UUID repositoryId) {
    }

    enum DiffScope {
        WORKTREE,
        STAGED,
        COMMITS
    }

    /** 只比较显式允许路径的工作树、暂存区或两个已解析提交。 */
    record GitDiffQuery(
            UUID repositoryId,
            DiffScope scope,
            String leftRef,
            String rightRef,
            List<String> paths
    ) {
        public GitDiffQuery {
            paths = paths == null ? List.of() : List.copyOf(paths);
        }
    }

    /** 查询提交元数据，不返回 patch；ref 会在服务端解析为提交 ID。 */
    record GitLogQuery(UUID repositoryId, String path, String ref, Integer limit, Integer skip) {
    }

    /** 查询一个提交的元数据，或一个显式路径的有界内容。 */
    record GitShowQuery(UUID repositoryId, String ref, String path) {
    }

    /** 查询单个允许文本文件的连续 blame 行，仅作为历史定位线索。 */
    record GitBlameQuery(UUID repositoryId, String path, String ref, Integer startLine, Integer lineCount) {
    }

    /** Evidence 的安全摘要；不向调用方暴露 Server 物理路径。 */
    record EvidenceMetadata(
            UUID repositoryId,
            String repositoryName,
            String querySummary,
            String branch,
            String head,
            boolean dirty,
            boolean includeUntracked,
            boolean includeIgnored,
            int candidateCount,
            int resultCount,
            boolean truncated,
            String truncationReason,
            String continuation
    ) {
    }

    /** 一个仓库相对目录项；List/Glob/Grep 不返回 ignored 项。 */
    record DirectoryEntry(String path, String name, boolean directory, boolean ignored) {
    }

    /** List 查询的有界结果。 */
    record ListDirectoryResult(EvidenceMetadata metadata, List<DirectoryEntry> entries) {
        public ListDirectoryResult {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    /** Glob 查询的有界仓库相对路径结果。 */
    record GlobResult(EvidenceMetadata metadata, List<String> paths) {
        public GlobResult {
            paths = paths == null ? List.of() : List.copyOf(paths);
        }
    }

    /** 一条包含可选上下文的搜索命中。 */
    record GrepMatch(
            String path,
            int line,
            int column,
            String text,
            List<String> contextBefore,
            List<String> contextAfter
    ) {
        public GrepMatch {
            contextBefore = contextBefore == null ? List.of() : List.copyOf(contextBefore);
            contextAfter = contextAfter == null ? List.of() : List.copyOf(contextAfter);
        }
    }

    /** Grep 查询的有界结果。 */
    record GrepResult(EvidenceMetadata metadata, List<GrepMatch> matches) {
        public GrepResult {
            matches = matches == null ? List.of() : List.copyOf(matches);
        }
    }

    /** ReadFile 的稳定行号与内容结果。 */
    record ReadFileResult(
            EvidenceMetadata metadata,
            String path,
            boolean ignored,
            int startLine,
            int endLine,
            String content
    ) {
    }

    /** 非敏感 Git 状态路径；敏感路径只计数不回显。 */
    record GitStatusEntry(String path, String kind) {
    }

    /** GitStatus 的结构化实时观察结果。 */
    record GitStatusResult(
            EvidenceMetadata metadata,
            String branch,
            String head,
            boolean unborn,
            boolean detached,
            boolean shallow,
            int stagedCount,
            int unstagedCount,
            int untrackedCount,
            int sensitiveChangedCount,
            List<GitStatusEntry> entries
    ) {
        public GitStatusResult {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    /** GitDiff 的显式路径、有界 patch 与提交边界。 */
    record GitDiffResult(
            EvidenceMetadata metadata,
            DiffScope scope,
            String leftCommit,
            String rightCommit,
            List<String> paths,
            String patch
    ) {
        public GitDiffResult {
            paths = paths == null ? List.of() : List.copyOf(paths);
        }
    }

    /** 一条提交历史定位信息，不代表设计者或责任人。 */
    record GitLogEntry(String commit, Instant authoredAt, String author, String subject) {
    }

    /** GitLog 的有界提交元数据结果。 */
    record GitLogResult(EvidenceMetadata metadata, List<GitLogEntry> entries) {
        public GitLogResult {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    /** GitShow 元数据中的非敏感变更路径。 */
    record ChangedPath(String path, String kind) {
    }

    /** GitShow 的提交元数据、变更路径或显式路径内容。 */
    record GitShowResult(
            EvidenceMetadata metadata,
            String commit,
            String author,
            Instant authoredAt,
            String subject,
            List<ChangedPath> changedPaths,
            String content
    ) {
        public GitShowResult {
            changedPaths = changedPaths == null ? List.of() : List.copyOf(changedPaths);
        }
    }

    /** 一条 blame 历史线索，不将 author 解释为设计者或责任人。 */
    record GitBlameLine(String commit, String author, int line, String text) {
    }

    /** GitBlame 的有界连续行结果。 */
    record GitBlameResult(EvidenceMetadata metadata, String path, List<GitBlameLine> lines) {
        public GitBlameResult {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }
}

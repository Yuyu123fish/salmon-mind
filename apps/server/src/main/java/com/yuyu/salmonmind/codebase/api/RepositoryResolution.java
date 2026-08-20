package com.yuyu.salmonmind.codebase.api;

import java.util.List;
import java.util.UUID;

/** 一次面向 Agent 的仓库精确解析结果。 */
public record RepositoryResolution(
        Status status,
        String reason,
        ResolvedRepository repository,
        List<Candidate> candidates,
        boolean candidatesTruncated
) {
    public RepositoryResolution {
        if (status == null) {
            throw new IllegalArgumentException("仓库解析状态不能为空");
        }
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        if (status == Status.RESOLVED && repository == null) {
            throw new IllegalArgumentException("成功解析必须携带仓库");
        }
        if (status != Status.RESOLVED && repository != null) {
            throw new IllegalArgumentException("未成功解析不能携带已选仓库");
        }
    }

    public static RepositoryResolution resolved(ResolvedRepository repository) {
        return new RepositoryResolution(Status.RESOLVED, "RESOLVED", repository, List.of(), false);
    }

    public static RepositoryResolution selectionRequired(List<Candidate> candidates, boolean truncated) {
        return new RepositoryResolution(Status.SELECTION_REQUIRED, "REPOSITORY_SELECTION_REQUIRED",
                null, candidates, truncated);
    }

    public static RepositoryResolution notFound(String reason) {
        return new RepositoryResolution(Status.NOT_FOUND, reason, null, List.of(), false);
    }

    public enum Status {
        RESOLVED,
        SELECTION_REQUIRED,
        NOT_FOUND
    }

    /** 已通过 catalog、路径和 Git 观察的仓库；ID 只供 Server 内部绑定使用。
     *
     * @param id 稳定的 Server-owned Repository ID
     * @param name 仓库显示名
     * @param path 规范化后的真实仓库路径，仅供 Server 内部使用
     * @param accessible 当前是否可访问
     * @param status 当前可读性/Git 观察状态
     * @param branch 当前分支或 detached 状态
     * @param head 当前 HEAD；仓库尚无提交时可以为空
     * @param dirty 当前工作树是否有变化
     */
    public record ResolvedRepository(
            UUID id,
            String name,
            String path,
            boolean accessible,
            String status,
            String branch,
            String head,
            boolean dirty
    ) {
    }

    /** 交给用户选择的有界候选，不包含 Repository ID。
     *
     * @param name 候选显示名
     * @param path 规范化路径，帮助用户消歧
     * @param accessible 当前是否可访问
     */
    public record Candidate(String name, String path, boolean accessible) {
    }
}

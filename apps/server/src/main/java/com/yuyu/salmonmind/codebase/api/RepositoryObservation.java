package com.yuyu.salmonmind.codebase.api;

import java.time.Instant;

/**
 * 调用链节点形成时保存的只读 Git/工作树观察。
 *
 * <p>这是证据时点，不是可恢复的 Git 状态；调用链存储不会依据它执行任何 Git 写操作。</p>
 */
public record RepositoryObservation(
        String branch,
        String head,
        boolean dirty,
        boolean unborn,
        boolean detached,
        boolean shallow,
        int stagedCount,
        int unstagedCount,
        int untrackedCount,
        int sensitiveChangedCount,
        Instant observedAt
) {
    public RepositoryObservation {
        if (observedAt == null) {
            observedAt = Instant.now();
        }
        if (stagedCount < 0 || unstagedCount < 0 || untrackedCount < 0 || sensitiveChangedCount < 0) {
            throw new IllegalArgumentException("Git 观察计数不合法");
        }
    }
}

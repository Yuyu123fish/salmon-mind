package com.yuyu.salmonmind.codebase.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 仓库注册资料与实时观察状态的组合视图。
 *
 * <p>路径、名称和 ID 来自 catalog；branch、HEAD 与 dirty 每次读取时重新观察，
 * 因而不可被当作持久化身份或历史快照。</p>
 */
public record RepositoryView(
        UUID id,
        String path,
        String name,
        List<String> aliases,
        boolean registered,
        String status,
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
        String unavailableCode,
        Instant createdAt,
        Instant updatedAt
) {
    public RepositoryView {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }
}

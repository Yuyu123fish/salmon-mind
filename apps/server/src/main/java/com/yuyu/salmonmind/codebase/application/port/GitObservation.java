package com.yuyu.salmonmind.codebase.application.port;

import java.util.List;

/** Git 适配器从只读 status 查询解析出的实时状态，不是 catalog 持久化事实。 */
public record GitObservation(
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
        List<StatusItem> items,
        String unavailableCode
) {
    public GitObservation {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** 已通过敏感路径策略的状态项。 */
    public record StatusItem(String path, String kind) {
    }
}

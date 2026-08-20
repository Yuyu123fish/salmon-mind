package com.yuyu.salmonmind.agent.infrastructure.reactagent;

/**
 * CODEBASE Run 的两段式调用预算。
 *
 * <p>Evidence 总量仍然是 16 次，但前 10 次之后关闭仓库选择与目录发现工具，给
 * ReadFile 与只读 Git 留出最后 6 次。调用链暂存使用独立的一次额度，不会因为目录探索耗尽。</p>
 */
final class CodebaseBudget {

    static final String DISCOVERY_RESERVED = "CODEBASE_DISCOVERY_BUDGET_RESERVED";
    private static final String CALL_LIMIT = "TOOL_CALL_BUDGET_EXCEEDED";
    private static final int DISCOVERY_LIMIT = 10;

    private final int maximumEvidenceCalls;
    private final int maximumDiscoveryCalls;
    private int evidenceCalls;
    private boolean stageUsed;

    CodebaseBudget(int maximumEvidenceCalls) {
        this.maximumEvidenceCalls = Math.max(0, maximumEvidenceCalls);
        this.maximumDiscoveryCalls = Math.min(DISCOVERY_LIMIT, this.maximumEvidenceCalls);
    }

    synchronized AcquireResult acquire(String toolName) {
        if ("stage_call_chain".equals(toolName)) {
            if (stageUsed) {
                return AcquireResult.rejected(CALL_LIMIT);
            }
            stageUsed = true;
            return AcquireResult.granted();
        }
        if (isDiscovery(toolName) && !discoveryAllowed()) {
            return AcquireResult.rejected(DISCOVERY_RESERVED);
        }
        if (evidenceCalls >= maximumEvidenceCalls) {
            return AcquireResult.rejected(CALL_LIMIT);
        }
        evidenceCalls++;
        return AcquireResult.granted();
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(
                Math.max(0, maximumEvidenceCalls - evidenceCalls),
                discoveryAllowed(),
                !stageUsed);
    }

    private boolean discoveryAllowed() {
        return evidenceCalls < maximumDiscoveryCalls;
    }

    private boolean isDiscovery(String toolName) {
        return "select_local_repository".equals(toolName)
                || "list_repository_directory".equals(toolName)
                || "glob_repository_files".equals(toolName)
                || "grep_repository".equals(toolName);
    }

    record AcquireResult(boolean acquired, String reason) {
        static AcquireResult granted() {
            return new AcquireResult(true, null);
        }

        static AcquireResult rejected(String reason) {
            return new AcquireResult(false, reason);
        }
    }

    record Snapshot(int remainingEvidenceCalls, boolean discoveryAllowed, boolean stageAvailable) {
    }
}

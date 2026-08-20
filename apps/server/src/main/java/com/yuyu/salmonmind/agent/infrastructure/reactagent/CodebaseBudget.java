package com.yuyu.salmonmind.agent.infrastructure.reactagent;

/**
 * CODEBASE Run 的两段式调用预算。
 *
 * <p>Evidence 总量仍然是 16 次，但前 10 次之后关闭仓库选择与目录发现工具，给
 * ReadFile 与只读 Git 留出最后 6 次。调用链暂存拥有两次尝试、一次成功额度，允许
 * 第一次因证据不足返回缺口后补读再试。</p>
 */
final class CodebaseBudget {

    static final String DISCOVERY_RESERVED = "CODEBASE_DISCOVERY_BUDGET_RESERVED";
    private static final String CALL_LIMIT = "TOOL_CALL_BUDGET_EXCEEDED";
    private static final int DISCOVERY_LIMIT = 10;

    private final int maximumEvidenceCalls;
    private final int maximumDiscoveryCalls;
    private int evidenceCalls;
    private int stageAttempts;
    private boolean stageSucceeded;

    CodebaseBudget(int maximumEvidenceCalls) {
        this.maximumEvidenceCalls = Math.max(0, maximumEvidenceCalls);
        this.maximumDiscoveryCalls = Math.min(DISCOVERY_LIMIT, this.maximumEvidenceCalls);
    }

    synchronized AcquireResult acquire(String toolName) {
        if ("stage_call_chain".equals(toolName)) {
            if (stageSucceeded || stageAttempts >= 2) {
                return AcquireResult.rejected(CALL_LIMIT);
            }
            stageAttempts++;
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
                !stageSucceeded && stageAttempts < 2);
    }

    /** 只有形成有效草稿时才关闭第二次尝试；失败尝试保留给补读后的再次暂存。 */
    synchronized void completeStage(String result) {
        if (result == null || result.isBlank()) {
            return;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(result);
            if (root != null && "SUCCESS".equals(root.path("status").asText())) {
                stageSucceeded = true;
            }
        } catch (Exception ignored) {
            // 非结构化或失败结果不消耗下一次 stage 尝试。
        }
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

package com.yuyu.salmonmind.agent.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** CODEBASE 终态摘要不把“无 Citation”误译成来源数量的合成测试。 */
class AgentToolCompletedTest {

    @Test
    void summarizesCodebaseByResultStatusAndKeepsSourceCountUnknown() {
        AgentToolCompleted success = completed(
                AgentToolOutcomeDetail.ResultStatus.SUCCESS, "COMPLETE", false, false);
        AgentToolCompleted empty = completed(
                AgentToolOutcomeDetail.ResultStatus.EMPTY, "NO_MATCH", false, false);
        AgentToolCompleted degraded = completed(
                AgentToolOutcomeDetail.ResultStatus.DEGRADED, "ITEM_LIMIT", true, true);

        assertThat(success.safeSummary()).isEqualTo("CODEBASE · 已完成");
        assertThat(empty.safeSummary()).isEqualTo("CODEBASE · 无匹配");
        assertThat(degraded.safeSummary()).isEqualTo("CODEBASE · 结果不完整");
        assertThat(success.outcomeDetail().sourceCount()).isNull();
        assertThat(success.sourceCount()).isZero();
    }

    @Test
    void legacyCodebaseConstructorDoesNotExposeZeroSources() {
        AgentToolCompleted completed = new AgentToolCompleted(
                "call-1", "read_repository_file", 12, "CODEBASE", 0, false, false);

        assertThat(completed.safeSummary()).isEqualTo("CODEBASE · 已完成");
        assertThat(completed.outcomeDetail().sourceCount()).isNull();
    }

    private static AgentToolCompleted completed(
            AgentToolOutcomeDetail.ResultStatus status, String reason,
            boolean degraded, boolean truncated
    ) {
        return new AgentToolCompleted("call", "read_repository_file",
                new AgentToolOutcomeDetail("CODEBASE", status, reason, null, 5, degraded, truncated), null);
    }
}

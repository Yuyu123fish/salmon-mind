package com.yuyu.salmonmind.agent.infrastructure.reactagent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 验证调用链暂存最多两次尝试且只有一次成功会关闭额度。 */
class CodebaseBudgetTest {

    @Test
    void keepsOneRetryAfterEvidenceFailureAndClosesAfterSuccess() {
        CodebaseBudget budget = new CodebaseBudget(16);

        assertThat(budget.acquire("stage_call_chain").acquired()).isTrue();
        budget.completeStage("{\"status\":\"UNAVAILABLE\",\"reason\":\"CALL_CHAIN_EVIDENCE_INSUFFICIENT\"}");
        assertThat(budget.snapshot().stageAvailable()).isTrue();

        assertThat(budget.acquire("stage_call_chain").acquired()).isTrue();
        budget.completeStage("{\"status\":\"SUCCESS\",\"reason\":\"DRAFT_STAGED\"}");
        assertThat(budget.snapshot().stageAvailable()).isFalse();
        assertThat(budget.acquire("stage_call_chain").acquired()).isFalse();
    }
}

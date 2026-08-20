package com.yuyu.salmonmind.agent.infrastructure.reactagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuyu.salmonmind.agent.api.AgentMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

/** 明确检索限制只关闭对应来源的策略单元验证。 */
class WebSearchPolicyTest {

    @Test
    void disablesOnlyWhenLatestUserExplicitlyForbidsBrowsing() {
        assertThat(EvidenceAccessPolicy.decide(List.of(
                new AgentMessage(AgentMessage.Role.USER, "禁止联网，只用已有知识"))))
                .extracting(EvidenceAccessPolicy.Decision::allowLocal, EvidenceAccessPolicy.Decision::allowWeb)
                .containsExactly(true, false);
        assertThat(EvidenceAccessPolicy.decide(List.of(
                new AgentMessage(AgentMessage.Role.USER, "请勿访问互联网"))))
                .extracting(EvidenceAccessPolicy.Decision::allowLocal, EvidenceAccessPolicy.Decision::allowWeb)
                .containsExactly(true, false);
        assertThat(EvidenceAccessPolicy.decide(List.of(
                new AgentMessage(AgentMessage.Role.USER, "请搜索最新资料"))))
                .extracting(EvidenceAccessPolicy.Decision::allowLocal, EvidenceAccessPolicy.Decision::allowWeb)
                .containsExactly(true, true);
    }

    @Test
    void distinguishesExplicitCodebaseReadBoundaryFromMutationRequests() {
        assertThat(EvidenceAccessPolicy.decide(List.of(
                new AgentMessage(AgentMessage.Role.USER, "不要读取本地代码，但可以联网核对"))))
                .extracting(EvidenceAccessPolicy.Decision::allowLocal, EvidenceAccessPolicy.Decision::allowWeb,
                        EvidenceAccessPolicy.Decision::allowCodebase)
                .containsExactly(true, true, false);
        assertThat(EvidenceAccessPolicy.decide(List.of(
                new AgentMessage(AgentMessage.Role.USER, "不要修改仓库，只读分析"))))
                .extracting(EvidenceAccessPolicy.Decision::allowLocal, EvidenceAccessPolicy.Decision::allowWeb,
                        EvidenceAccessPolicy.Decision::allowCodebase)
                .containsExactly(true, true, true);
    }

    @Test
    void distinguishesAllRetrievalAndLocalOnlyRestrictionsFromDiscussion() {
        assertThat(EvidenceAccessPolicy.decide(List.of(
                new AgentMessage(AgentMessage.Role.USER, "只根据当前对话回答，不要查询任何资料"))))
                .extracting(EvidenceAccessPolicy.Decision::allowLocal, EvidenceAccessPolicy.Decision::allowWeb)
                .containsExactly(false, false);
        assertThat(EvidenceAccessPolicy.decide(List.of(
                new AgentMessage(AgentMessage.Role.USER, "不要查本地资料，但可以联网核对"))))
                .extracting(EvidenceAccessPolicy.Decision::allowLocal, EvidenceAccessPolicy.Decision::allowWeb)
                .containsExactly(false, true);
        assertThat(EvidenceAccessPolicy.decide(List.of(
                new AgentMessage(AgentMessage.Role.USER, "为什么刚才没有联网？"))))
                .extracting(EvidenceAccessPolicy.Decision::allowLocal, EvidenceAccessPolicy.Decision::allowWeb)
                .containsExactly(true, true);
    }
}

package com.yuyu.salmonmind.agent.infrastructure.reactagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuyu.salmonmind.agent.api.AgentMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

/** 明确禁止联网时的零网页调用策略单元验证。 */
class WebSearchPolicyTest {

    @Test
    void disablesOnlyWhenLatestUserExplicitlyForbidsBrowsing() {
        assertThat(WebSearchPolicy.allows(List.of(new AgentMessage(AgentMessage.Role.USER, "禁止联网，只用已有知识"))))
                .isFalse();
        assertThat(WebSearchPolicy.allows(List.of(new AgentMessage(AgentMessage.Role.USER, "请勿访问互联网"))))
                .isFalse();
        assertThat(WebSearchPolicy.allows(List.of(new AgentMessage(AgentMessage.Role.USER, "请搜索最新资料"))))
                .isTrue();
    }
}

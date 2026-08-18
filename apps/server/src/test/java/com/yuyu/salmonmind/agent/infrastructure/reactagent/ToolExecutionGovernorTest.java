package com.yuyu.salmonmind.agent.infrastructure.reactagent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 验证进程级工具许可和网页 Provider 许可均为非阻塞硬边界。 */
class ToolExecutionGovernorTest {

    @Test
    void enforcesGlobalAndProviderPermitsWithoutBlocking() {
        ToolExecutionGovernor governor = new ToolExecutionGovernor(2, 1);

        ToolExecutionGovernor.Permit bocha = governor.tryAcquire("search_web_bocha");
        ToolExecutionGovernor.Permit sameProvider = governor.tryAcquire("search_web_bocha");
        ToolExecutionGovernor.Permit searchApi = governor.tryAcquire("search_web_searchapi");
        ToolExecutionGovernor.Permit local = governor.tryAcquire("search_local_knowledge");

        assertThat(bocha).isNotNull();
        assertThat(sameProvider).isNull();
        assertThat(searchApi).isNotNull();
        assertThat(local).isNull();

        bocha.close();
        ToolExecutionGovernor.Permit recovered = governor.tryAcquire("search_web_bocha");
        assertThat(recovered).isNotNull();

        recovered.close();
        searchApi.close();
    }
}

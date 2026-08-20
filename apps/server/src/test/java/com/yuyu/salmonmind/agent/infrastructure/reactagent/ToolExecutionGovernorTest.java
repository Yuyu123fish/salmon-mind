package com.yuyu.salmonmind.agent.infrastructure.reactagent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 验证进程级工具许可和 SearchApi Provider 许可。 */
class ToolExecutionGovernorTest {

    @Test
    void enforcesGlobalAndProviderPermitsWithoutBlocking() {
        ToolExecutionGovernor governor = new ToolExecutionGovernor(2, 1);

        ToolExecutionGovernor.Permit searchApi = governor.tryAcquire("search_web_searchapi");
        ToolExecutionGovernor.Permit sameProvider = governor.tryAcquire("search_web_searchapi");
        ToolExecutionGovernor.Permit local = governor.tryAcquire("search_local_knowledge");
        ToolExecutionGovernor.Permit secondLocal = governor.tryAcquire("search_local_knowledge");

        assertThat(searchApi).isNotNull();
        assertThat(sameProvider).isNull();
        assertThat(local).isNotNull();
        assertThat(secondLocal).isNull();

        searchApi.close();
        ToolExecutionGovernor.Permit recovered = governor.tryAcquire("search_web_searchapi");
        assertThat(recovered).isNotNull();

        recovered.close();
        local.close();
    }
}

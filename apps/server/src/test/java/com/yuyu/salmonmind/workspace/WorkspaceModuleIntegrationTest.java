package com.yuyu.salmonmind.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:tc:postgresql:17.10-alpine:///salmon_mind",
                "spring.datasource.username=test",
                "spring.datasource.password=test"
        }
)
class WorkspaceModuleIntegrationTest {

    private static final UUID WORKSPACE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    private WorkspaceRegistry workspaceRegistry;

    @Test
    void initializesAndReadsTheSingleWorkspaceFromPostgres() {
        var firstRead = workspaceRegistry.current();
        var secondRead = workspaceRegistry.current();

        assertThat(firstRead).isEqualTo(secondRead);
        assertThat(firstRead.id()).isEqualTo(WORKSPACE_ID);
        assertThat(firstRead.name()).isEqualTo("My Workspace");
        assertThat(firstRead.createdAt()).isBeforeOrEqualTo(Instant.now());
    }
}

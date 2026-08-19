package com.yuyu.salmonmind;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ApplicationModuleStructureTest {

    private static final Set<String> EXPECTED_MODULES = Set.of(
            "persistence",
            "workspace",
            "model",
            "agent",
            "conversation",
            "knowledge",
            "websearch",
            "codebase"
    );

    @Test
    void verifiesPlannedModuleStructure() {
        var modules = ApplicationModules.of(SalmonMindApplication.class);

        modules.verify();
        assertThat(modules.stream()
                .map(module -> module.getIdentifier().toString())
                .collect(java.util.stream.Collectors.toSet()))
                .isEqualTo(EXPECTED_MODULES);
    }
}

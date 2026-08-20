package com.yuyu.salmonmind.persistence.filesystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 统一 Server Data Root 的启动解析和旧配置拒绝合同。 */
class ServerDataRootTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesProjectRootFromRootAndServerWorkingDirectories() throws Exception {
        Files.createFile(temporaryDirectory.resolve("compose.yaml"));
        Files.createDirectories(temporaryDirectory.resolve("apps/server"));
        Files.createFile(temporaryDirectory.resolve("apps/server/pom.xml"));

        ServerDataRoot fromRoot = new ServerDataRoot(null, temporaryDirectory);
        ServerDataRoot fromServer = new ServerDataRoot(null, temporaryDirectory.resolve("apps/server"));

        assertThat(fromRoot.root()).isEqualTo(temporaryDirectory.resolve("data").toRealPath());
        assertThat(fromServer.root()).isEqualTo(fromRoot.root());
        assertThat(fromRoot.conversationRoot()).isDirectory();
        assertThat(fromRoot.repositoryUnderstandingRoot()).isDirectory();
    }

    @Test
    void rejectsUnknownWorkingDirectoryWithoutCreatingRelativeData() throws Exception {
        Path workingDirectory = temporaryDirectory.resolve("unrelated");
        Files.createDirectories(workingDirectory);

        assertThatThrownBy(() -> new ServerDataRoot(null, workingDirectory))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SALMON_DATA_DIR");
        assertThat(workingDirectory.resolve("data")).doesNotExist();
    }

    @Test
    void validatesExplicitAbsoluteRootAndRejectsLegacyVariables() throws Exception {
        Path explicit = temporaryDirectory.resolve("server-data").toAbsolutePath();
        ServerDataRoot root = new ServerDataRoot(explicit.toString(), temporaryDirectory);
        assertThat(root.root()).isEqualTo(explicit.toRealPath());

        assertThatThrownBy(() -> new ServerDataRoot("relative-data", temporaryDirectory))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("绝对路径");
        assertThatThrownBy(() -> new ServerDataRoot(null, temporaryDirectory,
                "legacy-data", ServerDataRoot.UNSET))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CONVERSATION_DATA_DIR");
        assertThatThrownBy(() -> new ServerDataRoot(null, temporaryDirectory,
                ServerDataRoot.UNSET, "legacy-data"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CODEBASE_DATA_DIR");
    }
}

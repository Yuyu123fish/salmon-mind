package com.yuyu.salmonmind.codebase.infrastructure.filesystem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.salmonmind.codebase.api.CallChainConfirmation;
import com.yuyu.salmonmind.codebase.api.CallChainEdgeInput;
import com.yuyu.salmonmind.codebase.api.CallChainNodeInput;
import com.yuyu.salmonmind.codebase.api.CallChainPrepareRequest;
import com.yuyu.salmonmind.codebase.api.CallChainReference;
import com.yuyu.salmonmind.codebase.api.CodebaseErrorCode;
import com.yuyu.salmonmind.codebase.api.CodebaseException;
import com.yuyu.salmonmind.codebase.api.RepositoryObservation;
import com.yuyu.salmonmind.codebase.application.port.CallChainStorePort;
import com.yuyu.salmonmind.codebase.application.port.CatalogStorePort;
import com.yuyu.salmonmind.codebase.application.port.CatalogState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 调用链文件协议的最小真实文件测试：验证 pending 可见性、重启解析与墓碑。 */
class FileSystemCallChainStoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void preparesConfirmsRenamesDeletesAndReloadsWithoutExposingPending() throws Exception {
        Path dataDir = temporaryDirectory.resolve("repository-understanding");
        UUID repositoryId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID answerEntryId = UUID.randomUUID();
        RepositoryObservation observation = observation();
        String sourceA = "void enter() {\n  service.run();\n}";
        String sourceB = "void run() {\n  return;\n}";
        CallChainNodeInput nodeA = node(repositoryId, "A", "java", "Demo.enter", "void enter()", "src/Demo.java", 1, 3, sourceA);
        CallChainNodeInput nodeB = node(repositoryId, "B", "java", "Demo.run", "void run()", "src/Service.java", 4, 6, sourceB);
        List<CallChainNodeInput> nodes = List.of(nodeA, nodeB);
        List<CallChainEdgeInput> edges = List.of(new CallChainEdgeInput("A", "B"), new CallChainEdgeInput("B", "A"));
        CallChainPrepareRequest request = new CallChainPrepareRequest(
                repositoryId, observation, "入口到服务", nodes, edges, conversationId, answerEntryId);
        List<CallChainStorePort.VerifiedNode> verified = List.of(
                new CallChainStorePort.VerifiedNode(nodeId(repositoryId, nodeA), nodeA, sourceA, nodeA.sourceHash(), observation),
                new CallChainStorePort.VerifiedNode(nodeId(repositoryId, nodeB), nodeB, sourceB, nodeB.sourceHash(), observation));

        FileSystemCallChainStore store = new FileSystemCallChainStore(
                new ObjectMapper(), catalogStore(dataDir));
        CallChainReference prepared = store.prepare(new CallChainStorePort.PrepareInput(
                temporaryDirectory.resolve("repo"), "demo", request, verified, edges));

        assertThat(Files.isRegularFile(dataDir.resolve("repositories").resolve(repositoryId.toString())
                .resolve("pending").resolve(answerEntryId + ".jsonl"))).isTrue();
        assertThat(store.list(repositoryId, "demo")).isEmpty();

        CallChainReference confirmed = store.confirm(new CallChainConfirmation(
                repositoryId, prepared.id(), answerEntryId));
        assertThat(confirmed).isEqualTo(prepared);
        assertThat(store.list(repositoryId, "demo")).singleElement().satisfies(summary -> {
            assertThat(summary.name()).isEqualTo("入口到服务");
            assertThat(summary.nodeCount()).isEqualTo(2);
            assertThat(summary.edgeCount()).isEqualTo(2);
        });
        assertThat(store.detail(repositoryId, prepared.id(), "demo").nodes())
                .extracting("source").containsExactly(sourceA, sourceB);
        assertThat(store.detail(repositoryId, prepared.id(), "demo").edges()).hasSize(2);

        store.rename(repositoryId, prepared.id(), "demo", "用户命名链");
        assertThat(store.detail(repositoryId, prepared.id(), "demo").name()).isEqualTo("用户命名链");
        store.delete(repositoryId, prepared.id(), "demo");
        assertThat(store.list(repositoryId, "demo")).isEmpty();
        assertThatThrownBy(() -> store.detail(repositoryId, prepared.id(), "demo"))
                .isInstanceOf(CodebaseException.class)
                .extracting("code").isEqualTo(CodebaseErrorCode.CALL_CHAIN_DELETED);

        FileSystemCallChainStore restarted = new FileSystemCallChainStore(
                new ObjectMapper(), catalogStore(dataDir));
        assertThatThrownBy(() -> restarted.detail(repositoryId, prepared.id(), "demo"))
                .isInstanceOf(CodebaseException.class)
                .extracting("code").isEqualTo(CodebaseErrorCode.CALL_CHAIN_DELETED);
        assertThat(Files.list(dataDir.resolve("repositories").resolve(repositoryId.toString()).resolve("sources")))
                .hasSize(2);
    }

    @Test
    void rejectsExistingNodeRevisionWhenSourceChanges() {
        Path dataDir = temporaryDirectory.resolve("repository-understanding");
        UUID repositoryId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        RepositoryObservation observation = observation();
        String source = "void run() {}";
        CallChainNodeInput firstA = node(repositoryId, "A", "java", "Demo.enter", "void enter()", "src/Demo.java", 1, 1, source);
        CallChainNodeInput firstB = node(repositoryId, "B", "java", "Demo.run", "void run()", "src/Service.java", 1, 1, source);
        FileSystemCallChainStore store = new FileSystemCallChainStore(new ObjectMapper(), catalogStore(dataDir));
        prepare(store, repositoryId, conversationId, UUID.randomUUID(), observation,
                List.of(firstA, firstB), source, source);

        CallChainNodeInput changedA = new CallChainNodeInput(
                firstA.key(), firstA.language(), firstA.qualifiedSymbol(), firstA.signature(),
                firstA.path(), firstA.startLine(), firstA.endLine(), firstA.summary(), hash("void changed() {}"));
        assertThatThrownBy(() -> prepare(store, repositoryId, conversationId, UUID.randomUUID(), observation,
                List.of(changedA, firstB), "void changed() {}", source))
                .isInstanceOf(CodebaseException.class)
                .extracting("code").isEqualTo(CodebaseErrorCode.CALL_CHAIN_REVISION_UPDATE_REQUIRED);
    }

    private void prepare(
            FileSystemCallChainStore store, UUID repositoryId, UUID conversationId, UUID answerEntryId,
            RepositoryObservation observation, List<CallChainNodeInput> nodes, String sourceA, String sourceB
    ) {
        List<CallChainEdgeInput> edges = List.of(new CallChainEdgeInput("A", "B"));
        CallChainPrepareRequest request = new CallChainPrepareRequest(
                repositoryId, observation, "链", nodes, edges, conversationId, answerEntryId);
        List<CallChainStorePort.VerifiedNode> verified = List.of(
                new CallChainStorePort.VerifiedNode(nodeId(repositoryId, nodes.get(0)), nodes.get(0), sourceA, nodes.get(0).sourceHash(), observation),
                new CallChainStorePort.VerifiedNode(nodeId(repositoryId, nodes.get(1)), nodes.get(1), sourceB, nodes.get(1).sourceHash(), observation));
        store.prepare(new CallChainStorePort.PrepareInput(temporaryDirectory.resolve("repo"), "demo", request, verified, edges));
    }

    private CallChainNodeInput node(
            UUID repositoryId, String key, String language, String symbol, String signature,
            String path, int start, int end, String source
    ) {
        return new CallChainNodeInput(key, language, symbol, signature, path, start, end, key, hash(source));
    }

    private static String nodeId(UUID repositoryId, CallChainNodeInput node) {
        return hash("node-v1\0" + repositoryId + "\0" + node.language().toLowerCase()
                + "\0" + node.qualifiedSymbol().trim() + "\0" + node.signature().trim().replaceAll("\\s+", " "));
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.replace("\r\n", "\n").replace('\r', '\n').getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private static RepositoryObservation observation() {
        return new RepositoryObservation("main", "0123456789012345678901234567890123456789",
                true, false, false, false, 0, 1, 1, 0, Instant.parse("2026-08-20T00:00:00Z"));
    }

    private static CatalogStorePort catalogStore(Path dataDir) {
        return new CatalogStorePort() {
            @Override public CatalogState snapshot() { return new CatalogState(java.util.Map.of(), null); }
            @Override public void saveRepository(com.yuyu.salmonmind.codebase.application.port.StoredRepository repository) { }
            @Override public void saveSettings(UUID activeRepositoryId) { }
            @Override public Path dataDir() { return dataDir; }
            @Override public Path serverDataRoot() { return dataDir; }
        };
    }
}

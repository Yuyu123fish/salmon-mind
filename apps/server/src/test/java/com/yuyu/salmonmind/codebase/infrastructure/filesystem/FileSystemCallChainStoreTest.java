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
    void matchesExistingChainAndAppendsChangedNodeRevision() {
        Path dataDir = temporaryDirectory.resolve("repository-understanding");
        UUID repositoryId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        RepositoryObservation observation = observation();
        String source = "void run() {}";
        UUID firstAnswer = UUID.randomUUID();
        UUID updatedAnswer = UUID.randomUUID();
        CallChainNodeInput firstA = node(repositoryId, "A", "java", "Demo.enter", "void enter()", "src/Demo.java", 1, 1, source);
        CallChainNodeInput firstB = node(repositoryId, "B", "java", "Demo.run", "void run()", "src/Service.java", 1, 1, source);
        FileSystemCallChainStore store = new FileSystemCallChainStore(new ObjectMapper(), catalogStore(dataDir));
        CallChainReference first = prepare(store, repositoryId, conversationId, firstAnswer, observation,
                List.of(firstA, firstB), source, source);
        store.confirm(new CallChainConfirmation(repositoryId, first.id(), firstAnswer));

        CallChainNodeInput changedA = new CallChainNodeInput(
                firstA.key(), firstA.language(), firstA.qualifiedSymbol(), firstA.signature(),
                firstA.path(), firstA.startLine(), firstA.endLine(), firstA.summary(), hash("void changed() {}"));
        CallChainReference updated = prepare(store, repositoryId, conversationId, updatedAnswer, observation,
                List.of(changedA, firstB), "void changed() {}", source);
        assertThat(updated.id()).isEqualTo(first.id());
        store.confirm(new CallChainConfirmation(repositoryId, updated.id(), updatedAnswer));

        var detail = store.detail(repositoryId, updated.id(), "demo");
        assertThat(detail.nodes()).extracting("source").containsExactly("void changed() {}", source);
        assertThat(detail.nodes().getFirst().revisions()).hasSize(2);
        assertThat(detail.nodes().getFirst().revisions().getLast().parentRevisionId())
                .isEqualTo(detail.nodes().getFirst().revisions().getFirst().id());
        assertThat(store.revisionDetail(repositoryId, updated.id(), detail.nodes().getFirst().nodeId(),
                detail.nodes().getFirst().revisions().getFirst().id()).source())
                .isEqualTo(source);
    }

    @Test
    void protectsUserNameUnlessAgentOverrideIsExplicit() {
        Path dataDir = temporaryDirectory.resolve("repository-understanding");
        UUID repositoryId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        RepositoryObservation observation = observation();
        String sourceA = "void enter() {}";
        String sourceB = "void run() {}";
        CallChainNodeInput nodeA = node(repositoryId, "A", "java", "Demo.enter", "void enter()", "A.java", 1, 1, sourceA);
        CallChainNodeInput nodeB = node(repositoryId, "B", "java", "Demo.run", "void run()", "B.java", 1, 1, sourceB);
        FileSystemCallChainStore store = new FileSystemCallChainStore(new ObjectMapper(), catalogStore(dataDir));
        UUID firstAnswer = UUID.randomUUID();
        CallChainReference first = prepareWithName(store, repositoryId, conversationId, firstAnswer, observation,
                List.of(nodeA, nodeB), List.of(sourceA, sourceB), "Agent 初始名", false);
        store.confirm(new CallChainConfirmation(repositoryId, first.id(), firstAnswer));
        store.rename(repositoryId, first.id(), "demo", "用户命名");

        UUID automaticAnswer = UUID.randomUUID();
        CallChainReference automatic = prepareWithName(store, repositoryId, conversationId, automaticAnswer,
                observation, List.of(nodeA, nodeB), List.of(sourceA, sourceB), "Agent 覆盖尝试", false);
        assertThat(automatic.name()).isEqualTo("用户命名");
        store.confirm(new CallChainConfirmation(repositoryId, automatic.id(), automaticAnswer));

        UUID overrideAnswer = UUID.randomUUID();
        CallChainReference override = prepareWithName(store, repositoryId, conversationId, overrideAnswer,
                observation, List.of(nodeA, nodeB), List.of(sourceA, sourceB), "用户授权的新名", true);
        assertThat(override.name()).isEqualTo("用户授权的新名");
        store.confirm(new CallChainConfirmation(repositoryId, override.id(), overrideAnswer));
        assertThat(store.detail(repositoryId, first.id(), "demo").name()).isEqualTo("用户授权的新名");
    }

    @Test
    void stopsAmbiguousSharedNodeMatchAndRejectsStaleConcurrentConfirm() {
        Path dataDir = temporaryDirectory.resolve("repository-understanding");
        UUID repositoryId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        RepositoryObservation observation = observation();
        FileSystemCallChainStore store = new FileSystemCallChainStore(new ObjectMapper(), catalogStore(dataDir));

        List<CallChainNodeInput> firstNodes = List.of(
                node(repositoryId, "A", "java", "Demo.a", "void a()", "A.java", 1, 1, "a"),
                node(repositoryId, "B", "java", "Demo.b", "void b()", "B.java", 1, 1, "b"),
                node(repositoryId, "C", "java", "Demo.c", "void c()", "C.java", 1, 1, "c"));
        List<CallChainNodeInput> secondNodes = List.of(
                node(repositoryId, "D", "java", "Demo.d", "void d()", "D.java", 1, 1, "d"),
                node(repositoryId, "E", "java", "Demo.e", "void e()", "E.java", 1, 1, "e"),
                node(repositoryId, "F", "java", "Demo.f", "void f()", "F.java", 1, 1, "f"));
        List<CallChainEdgeInput> threeEdges = List.of(
                new CallChainEdgeInput("A", "B"), new CallChainEdgeInput("B", "C"));
        List<CallChainEdgeInput> otherEdges = List.of(
                new CallChainEdgeInput("D", "E"), new CallChainEdgeInput("E", "F"));
        UUID firstAnswer = UUID.randomUUID();
        CallChainReference first = prepareWithName(store, repositoryId, conversationId, firstAnswer, observation,
                firstNodes, List.of("a", "b", "c"), "第一条", false, threeEdges);
        store.confirm(new CallChainConfirmation(repositoryId, first.id(), firstAnswer));
        UUID secondAnswer = UUID.randomUUID();
        CallChainReference second = prepareWithName(store, repositoryId, conversationId, secondAnswer, observation,
                secondNodes, List.of("d", "e", "f"), "第二条", false, otherEdges);
        store.confirm(new CallChainConfirmation(repositoryId, second.id(), secondAnswer));

        List<CallChainNodeInput> ambiguousNodes = List.of(firstNodes.get(0), firstNodes.get(1),
                secondNodes.get(0), secondNodes.get(1));
        List<CallChainEdgeInput> ambiguousEdges = List.of(
                new CallChainEdgeInput("A", "B"), new CallChainEdgeInput("B", "D"),
                new CallChainEdgeInput("D", "E"));
        assertThatThrownBy(() -> prepareWithName(store, repositoryId, conversationId, UUID.randomUUID(),
                observation, ambiguousNodes, List.of("a", "b", "d", "e"), "歧义", false, ambiguousEdges))
                .isInstanceOf(CodebaseException.class)
                .extracting("code").isEqualTo(CodebaseErrorCode.CALL_CHAIN_MATCH_AMBIGUOUS);
    }

    @Test
    void rejectsConcurrentConfirmWhenFormalChainAdvanced() {
        Path dataDir = temporaryDirectory.resolve("repository-understanding");
        UUID repositoryId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        RepositoryObservation observation = observation();
        String sourceA = "a1";
        String sourceB = "b1";
        CallChainNodeInput nodeA = node(repositoryId, "A", "java", "Demo.a", "void a()", "A.java", 1, 1, sourceA);
        CallChainNodeInput nodeB = node(repositoryId, "B", "java", "Demo.b", "void b()", "B.java", 1, 1, sourceB);
        FileSystemCallChainStore store = new FileSystemCallChainStore(new ObjectMapper(), catalogStore(dataDir));
        UUID initialAnswer = UUID.randomUUID();
        CallChainReference initial = prepareWithName(store, repositoryId, conversationId, initialAnswer, observation,
                List.of(nodeA, nodeB), List.of(sourceA, sourceB), "初始", false);
        store.confirm(new CallChainConfirmation(repositoryId, initial.id(), initialAnswer));

        CallChainNodeInput changedA = node(repositoryId, "A", "java", "Demo.a", "void a()", "A.java", 1, 1, "a2");
        CallChainNodeInput changedB = node(repositoryId, "B", "java", "Demo.b", "void b()", "B.java", 1, 1, "b2");
        UUID answerA = UUID.randomUUID();
        UUID answerB = UUID.randomUUID();
        CallChainReference updateA = prepareWithName(store, repositoryId, conversationId, answerA, observation,
                List.of(changedA, nodeB), List.of("a2", sourceB), "更新 A", false);
        CallChainReference updateB = prepareWithName(store, repositoryId, conversationId, answerB, observation,
                List.of(nodeA, changedB), List.of(sourceA, "b2"), "更新 B", false);
        assertThat(updateA.id()).isEqualTo(initial.id());
        assertThat(updateB.id()).isEqualTo(initial.id());

        store.confirm(new CallChainConfirmation(repositoryId, updateA.id(), answerA));
        assertThatThrownBy(() -> store.confirm(new CallChainConfirmation(repositoryId, updateB.id(), answerB)))
                .isInstanceOf(CodebaseException.class)
                .extracting("code").isEqualTo(CodebaseErrorCode.CALL_CHAIN_IDENTITY_CONFLICT);
        assertThat(store.detail(repositoryId, initial.id(), "demo").nodes())
                .extracting("source").containsExactly("a2", sourceB);
    }

    @Test
    void preservesSiblingHistoryAndSupportsGraphEvolutionWithoutFuzzyMatching() {
        Path dataDir = temporaryDirectory.resolve("repository-understanding");
        UUID repositoryId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        RepositoryObservation observation = observation();
        FileSystemCallChainStore store = new FileSystemCallChainStore(new ObjectMapper(), catalogStore(dataDir));

        CallChainNodeInput a0 = node(repositoryId, "A", "java", "Demo.a", "void a()", "src/A.java", 1, 1, "a0");
        CallChainNodeInput b0 = node(repositoryId, "B", "java", "Demo.b", "void b()", "src/B.java", 1, 1, "b0");
        CallChainNodeInput c0 = node(repositoryId, "C", "java", "Demo.c", "void c()", "src/C.java", 1, 1, "c0");
        List<CallChainEdgeInput> roots = List.of(
                new CallChainEdgeInput("A", "C"), new CallChainEdgeInput("B", "C"));
        UUID firstAnswer = UUID.randomUUID();
        CallChainReference first = prepareWithName(store, repositoryId, conversationId, firstAnswer, observation,
                List.of(a0, b0, c0), List.of("a0", "b0", "c0"), "第一条", false, roots);
        store.confirm(new CallChainConfirmation(repositoryId, first.id(), firstAnswer));
        var initial = store.detail(repositoryId, first.id(), "demo");
        String originalAId = initial.nodes().getFirst().nodeId();
        UUID originalARevision = initial.nodes().getFirst().revisionId();

        CallChainNodeInput d0 = node(repositoryId, "D", "java", "Demo.d", "void d()", "src/D.java", 1, 1, "d0");
        UUID secondAnswer = UUID.randomUUID();
        CallChainReference second = prepareWithName(store, repositoryId, conversationId, secondAnswer, observation,
                List.of(a0, d0), List.of("a0", "d0"), "第二条", false,
                List.of(new CallChainEdgeInput("A", "D")));
        assertThat(second.id()).isNotEqualTo(first.id());
        store.confirm(new CallChainConfirmation(repositoryId, second.id(), secondAnswer));
        assertThat(store.detail(repositoryId, second.id(), "demo").nodes().getFirst().revisionId())
                .isEqualTo(originalARevision);

        CallChainNodeInput aFirst = node(repositoryId, "A", "java", "Demo.a", "void a()", "src/A.java", 1, 1, "a-first");
        UUID firstBranchAnswer = UUID.randomUUID();
        prepareWithName(store, repositoryId, conversationId, firstBranchAnswer, observation,
                List.of(aFirst, b0, c0), List.of("a-first", "b0", "c0"), "第一条更新", false, roots);
        store.confirm(new CallChainConfirmation(repositoryId, first.id(), firstBranchAnswer));

        CallChainNodeInput aSecond = node(repositoryId, "A", "java", "Demo.a", "void a()", "src/A.java", 1, 1, "a-second");
        UUID secondBranchAnswer = UUID.randomUUID();
        prepareWithName(store, repositoryId, conversationId, secondBranchAnswer, observation,
                List.of(aSecond, d0), List.of("a-second", "d0"), "第二条更新", false,
                List.of(new CallChainEdgeInput("A", "D")));
        store.confirm(new CallChainConfirmation(repositoryId, second.id(), secondBranchAnswer));
        var siblingDetail = store.detail(repositoryId, second.id(), "demo").nodes().getFirst();
        assertThat(siblingDetail.revisions()).hasSize(3);
        assertThat(siblingDetail.revisions().get(1).parentRevisionId()).isEqualTo(originalARevision);
        assertThat(siblingDetail.revisions().get(2).parentRevisionId()).isEqualTo(originalARevision);

        CallChainNodeInput movedB = node(repositoryId, "B", "java", "Demo.b", "void b()",
                "src/moved/B.java", 1, 1, "b0");
        UUID movedAnswer = UUID.randomUUID();
        prepareWithName(store, repositoryId, conversationId, movedAnswer, observation,
                List.of(aFirst, movedB, c0), List.of("a-first", "b0", "c0"), "路径移动", false,
                roots);
        store.confirm(new CallChainConfirmation(repositoryId, first.id(), movedAnswer));
        var movedDetail = store.detail(repositoryId, first.id(), "demo");
        assertThat(movedDetail.nodes().get(1).path()).isEqualTo("src/moved/B.java");
        assertThat(movedDetail.nodes().get(1).revisions()).hasSize(2);

        CallChainNodeInput renamedA = node(repositoryId, "A", "java", "Demo.renamedA", "void renamedA()",
                "src/A.java", 1, 1, "a-renamed");
        UUID renamedAnswer = UUID.randomUUID();
        prepareWithName(store, repositoryId, conversationId, renamedAnswer, observation,
                List.of(renamedA, movedB, c0), List.of("a-renamed", "b0", "c0"), "新增节点身份", false,
                List.of(new CallChainEdgeInput("A", "C"), new CallChainEdgeInput("B", "C")));
        store.confirm(new CallChainConfirmation(repositoryId, first.id(), renamedAnswer));
        var renamedDetail = store.detail(repositoryId, first.id(), "demo");
        assertThat(renamedDetail.nodes()).extracting("qualifiedSymbol")
                .containsExactly("Demo.renamedA", "Demo.b", "Demo.c");
        assertThat(renamedDetail.nodes().getFirst().nodeId()).isNotEqualTo(originalAId);
        assertThat(store.revisionDetail(repositoryId, first.id(), originalAId, originalARevision).source())
                .isEqualTo("a0");

        UUID addedAnswer = UUID.randomUUID();
        prepareWithName(store, repositoryId, conversationId, addedAnswer, observation,
                List.of(renamedA, movedB, c0, d0), List.of("a-renamed", "b0", "c0", "d0"), "增加节点", false,
                List.of(new CallChainEdgeInput("A", "C"), new CallChainEdgeInput("B", "C"),
                        new CallChainEdgeInput("C", "D")));
        store.confirm(new CallChainConfirmation(repositoryId, first.id(), addedAnswer));
        assertThat(store.detail(repositoryId, first.id(), "demo").nodes()).hasSize(4);

        UUID removedAnswer = UUID.randomUUID();
        prepareWithName(store, repositoryId, conversationId, removedAnswer, observation,
                List.of(renamedA, c0, d0), List.of("a-renamed", "c0", "d0"), "移除节点", false,
                List.of(new CallChainEdgeInput("A", "C"), new CallChainEdgeInput("C", "D")));
        store.confirm(new CallChainConfirmation(repositoryId, first.id(), removedAnswer));
        assertThat(store.detail(repositoryId, first.id(), "demo").nodes()).extracting("qualifiedSymbol")
                .containsExactly("Demo.renamedA", "Demo.c", "Demo.d");
    }

    private CallChainReference prepare(
            FileSystemCallChainStore store, UUID repositoryId, UUID conversationId, UUID answerEntryId,
            RepositoryObservation observation, List<CallChainNodeInput> nodes, String sourceA, String sourceB
    ) {
        return prepareWithName(store, repositoryId, conversationId, answerEntryId, observation,
                nodes, List.of(sourceA, sourceB), "链", false);
    }

    private CallChainReference prepareWithName(
            FileSystemCallChainStore store, UUID repositoryId, UUID conversationId, UUID answerEntryId,
            RepositoryObservation observation, List<CallChainNodeInput> nodes, List<String> sources,
            String name, boolean allowUserNameOverride
    ) {
        List<CallChainEdgeInput> edges = defaultEdges(nodes);
        return prepareWithName(store, repositoryId, conversationId, answerEntryId, observation,
                nodes, sources, name, allowUserNameOverride, edges);
    }

    private CallChainReference prepareWithName(
            FileSystemCallChainStore store, UUID repositoryId, UUID conversationId, UUID answerEntryId,
            RepositoryObservation observation, List<CallChainNodeInput> nodes, List<String> sources,
            String name, boolean allowUserNameOverride, List<CallChainEdgeInput> edges
    ) {
        CallChainPrepareRequest request = new CallChainPrepareRequest(
                repositoryId, observation, name, nodes, edges, conversationId, answerEntryId,
                allowUserNameOverride);
        List<CallChainStorePort.VerifiedNode> verified = new java.util.ArrayList<>();
        for (int index = 0; index < nodes.size(); index++) {
            CallChainNodeInput node = nodes.get(index);
            verified.add(new CallChainStorePort.VerifiedNode(
                    nodeId(repositoryId, node), node, sources.get(index), node.sourceHash(), observation));
        }
        return store.prepare(new CallChainStorePort.PrepareInput(
                temporaryDirectory.resolve("repo"), "demo", request, verified, edges));
    }

    private List<CallChainEdgeInput> defaultEdges(List<CallChainNodeInput> nodes) {
        List<CallChainEdgeInput> edges = new java.util.ArrayList<>();
        for (int index = 0; index + 1 < nodes.size(); index++) {
            edges.add(new CallChainEdgeInput(nodes.get(index).key(), nodes.get(index + 1).key()));
        }
        return List.copyOf(edges);
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

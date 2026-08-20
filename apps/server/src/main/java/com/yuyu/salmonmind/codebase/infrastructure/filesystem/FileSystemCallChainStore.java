package com.yuyu.salmonmind.codebase.infrastructure.filesystem;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyu.salmonmind.codebase.api.CallChainConfirmation;
import com.yuyu.salmonmind.codebase.api.CallChainDetail;
import com.yuyu.salmonmind.codebase.api.CallChainEdge;
import com.yuyu.salmonmind.codebase.api.CallChainEdgeInput;
import com.yuyu.salmonmind.codebase.api.CallChainNodeDetail;
import com.yuyu.salmonmind.codebase.api.CallChainNodeInput;
import com.yuyu.salmonmind.codebase.api.CallChainPrepareRequest;
import com.yuyu.salmonmind.codebase.api.CallChainReference;
import com.yuyu.salmonmind.codebase.api.CallChainSummary;
import com.yuyu.salmonmind.codebase.api.CodebaseErrorCode;
import com.yuyu.salmonmind.codebase.api.CodebaseException;
import com.yuyu.salmonmind.codebase.api.NodeRevisionView;
import com.yuyu.salmonmind.codebase.api.RepositoryObservation;
import com.yuyu.salmonmind.codebase.application.port.CallChainStorePort;
import com.yuyu.salmonmind.codebase.application.port.CatalogStorePort;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Repository Understanding 的 JSONL 调用链权威存储。
 *
 * <p>节点、源码和正式调用链先写入 Server 数据根，只有 Assistant Entry 成功追加后才把
 * pending 文件原子移动到正式目录。每个 Repository 的准备、确认、重命名和删除共用一把
 * 写锁，读取只解析正式文件；目标仓库始终只作为读取证据来源。</p>
 */
@Component
public final class FileSystemCallChainStore implements CallChainStorePort {

    private static final int FORMAT_VERSION = 1;
    private final ObjectMapper mapper;
    private final Path dataDir;
    private final ConcurrentHashMap<UUID, ReentrantReadWriteLock> locks = new ConcurrentHashMap<>();

    public FileSystemCallChainStore(ObjectMapper objectMapper, CatalogStorePort catalogStore) {
        this.mapper = objectMapper.copy().findAndRegisterModules();
        this.dataDir = catalogStore.dataDir();
    }

    @Override
    public Path dataDir() {
        return dataDir;
    }

    @Override
    public CallChainReference prepare(PrepareInput input) {
        if (input == null || input.request() == null || input.repositoryRoot() == null) {
            throw invalid("调用链 prepare 参数为空");
        }
        CallChainPrepareRequest request = input.request();
        ReentrantReadWriteLock lock = lockOf(request.repositoryId());
        lock.writeLock().lock();
        try {
            Path repositoryDir = repositoryDir(request.repositoryId());
            Path pendingDir = repositoryDir.resolve("pending");
            Path pending = pendingDir.resolve(request.originAnswerEntryId() + ".jsonl");
            if (Files.exists(pending)) {
                ChainState existing = readChain(pending);
                verifyPending(existing, request, input);
                return reference(existing.header(), existing.current());
            }
            Path formalDir = repositoryDir.resolve("call-chains");
            if (Files.exists(formalDir)) {
                try (DirectoryStream<Path> files = Files.newDirectoryStream(formalDir, "*.jsonl")) {
                    for (Path file : files) {
                        ChainState chain = readChain(file);
                        ChainRevision current = chain.current();
                        if (request.originAnswerEntryId().equals(current.originAnswerEntryId())) {
                            verifyPending(chain, request, input);
                            return reference(chain.header(), current);
                        }
                    }
                } catch (IOException ex) {
                    throw unavailable("调用链数据目录不可用", ex);
                }
            }

            validateName(request.name());
            validateGraph(request.nodes(), request.edges());
            if (request.nodes().size() != input.nodes().size()) {
                throw invalid("调用链节点证据不完整");
            }
            Map<String, String> nodeIds = new HashMap<>();
            for (int index = 0; index < request.nodes().size(); index++) {
                CallChainNodeInput node = request.nodes().get(index);
                CallChainStorePort.VerifiedNode verified = input.nodes().get(index);
                if (verified == null || !node.key().equals(verified.input().key())) {
                    throw invalid("调用链节点证据顺序不一致");
                }
                nodeIds.put(node.key(), verified.nodeId());
            }
            validateEdges(request.edges(), nodeIds.keySet());

            UUID chainId = UUID.randomUUID();
            Instant now = Instant.now();
            ChainHeader header = new ChainHeader(request.repositoryId(), chainId, now);
            List<ChainNodeRef> refs = new ArrayList<>();
            for (CallChainStorePort.VerifiedNode verified : input.nodes()) {
                NodeState nodeState = prepareNode(repositoryDir, request.repositoryId(), verified, now);
                refs.add(new ChainNodeRef(nodeState.header().nodeId(), nodeState.current().id(),
                        verified.input().summary()));
            }
            List<CallChainEdge> edges = request.edges().stream()
                    .map(edge -> new CallChainEdge(nodeIds.get(edge.from()), nodeIds.get(edge.to())))
                    .toList();
            ChainRevision revision = new ChainRevision(
                    UUID.randomUUID(), null, request.name().trim(), "AGENT", refs, edges,
                    request.originConversationId(), request.originAnswerEntryId(), now, null);
            writeChain(pending, header, List.of(revision));
            return reference(header, revision);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public CallChainReference confirm(CallChainConfirmation confirmation) {
        if (confirmation == null || confirmation.repositoryId() == null
                || confirmation.callChainId() == null || confirmation.answerEntryId() == null) {
            throw invalid("调用链确认参数不完整");
        }
        ReentrantReadWriteLock lock = lockOf(confirmation.repositoryId());
        lock.writeLock().lock();
        try {
            Path repositoryDir = repositoryDir(confirmation.repositoryId());
            Path formal = repositoryDir.resolve("call-chains")
                    .resolve(confirmation.callChainId() + ".jsonl");
            if (Files.exists(formal)) {
                ChainState state = readChain(formal);
                verifyChainIdentity(state, confirmation.repositoryId(), confirmation.callChainId());
                if (!confirmation.answerEntryId().equals(state.current().originAnswerEntryId())) {
                    throw conflict("调用链确认来源冲突");
                }
                return reference(state.header(), state.current());
            }
            Path pending = repositoryDir.resolve("pending")
                    .resolve(confirmation.answerEntryId() + ".jsonl");
            if (!Files.isRegularFile(pending)) {
                throw new CodebaseException(CodebaseErrorCode.CALL_CHAIN_PENDING_NOT_FOUND,
                        "调用链待发布记录不存在");
            }
            ChainState state = readChain(pending);
            verifyChainIdentity(state, confirmation.repositoryId(), confirmation.callChainId());
            if (!confirmation.answerEntryId().equals(state.current().originAnswerEntryId())) {
                throw conflict("调用链确认来源冲突");
            }
            verifyNodeReferences(repositoryDir, confirmation.repositoryId(), state.current());
            Files.createDirectories(formal.getParent());
            moveAtomically(pending, formal);
            return reference(state.header(), state.current());
        } catch (IOException ex) {
            throw unavailable("调用链数据目录不可用", ex);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public List<CallChainSummary> list(UUID repositoryId, String repositoryName) {
        if (repositoryId == null) {
            throw new CodebaseException(CodebaseErrorCode.REPOSITORY_NOT_FOUND, "仓库不存在");
        }
        ReentrantReadWriteLock lock = lockOf(repositoryId);
        lock.readLock().lock();
        try {
            Path directory = repositoryDir(repositoryId).resolve("call-chains");
            if (!Files.isDirectory(directory)) {
                return List.of();
            }
            List<CallChainSummary> result = new ArrayList<>();
            try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.jsonl")) {
                for (Path file : files) {
                    ChainState state = readChain(file);
                    ChainRevision current = state.current();
                    if (current.deletedAt() != null) {
                        continue;
                    }
                    result.add(summary(state.header(), current, repositoryName));
                }
            }
            result.sort((left, right) -> right.updatedAt().compareTo(left.updatedAt()));
            return List.copyOf(result);
        } catch (IOException ex) {
            throw unavailable("调用链数据目录不可用", ex);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public CallChainDetail detail(UUID repositoryId, UUID callChainId, String repositoryName) {
        ReentrantReadWriteLock lock = lockOf(repositoryId);
        lock.readLock().lock();
        try {
            ChainState state = readFormal(repositoryId, callChainId);
            ChainRevision current = state.current();
            if (current.deletedAt() != null) {
                throw new CodebaseException(CodebaseErrorCode.CALL_CHAIN_DELETED, "调用链已删除");
            }
            return detailOf(state, current, repositoryName);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public CallChainDetail rename(UUID repositoryId, UUID callChainId, String repositoryName, String name) {
        validateName(name);
        ReentrantReadWriteLock lock = lockOf(repositoryId);
        lock.writeLock().lock();
        try {
            ChainState state = readFormal(repositoryId, callChainId);
            ChainRevision current = state.current();
            if (current.deletedAt() != null) {
                throw new CodebaseException(CodebaseErrorCode.CALL_CHAIN_DELETED, "调用链已删除");
            }
            ChainRevision next = new ChainRevision(
                    UUID.randomUUID(), current.id(), name.trim(), "USER", current.nodes(), current.edges(),
                    current.originConversationId(), current.originAnswerEntryId(), Instant.now(), null);
            List<ChainRevision> revisions = new ArrayList<>(state.revisions());
            revisions.add(next);
            writeChain(chainPath(repositoryId, callChainId), state.header(), revisions);
            return detailOf(new ChainState(state.header(), revisions), next, repositoryName);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public CallChainDetail delete(UUID repositoryId, UUID callChainId, String repositoryName) {
        ReentrantReadWriteLock lock = lockOf(repositoryId);
        lock.writeLock().lock();
        try {
            ChainState state = readFormal(repositoryId, callChainId);
            ChainRevision current = state.current();
            if (current.deletedAt() != null) {
                throw new CodebaseException(CodebaseErrorCode.CALL_CHAIN_DELETED, "调用链已删除");
            }
            ChainRevision next = new ChainRevision(
                    UUID.randomUUID(), current.id(), current.name(), current.nameSource(), current.nodes(),
                    current.edges(), current.originConversationId(), current.originAnswerEntryId(), Instant.now(),
                    Instant.now());
            List<ChainRevision> revisions = new ArrayList<>(state.revisions());
            revisions.add(next);
            writeChain(chainPath(repositoryId, callChainId), state.header(), revisions);
            return detailOf(new ChainState(state.header(), revisions), next, repositoryName);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private NodeState prepareNode(
            Path repositoryDir, UUID repositoryId, CallChainStorePort.VerifiedNode verified, Instant now
    ) {
        CallChainNodeInput input = verified.input();
        Path nodesDir = repositoryDir.resolve("nodes");
        Path nodeFile = nodesDir.resolve(verified.nodeId() + ".jsonl");
        NodeState state;
        if (Files.exists(nodeFile)) {
            state = readNode(nodeFile);
            if (!repositoryId.equals(state.header().repositoryId())) {
                throw corrupted("Node Header 仓库身份不一致");
            }
            NodeRevision latest = state.current();
            if (!latest.sourceHash().equals(verified.sourceHash()) || !latest.path().equals(input.path())) {
                throw new CodebaseException(CodebaseErrorCode.CALL_CHAIN_REVISION_UPDATE_REQUIRED,
                        "已有代码节点需要后续 Revision 处理");
            }
            return state;
        }
        NodeHeader header = new NodeHeader(
                repositoryId,
                input.language().toLowerCase(java.util.Locale.ROOT), input.qualifiedSymbol().trim(),
                normalizeSignature(input.signature()), verified.nodeId(), now);
        NodeRevision revision = new NodeRevision(
                UUID.randomUUID(), null, verified.sourceHash(), input.path(), input.startLine(), input.endLine(),
                verified.observation(), now);
        writeNode(nodeFile, header, List.of(revision));
        Path source = repositoryDir.resolve("sources").resolve(verified.sourceHash() + ".txt");
        if (!Files.exists(source)) {
            writeAtomic(source, verified.source(), false);
        }
        return new NodeState(header, List.of(revision));
    }

    private void verifyNodeReferences(Path repositoryDir, UUID repositoryId, ChainRevision revision) {
        for (ChainNodeRef reference : revision.nodes()) {
            Path nodeFile = repositoryDir.resolve("nodes").resolve(reference.nodeId() + ".jsonl");
            NodeState node = readNode(nodeFile);
            if (!repositoryId.equals(node.header().repositoryId())) {
                throw corrupted("节点 Header 仓库身份不一致");
            }
            NodeRevision found = node.revisions().stream()
                    .filter(value -> value.id().equals(reference.nodeRevisionId())).findFirst()
                    .orElseThrow(() -> corrupted("调用链引用了不存在的节点 Revision"));
            Path source = repositoryDir.resolve("sources").resolve(found.sourceHash() + ".txt");
            if (!Files.isRegularFile(source)) {
                throw corrupted("调用链引用的源码快照不存在");
            }
            try {
                String content = Files.readString(source, StandardCharsets.UTF_8);
                if (!hash(content).equals(found.sourceHash())) {
                    throw corrupted("源码快照哈希不一致");
                }
            } catch (IOException ex) {
                throw unavailable("调用链源码快照不可读", ex);
            }
        }
    }

    private CallChainDetail detailOf(ChainState state, ChainRevision revision, String repositoryName) {
        List<CallChainNodeDetail> nodes = new ArrayList<>();
        Path repositoryDir = repositoryDir(state.header().repositoryId());
        for (ChainNodeRef reference : revision.nodes()) {
            NodeState node = readNode(repositoryDir.resolve("nodes").resolve(reference.nodeId() + ".jsonl"));
            NodeRevision selected = node.revisions().stream()
                    .filter(value -> value.id().equals(reference.nodeRevisionId())).findFirst()
                    .orElseThrow(() -> corrupted("调用链引用了不存在的节点 Revision"));
            String source = readSource(repositoryDir, selected.sourceHash());
            List<NodeRevisionView> revisions = node.revisions().stream()
                    .map(value -> new NodeRevisionView(value.id(), value.parentRevisionId(), value.sourceHash(),
                            value.path(), value.startLine(), value.endLine(), value.observation(), value.observedAt()))
                    .toList();
            nodes.add(new CallChainNodeDetail(
                    node.header().nodeId(), selected.id(), node.header().language(), node.header().qualifiedSymbol(),
                    node.header().normalizedSignature(), reference.summary(), selected.sourceHash(), selected.path(),
                    selected.startLine(), selected.endLine(), source, selected.observation(), revisions));
        }
        List<CallChainEdge> edges = List.copyOf(revision.edges());
        return new CallChainDetail(state.header().id(), state.header().repositoryId(), repositoryName,
                revision.name(), revision.nodes().size(), edges.size(), revision.originConversationId(),
                revision.originAnswerEntryId(), state.header().createdAt(), revision.createdAt(), nodes, edges);
    }

    private String readSource(Path repositoryDir, String sourceHash) {
        Path source = repositoryDir.resolve("sources").resolve(sourceHash + ".txt");
        try {
            if (!Files.isRegularFile(source)) {
                throw corrupted("源码快照不存在");
            }
            String content = Files.readString(source, StandardCharsets.UTF_8);
            if (!hash(content).equals(sourceHash)) {
                throw corrupted("源码快照哈希不一致");
            }
            return content;
        } catch (IOException ex) {
            throw unavailable("源码快照不可读", ex);
        }
    }

    private ChainState readFormal(UUID repositoryId, UUID callChainId) {
        if (repositoryId == null || callChainId == null) {
            throw new CodebaseException(CodebaseErrorCode.CALL_CHAIN_NOT_FOUND, "调用链不存在");
        }
        Path path = chainPath(repositoryId, callChainId);
        if (!Files.isRegularFile(path)) {
            throw new CodebaseException(CodebaseErrorCode.CALL_CHAIN_NOT_FOUND, "调用链不存在");
        }
        ChainState state = readChain(path);
        verifyChainIdentity(state, repositoryId, callChainId);
        return state;
    }

    private void verifyPending(
            ChainState state, CallChainPrepareRequest request, CallChainStorePort.PrepareInput input
    ) {
        verifyChainIdentity(state, request.repositoryId(), state.header().id());
        ChainRevision current = state.current();
        if (!request.originAnswerEntryId().equals(current.originAnswerEntryId())
                || !request.originConversationId().equals(current.originConversationId())
                || !request.name().trim().equals(current.name())
                || input == null || input.nodes() == null
                || input.nodes().size() != current.nodes().size()
                || input.edges() == null || input.edges().size() != current.edges().size()) {
            throw conflict("调用链 pending 来源冲突");
        }
        for (int index = 0; index < input.nodes().size(); index++) {
            CallChainStorePort.VerifiedNode verified = input.nodes().get(index);
            ChainNodeRef reference = current.nodes().get(index);
            if (verified == null || !verified.nodeId().equals(reference.nodeId())
                    || !verified.input().summary().equals(reference.summary())) {
                throw conflict("调用链 pending 节点冲突");
            }
        }
        Map<String, String> nodeIds = new HashMap<>();
        for (int index = 0; index < request.nodes().size(); index++) {
            nodeIds.put(request.nodes().get(index).key(), input.nodes().get(index).nodeId());
        }
        List<CallChainEdge> expectedEdges = request.edges().stream()
                .map(edge -> new CallChainEdge(nodeIds.get(edge.from()), nodeIds.get(edge.to())))
                .toList();
        if (!expectedEdges.equals(current.edges())) {
            throw conflict("调用链 pending 边冲突");
        }
    }

    private void verifyChainIdentity(ChainState state, UUID repositoryId, UUID chainId) {
        if (!repositoryId.equals(state.header().repositoryId()) || !chainId.equals(state.header().id())) {
            throw new CodebaseException(CodebaseErrorCode.CALL_CHAIN_IDENTITY_CONFLICT,
                    "调用链身份不一致");
        }
    }

    private CallChainReference reference(ChainHeader header, ChainRevision revision) {
        return new CallChainReference(header.id(), header.repositoryId(), revision.name(),
                revision.nodes().size(), revision.edges().size());
    }

    private CallChainSummary summary(ChainHeader header, ChainRevision revision, String repositoryName) {
        return new CallChainSummary(header.id(), header.repositoryId(), repositoryName, revision.name(),
                revision.nodes().size(), revision.edges().size(), header.createdAt(), revision.createdAt());
    }

    private void validateGraph(List<CallChainNodeInput> nodes, List<CallChainEdgeInput> edges) {
        if (nodes == null || nodes.size() < 2 || nodes.size() > 12 || edges == null
                || edges.isEmpty() || edges.size() > 24) {
            throw invalid("调用链节点或边数量不合法");
        }
        Set<String> keys = new HashSet<>();
        for (CallChainNodeInput node : nodes) {
            if (node == null || blankOrLong(node.key(), 80) || !keys.add(node.key().trim())
                    || blankOrLong(node.language(), 40) || blankOrLong(node.qualifiedSymbol(), 500)
                    || blankOrLong(node.signature(), 2_000) || blankOrLong(node.path(), 512)
                    || node.startLine() < 1 || node.endLine() < node.startLine()
                    || node.endLine() - node.startLine() + 1 > 500
                    || node.summary() == null || node.summary().length() > 500
                    || node.sourceHash() == null || !node.sourceHash().matches("[0-9a-f]{64}")) {
                throw invalid("调用链节点字段不合法");
            }
        }
        validateEdges(edges, keys);
    }

    private void validateEdges(List<CallChainEdgeInput> edges, Set<String> keys) {
        Set<String> seen = new HashSet<>();
        for (CallChainEdgeInput edge : edges) {
            if (edge == null || edge.from() == null || edge.to() == null
                    || !keys.contains(edge.from()) || !keys.contains(edge.to())
                    || !seen.add(edge.from() + "\u0000" + edge.to())) {
                throw invalid("调用链边不合法");
            }
        }
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank() || name.trim().length() > 120
                || name.contains("\n") || name.contains("\r")) {
            throw new CodebaseException(CodebaseErrorCode.CALL_CHAIN_NAME_INVALID, "调用链名称不合法");
        }
    }

    private static boolean blankOrLong(String value, int maximum) {
        return value == null || value.isBlank() || value.trim().length() > maximum
                || value.indexOf('\0') >= 0;
    }

    private Path repositoryDir(UUID repositoryId) {
        return dataDir.resolve("repositories").resolve(repositoryId.toString());
    }

    private Path chainPath(UUID repositoryId, UUID callChainId) {
        return repositoryDir(repositoryId).resolve("call-chains").resolve(callChainId + ".jsonl");
    }

    private ReentrantReadWriteLock lockOf(UUID repositoryId) {
        return locks.computeIfAbsent(repositoryId == null ? new UUID(0, 0) : repositoryId,
                ignored -> new ReentrantReadWriteLock());
    }

    private void writeNode(Path target, NodeHeader header, List<NodeRevision> revisions) {
        List<String> lines = new ArrayList<>();
        lines.add(write(nodeHeader(header)));
        revisions.forEach(revision -> lines.add(write(nodeRevision(revision))));
        writeAtomic(target, String.join("\n", lines) + "\n", true);
    }

    private void writeChain(Path target, ChainHeader header, List<ChainRevision> revisions) {
        List<String> lines = new ArrayList<>();
        lines.add(write(chainHeader(header)));
        revisions.forEach(revision -> lines.add(write(chainRevision(revision))));
        writeAtomic(target, String.join("\n", lines) + "\n", true);
    }

    private NodeState readNode(Path file) {
        List<JsonNode> lines = readJsonLines(file);
        if (lines.size() < 2) {
            throw corrupted("Node JSONL 缺少 Header 或 Revision");
        }
        JsonNode header = lines.getFirst();
        ensureType(header, "HEADER");
        NodeHeader parsedHeader = new NodeHeader(
                requiredUuid(header, "repositoryId"),
                requiredText(header, "language"), requiredText(header, "qualifiedSymbol"),
                requiredText(header, "normalizedSignature"), requiredNodeId(header, "nodeId"),
                requiredInstant(header, "createdAt"));
        List<NodeRevision> revisions = new ArrayList<>();
        UUID previous = null;
        for (JsonNode line : lines.subList(1, lines.size())) {
            ensureType(line, "REVISION");
            NodeRevision revision = parseNodeRevision(line);
            if (revision.parentRevisionId() == null ? previous != null
                    : !revision.parentRevisionId().equals(previous)) {
                throw corrupted("Node Revision 父链断裂");
            }
            if (revisions.stream().anyMatch(value -> value.id().equals(revision.id()))) {
                throw corrupted("Node Revision 身份重复");
            }
            revisions.add(revision);
            previous = revision.id();
        }
        return new NodeState(parsedHeader, List.copyOf(revisions));
    }

    private ChainState readChain(Path file) {
        List<JsonNode> lines = readJsonLines(file);
        if (lines.size() < 2) {
            throw corrupted("Call Chain JSONL 缺少 Header 或 Revision");
        }
        JsonNode header = lines.getFirst();
        ensureType(header, "HEADER");
        ChainHeader parsedHeader = new ChainHeader(requiredUuid(header, "repositoryId"),
                requiredUuid(header, "callChainId"), requiredInstant(header, "createdAt"));
        List<ChainRevision> revisions = new ArrayList<>();
        UUID previous = null;
        for (JsonNode line : lines.subList(1, lines.size())) {
            ensureType(line, "REVISION");
            ChainRevision revision = parseChainRevision(line);
            if (revision.parentRevisionId() == null ? previous != null
                    : !revision.parentRevisionId().equals(previous)) {
                throw corrupted("Call Chain Revision 父链断裂");
            }
            if (revisions.stream().anyMatch(value -> value.id().equals(revision.id()))) {
                throw corrupted("Call Chain Revision 身份重复");
            }
            revisions.add(revision);
            previous = revision.id();
        }
        return new ChainState(parsedHeader, List.copyOf(revisions));
    }

    private List<JsonNode> readJsonLines(Path file) {
        if (!Files.isRegularFile(file)) {
            throw corrupted("调用链数据文件缺失");
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.isEmpty()) {
                throw corrupted("调用链数据文件为空");
            }
            String[] raw = content.split("\\r?\\n", -1);
            boolean trailingNewline = content.endsWith("\n");
            int count = trailingNewline ? raw.length - 1 : raw.length;
            List<JsonNode> result = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                if (raw[index].isBlank()) {
                    throw corrupted("调用链 JSONL 包含空行");
                }
                try {
                    result.add(mapper.readTree(raw[index]));
                } catch (JsonProcessingException ex) {
                    if (index == count - 1 && !trailingNewline && isTorn(ex) && index > 0) {
                        String repaired = String.join("\n", java.util.Arrays.copyOf(raw, index)) + "\n";
                        writeAtomic(file, repaired, true);
                        return readJsonLines(file);
                    }
                    throw corrupted("调用链 JSONL 已损坏", ex);
                }
            }
            return result;
        } catch (CodebaseException ex) {
            throw ex;
        } catch (IOException ex) {
            throw unavailable("调用链数据目录不可用", ex);
        }
    }

    private boolean isTorn(JsonProcessingException ex) {
        String message = ex.getMessage();
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("end-of-input") || normalized.contains("unexpected end")
                || normalized.contains("eof");
    }

    private ObjectNode nodeHeader(NodeHeader value) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "HEADER");
        node.put("formatVersion", FORMAT_VERSION);
        node.put("repositoryId", value.repositoryId().toString());
        node.put("nodeId", value.nodeId());
        node.put("language", value.language());
        node.put("qualifiedSymbol", value.qualifiedSymbol());
        node.put("normalizedSignature", value.normalizedSignature());
        node.put("createdAt", value.createdAt().toString());
        return node;
    }

    private ObjectNode nodeRevision(NodeRevision value) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "REVISION");
        node.put("formatVersion", FORMAT_VERSION);
        node.put("revisionId", value.id().toString());
        putUuid(node, "parentRevisionId", value.parentRevisionId());
        node.put("sourceHash", value.sourceHash());
        node.put("path", value.path());
        node.put("startLine", value.startLine());
        node.put("endLine", value.endLine());
        node.set("observation", observation(value.observation()));
        node.put("observedAt", value.observedAt().toString());
        return node;
    }

    private ObjectNode chainHeader(ChainHeader value) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "HEADER");
        node.put("formatVersion", FORMAT_VERSION);
        node.put("repositoryId", value.repositoryId().toString());
        node.put("callChainId", value.id().toString());
        node.put("createdAt", value.createdAt().toString());
        return node;
    }

    private ObjectNode chainRevision(ChainRevision value) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "REVISION");
        node.put("formatVersion", FORMAT_VERSION);
        node.put("revisionId", value.id().toString());
        putUuid(node, "parentRevisionId", value.parentRevisionId());
        node.put("name", value.name());
        node.put("nameSource", value.nameSource());
        ArrayNode nodes = node.putArray("nodes");
        for (ChainNodeRef reference : value.nodes()) {
            ObjectNode item = nodes.addObject();
            item.put("nodeId", reference.nodeId());
            item.put("nodeRevisionId", reference.nodeRevisionId().toString());
            item.put("summary", reference.summary());
        }
        ArrayNode edges = node.putArray("edges");
        for (CallChainEdge edge : value.edges()) {
            ObjectNode item = edges.addObject();
            item.put("fromNodeId", edge.fromNodeId());
            item.put("toNodeId", edge.toNodeId());
        }
        node.put("originConversationId", value.originConversationId().toString());
        node.put("originAnswerEntryId", value.originAnswerEntryId().toString());
        node.put("createdAt", value.createdAt().toString());
        putInstant(node, "deletedAt", value.deletedAt());
        return node;
    }

    private ObjectNode observation(RepositoryObservation value) {
        ObjectNode node = mapper.createObjectNode();
        putNullable(node, "branch", value.branch());
        putNullable(node, "head", value.head());
        node.put("dirty", value.dirty());
        node.put("unborn", value.unborn());
        node.put("detached", value.detached());
        node.put("shallow", value.shallow());
        node.put("stagedCount", value.stagedCount());
        node.put("unstagedCount", value.unstagedCount());
        node.put("untrackedCount", value.untrackedCount());
        node.put("sensitiveChangedCount", value.sensitiveChangedCount());
        node.put("observedAt", value.observedAt().toString());
        return node;
    }

    private NodeRevision parseNodeRevision(JsonNode node) {
        return new NodeRevision(requiredUuid(node, "revisionId"), nullableUuid(node, "parentRevisionId"),
                requiredHash(node, "sourceHash"), requiredText(node, "path"), positiveInt(node, "startLine"),
                positiveInt(node, "endLine"), parseObservation(node.get("observation")),
                requiredInstant(node, "observedAt"));
    }

    private ChainRevision parseChainRevision(JsonNode node) {
        List<ChainNodeRef> nodes = new ArrayList<>();
        JsonNode nodeArray = node.get("nodes");
        if (nodeArray == null || !nodeArray.isArray() || nodeArray.size() < 2 || nodeArray.size() > 12) {
            throw corrupted("调用链节点列表无效");
        }
        Set<String> nodeIds = new HashSet<>();
        for (JsonNode item : nodeArray) {
            ChainNodeRef reference = new ChainNodeRef(requiredNodeId(item, "nodeId"),
                    requiredUuid(item, "nodeRevisionId"), requiredText(item, "summary"));
            if (!nodeIds.add(reference.nodeId())) {
                throw corrupted("调用链节点重复");
            }
            nodes.add(reference);
        }
        List<CallChainEdge> edges = new ArrayList<>();
        JsonNode edgeArray = node.get("edges");
        if (edgeArray == null || !edgeArray.isArray() || edgeArray.isEmpty() || edgeArray.size() > 24) {
            throw corrupted("调用链边列表无效");
        }
        Set<String> edgeKeys = new HashSet<>();
        for (JsonNode item : edgeArray) {
            String from = requiredNodeId(item, "fromNodeId");
            String to = requiredNodeId(item, "toNodeId");
            if (!nodeIds.contains(from) || !nodeIds.contains(to) || !edgeKeys.add(from + "\u0000" + to)) {
                throw corrupted("调用链边端点或身份无效");
            }
            edges.add(new CallChainEdge(from, to));
        }
        return new ChainRevision(requiredUuid(node, "revisionId"), nullableUuid(node, "parentRevisionId"),
                requiredText(node, "name"), requiredText(node, "nameSource"), nodes, edges,
                requiredUuid(node, "originConversationId"), requiredUuid(node, "originAnswerEntryId"),
                requiredInstant(node, "createdAt"), nullableInstant(node, "deletedAt"));
    }

    private RepositoryObservation parseObservation(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw corrupted("Git Observation 缺失");
        }
        return new RepositoryObservation(nullableText(node, "branch"), nullableText(node, "head"),
                requiredBoolean(node, "dirty"), requiredBoolean(node, "unborn"),
                requiredBoolean(node, "detached"), requiredBoolean(node, "shallow"),
                positiveOrZero(node, "stagedCount"), positiveOrZero(node, "unstagedCount"),
                positiveOrZero(node, "untrackedCount"), positiveOrZero(node, "sensitiveChangedCount"),
                requiredInstant(node, "observedAt"));
    }

    private static String normalizeSignature(String signature) {
        return signature.trim().replaceAll("\\s+", " ");
    }

    private static String hash(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.replace("\r\n", "\n").replace('\r', '\n')
                            .getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private void ensureType(JsonNode node, String type) {
        if (node == null || !node.isObject() || !type.equals(nullableText(node, "type"))
                || node.path("formatVersion").asInt(-1) != FORMAT_VERSION) {
            throw corrupted("调用链 JSONL Header/Revision 类型无效");
        }
    }

    private String write(ObjectNode value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw unavailable("调用链 JSONL 序列化失败", ex);
        }
    }

    private void writeAtomic(Path target, String content, boolean createParent) {
        try {
            if (createParent) {
                Files.createDirectories(target.getParent());
            }
            Path parent = target.getParent();
            Files.createDirectories(parent);
            Path temporary = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
            boolean moved = false;
            try {
                Files.writeString(temporary, content, StandardCharsets.UTF_8,
                        StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
                moveAtomically(temporary, target);
                moved = true;
            } finally {
                if (!moved) {
                    Files.deleteIfExists(temporary);
                }
            }
        } catch (IOException ex) {
            throw unavailable("调用链数据目录不可用", ex);
        }
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void putUuid(ObjectNode node, String field, UUID value) {
        if (value == null) node.putNull(field); else node.put(field, value.toString());
    }

    private static void putInstant(ObjectNode node, String field, Instant value) {
        if (value == null) node.putNull(field); else node.put(field, value.toString());
    }

    private static void putNullable(ObjectNode node, String field, String value) {
        if (value == null) node.putNull(field); else node.put(field, value);
    }

    private static String requiredText(JsonNode node, String field) {
        String value = nullableText(node, field);
        if (value == null || value.isBlank()) throw corrupted("调用链字段缺失: " + field);
        return value;
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.isTextual() ? value.asText() : invalidField(field);
    }

    private static String requiredHash(JsonNode node, String field) {
        String value = requiredText(node, field);
        if (!value.matches("[0-9a-f]{64}")) throw corrupted("调用链哈希无效");
        return value;
    }

    private static UUID requiredUuid(JsonNode node, String field) {
        String value = requiredText(node, field);
        try { return UUID.fromString(value); } catch (IllegalArgumentException ex) { throw corrupted("UUID 无效: " + field); }
    }

    private static String requiredNodeId(JsonNode node, String field) {
        String value = requiredText(node, field);
        if (!value.matches("[0-9a-f]{64}")) {
            throw corrupted("Node ID 无效: " + field);
        }
        return value;
    }

    private static UUID nullableUuid(JsonNode node, String field) {
        String value = nullableText(node, field);
        if (value == null) return null;
        try { return UUID.fromString(value); } catch (IllegalArgumentException ex) { throw corrupted("UUID 无效: " + field); }
    }

    private static Instant requiredInstant(JsonNode node, String field) {
        String value = requiredText(node, field);
        try { return Instant.parse(value); } catch (RuntimeException ex) { throw corrupted("时间无效: " + field); }
    }

    private static Instant nullableInstant(JsonNode node, String field) {
        String value = nullableText(node, field);
        if (value == null) return null;
        try { return Instant.parse(value); } catch (RuntimeException ex) { throw corrupted("时间无效: " + field); }
    }

    private static int positiveInt(JsonNode node, String field) {
        int value = node == null || !node.has(field) || !node.get(field).canConvertToInt()
                ? -1 : node.get(field).asInt();
        if (value < 1) throw corrupted("行号无效: " + field);
        return value;
    }

    private static int positiveOrZero(JsonNode node, String field) {
        int value = node == null || !node.has(field) || !node.get(field).canConvertToInt()
                ? -1 : node.get(field).asInt();
        if (value < 0) throw corrupted("观察计数无效: " + field);
        return value;
    }

    private static boolean requiredBoolean(JsonNode node, String field) {
        if (node == null || !node.has(field) || !node.get(field).isBoolean()) throw corrupted("布尔字段无效");
        return node.get(field).asBoolean();
    }

    private static String invalidField(String field) {
        throw corrupted("调用链字段类型无效: " + field);
    }

    private static CodebaseException invalid(String message) {
        return new CodebaseException(CodebaseErrorCode.CALL_CHAIN_DRAFT_INVALID, message);
    }

    private static CodebaseException conflict(String message) {
        return new CodebaseException(CodebaseErrorCode.CALL_CHAIN_IDENTITY_CONFLICT, message);
    }

    private static CodebaseException corrupted(String message) {
        return new CodebaseException(CodebaseErrorCode.CODEBASE_DATA_CORRUPTED, message);
    }

    private static CodebaseException corrupted(String message, Throwable cause) {
        return new CodebaseException(CodebaseErrorCode.CODEBASE_DATA_CORRUPTED, message, cause);
    }

    private static CodebaseException unavailable(String message, Throwable cause) {
        return new CodebaseException(CodebaseErrorCode.CODEBASE_DATA_UNAVAILABLE, message, cause);
    }

    private record NodeHeader(UUID repositoryId, String language, String qualifiedSymbol,
                              String normalizedSignature, String nodeId, Instant createdAt) {
    }

    private record NodeRevision(UUID id, UUID parentRevisionId, String sourceHash, String path,
                                int startLine, int endLine, RepositoryObservation observation,
                                Instant observedAt) {
    }

    private record NodeState(NodeHeader header, List<NodeRevision> revisions) {
        NodeRevision current() { return revisions.getLast(); }
    }

    private record ChainHeader(UUID repositoryId, UUID id, Instant createdAt) {
    }

    private record ChainNodeRef(String nodeId, UUID nodeRevisionId, String summary) {
    }

    private record ChainRevision(UUID id, UUID parentRevisionId, String name, String nameSource,
                                 List<ChainNodeRef> nodes, List<CallChainEdge> edges,
                                 UUID originConversationId, UUID originAnswerEntryId,
                                 Instant createdAt, Instant deletedAt) {
        ChainRevision {
            nodes = List.copyOf(nodes);
            edges = List.copyOf(edges);
        }
    }

    private record ChainState(ChainHeader header, List<ChainRevision> revisions) {
        ChainRevision current() { return revisions.getLast(); }
    }

}

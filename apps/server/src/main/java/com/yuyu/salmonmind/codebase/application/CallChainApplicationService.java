package com.yuyu.salmonmind.codebase.application;

import com.yuyu.salmonmind.codebase.api.AgentCallChainService;
import com.yuyu.salmonmind.codebase.api.CallChainConfirmation;
import com.yuyu.salmonmind.codebase.api.CallChainDetail;
import com.yuyu.salmonmind.codebase.api.CallChainEdgeInput;
import com.yuyu.salmonmind.codebase.api.CallChainNodeInput;
import com.yuyu.salmonmind.codebase.api.CallChainPrepareRequest;
import com.yuyu.salmonmind.codebase.api.CallChainQueryService;
import com.yuyu.salmonmind.codebase.api.CallChainReference;
import com.yuyu.salmonmind.codebase.api.CallChainSummary;
import com.yuyu.salmonmind.codebase.api.CodebaseErrorCode;
import com.yuyu.salmonmind.codebase.api.CodebaseException;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.GitStatusResult;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService.ReadFileResult;
import com.yuyu.salmonmind.codebase.api.RepositoryObservation;
import com.yuyu.salmonmind.codebase.application.port.CallChainStorePort;
import com.yuyu.salmonmind.codebase.application.port.CatalogState;
import com.yuyu.salmonmind.codebase.application.port.CatalogStorePort;
import com.yuyu.salmonmind.codebase.application.port.RepositoryLocation;
import com.yuyu.salmonmind.codebase.application.port.StoredRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 调用链生命周期的应用编排层。
 *
 * <p>它把 Agent 的 Run-local 证据翻译成存储材料，并在所有文件写入前重新读取源码与
 * Git 状态。Web 查询只依赖 Server 文件数据，因此仓库暂时不可访问时仍能读取历史链。</p>
 */
@Service
public final class CallChainApplicationService implements AgentCallChainService, CallChainQueryService {

    private final CallChainStorePort store;
    private final RepositoryCatalogService catalog;
    private final CatalogStorePort catalogStore;
    private final RepositoryEvidenceService evidence;

    public CallChainApplicationService(
            CallChainStorePort store,
            RepositoryCatalogService catalog,
            CatalogStorePort catalogStore,
            RepositoryEvidenceService evidence
    ) {
        this.store = store;
        this.catalog = catalog;
        this.catalogStore = catalogStore;
        this.evidence = evidence;
    }

    @Override
    public CallChainReference prepare(CallChainPrepareRequest request) {
        validateRequest(request);
        StoredRepository registration = requireRepository(request.repositoryId());
        RepositoryLocation location = catalog.resolveRegistered(request.repositoryId());
        rejectDataRootOverlap(location.root());

        GitStatusResult status = evidence.gitStatus(
                new RepositoryEvidenceService.GitStatusQuery(request.repositoryId()));
        RepositoryObservation current = observation(status);
        if (!sameRunObservation(request.expectedObservation(), current)) {
            throw new CodebaseException(CodebaseErrorCode.CALL_CHAIN_REPOSITORY_CHANGED,
                    "仓库在分析期间发生变化");
        }

        List<CallChainStorePort.VerifiedNode> verified = new ArrayList<>();
        Map<String, String> ids = new HashMap<>();
        for (CallChainNodeInput input : request.nodes()) {
            String path = normalizeRelativePath(input.path());
            ReadFileResult result = evidence.readFile(new RepositoryEvidenceService.ReadFileQuery(
                    request.repositoryId(), path, input.startLine(),
                    input.endLine() - input.startLine() + 1));
            if (!path.equals(result.path()) || result.startLine() != input.startLine()
                    || result.endLine() != input.endLine() || result.content() == null
                    || result.content().isBlank()) {
                throw new CodebaseException(CodebaseErrorCode.CALL_CHAIN_EVIDENCE_INSUFFICIENT,
                        "调用链节点没有被完整源码证据覆盖");
            }
            String sourceHash = sha256(result.content());
            if (!sourceHash.equals(input.sourceHash())) {
                throw new CodebaseException(CodebaseErrorCode.CALL_CHAIN_REPOSITORY_CHANGED,
                        "源码证据在发布前发生变化");
            }
            CallChainNodeInput normalized = new CallChainNodeInput(
                    input.key().trim(), input.language().trim(), input.qualifiedSymbol().trim(),
                    normalizeSignature(input.signature()), path, input.startLine(), input.endLine(),
                    input.summary() == null ? "" : input.summary().trim(), sourceHash);
            String nodeId = nodeId(request.repositoryId(), normalized);
            if (ids.put(normalized.key(), nodeId) != null) {
                throw new CodebaseException(CodebaseErrorCode.CALL_CHAIN_DRAFT_INVALID,
                        "调用链节点 key 重复");
            }
            verified.add(new CallChainStorePort.VerifiedNode(nodeId, normalized, result.content(), sourceHash, current));
        }
        validateEdges(request.edges(), ids.keySet());
        CallChainPrepareRequest normalizedRequest = new CallChainPrepareRequest(
                request.repositoryId(), request.expectedObservation(), request.name().trim(),
                verified.stream().map(CallChainStorePort.VerifiedNode::input).toList(), request.edges(),
                request.originConversationId(), request.originAnswerEntryId());
        return store.prepare(new CallChainStorePort.PrepareInput(
                location.root(), registration.name(), normalizedRequest, verified, request.edges()));
    }

    @Override
    public CallChainReference confirm(CallChainConfirmation confirmation) {
        requireRepository(confirmation == null ? null : confirmation.repositoryId());
        return store.confirm(confirmation);
    }

    @Override
    public List<CallChainSummary> list(UUID repositoryId) {
        StoredRepository repository = requireRepository(repositoryId);
        return store.list(repositoryId, repository.name());
    }

    @Override
    public CallChainDetail detail(UUID repositoryId, UUID callChainId) {
        StoredRepository repository = requireRepository(repositoryId);
        return store.detail(repositoryId, callChainId, repository.name());
    }

    @Override
    public CallChainDetail rename(UUID repositoryId, UUID callChainId, String name) {
        StoredRepository repository = requireRepository(repositoryId);
        return store.rename(repositoryId, callChainId, repository.name(), name);
    }

    @Override
    public CallChainDetail delete(UUID repositoryId, UUID callChainId) {
        StoredRepository repository = requireRepository(repositoryId);
        return store.delete(repositoryId, callChainId, repository.name());
    }

    private StoredRepository requireRepository(UUID repositoryId) {
        if (repositoryId == null) {
            throw new CodebaseException(CodebaseErrorCode.REPOSITORY_NOT_FOUND, "仓库不存在");
        }
        CatalogState state = catalogStore.snapshot();
        StoredRepository repository = state.repositories().get(repositoryId);
        if (repository == null) {
            throw new CodebaseException(CodebaseErrorCode.REPOSITORY_NOT_FOUND, "仓库不存在");
        }
        return repository;
    }

    private void validateRequest(CallChainPrepareRequest request) {
        if (request == null || request.repositoryId() == null || request.expectedObservation() == null
                || request.name() == null || request.name().isBlank()
                || request.originConversationId() == null || request.originAnswerEntryId() == null
                || request.nodes() == null || request.edges() == null) {
            throw new CodebaseException(CodebaseErrorCode.CALL_CHAIN_DRAFT_INVALID,
                    "调用链草稿参数不完整");
        }
        if (request.name().trim().length() > 120 || request.name().contains("\n")
                || request.name().contains("\r")) {
            throw new CodebaseException(CodebaseErrorCode.CALL_CHAIN_DRAFT_INVALID,
                    "调用链名称不合法");
        }
        if (request.nodes().size() < 2 || request.nodes().size() > 12
                || request.edges().isEmpty() || request.edges().size() > 24) {
            throw new CodebaseException(CodebaseErrorCode.CALL_CHAIN_DRAFT_INVALID,
                    "调用链节点或边数量不合法");
        }
        Set<String> keys = new HashSet<>();
        for (CallChainNodeInput node : request.nodes()) {
            if (node == null || blank(node.key(), 80) || !keys.add(node.key().trim())
                    || blank(node.language(), 40) || blank(node.qualifiedSymbol(), 500)
                    || blank(node.signature(), 2_000) || blank(node.path(), 512)
                    || node.startLine() < 1 || node.endLine() < node.startLine()
                    || node.endLine() - node.startLine() + 1 > 500
                    || node.summary() == null || node.summary().length() > 500
                    || node.sourceHash() == null || !node.sourceHash().matches("[0-9a-f]{64}")) {
                throw new CodebaseException(CodebaseErrorCode.CALL_CHAIN_DRAFT_INVALID,
                        "调用链节点字段不合法");
            }
        }
        validateEdges(request.edges(), keys);
    }

    private void validateEdges(List<CallChainEdgeInput> edges, Set<String> keys) {
        Set<String> seen = new HashSet<>();
        for (CallChainEdgeInput edge : edges) {
            if (edge == null || edge.from() == null || edge.to() == null
                    || !keys.contains(edge.from()) || !keys.contains(edge.to())
                    || !seen.add(edge.from() + "\u0000" + edge.to())) {
                throw new CodebaseException(CodebaseErrorCode.CALL_CHAIN_DRAFT_INVALID,
                        "调用链边不合法");
            }
        }
    }

    private RepositoryObservation observation(GitStatusResult status) {
        return new RepositoryObservation(status.branch(), status.head(), status.metadata().dirty(),
                status.unborn(), status.detached(), status.shallow(), status.stagedCount(),
                status.unstagedCount(), status.untrackedCount(), status.sensitiveChangedCount(), Instant.now());
    }

    private boolean sameRunObservation(RepositoryObservation expected, RepositoryObservation current) {
        return java.util.Objects.equals(expected.branch(), current.branch())
                && java.util.Objects.equals(expected.head(), current.head());
    }

    private void rejectDataRootOverlap(Path repositoryRoot) {
        Path data = normalizeRealOrAbsolute(catalogStore.serverDataRoot());
        Path repository = normalizeRealOrAbsolute(repositoryRoot);
        // Server Data Root 位于 SalmonMind Repository 内时是应用自身的受控写入例外；
        // 目标根等于数据根，或目标根位于数据根内，仍然会把应用数据当作目标项目而拒绝。
        if (data.equals(repository) || repository.startsWith(data)) {
            throw new CodebaseException(CodebaseErrorCode.CALL_CHAIN_DATA_ROOT_CONFLICT,
                    "调用链数据目录不能位于目标仓库内或覆盖目标仓库");
        }
    }

    private Path normalizeRealOrAbsolute(Path path) {
        try {
            return Files.exists(path) ? path.toRealPath() : path.toAbsolutePath().normalize();
        } catch (Exception ex) {
            return path.toAbsolutePath().normalize();
        }
    }

    private static String normalizeRelativePath(String raw) {
        String path = raw.replace('\\', '/');
        while (path.startsWith("./")) path = path.substring(2);
        Path parsed = Path.of(path.replace('/', java.io.File.separatorChar)).normalize();
        if (parsed.isAbsolute() || path.isBlank() || path.equals("..") || path.startsWith("../")
                || path.contains("\0")) {
            throw new CodebaseException(CodebaseErrorCode.CALL_CHAIN_DRAFT_INVALID,
                    "调用链节点路径不合法");
        }
        return path;
    }

    private static String normalizeSignature(String value) {
        return value.trim().replaceAll("\\s+", " ");
    }

    private static String nodeId(UUID repositoryId, CallChainNodeInput input) {
        String identity = "node-v1\0" + repositoryId + "\0"
                + input.language().toLowerCase(Locale.ROOT) + "\0"
                + input.qualifiedSymbol().trim() + "\0" + normalizeSignature(input.signature());
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte item : digest) hex.append(String.format("%02x", item));
        return hex.toString();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.replace("\r\n", "\n").replace('\r', '\n')
                            .getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte item : digest) hex.append(String.format("%02x", item));
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private static boolean blank(String value, int max) {
        return value == null || value.isBlank() || value.trim().length() > max || value.indexOf('\0') >= 0;
    }
}

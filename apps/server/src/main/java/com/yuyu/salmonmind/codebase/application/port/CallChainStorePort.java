package com.yuyu.salmonmind.codebase.application.port;

import com.yuyu.salmonmind.codebase.api.CallChainConfirmation;
import com.yuyu.salmonmind.codebase.api.CallChainDetail;
import com.yuyu.salmonmind.codebase.api.CallChainEdgeInput;
import com.yuyu.salmonmind.codebase.api.CallChainNodeDetail;
import com.yuyu.salmonmind.codebase.api.CallChainNodeInput;
import com.yuyu.salmonmind.codebase.api.CallChainPrepareRequest;
import com.yuyu.salmonmind.codebase.api.CallChainReference;
import com.yuyu.salmonmind.codebase.api.CallChainSummary;
import com.yuyu.salmonmind.codebase.api.RepositoryObservation;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * 调用链聚合级文件存储合同。
 *
 * <p>实现隐藏 nodes/sources/call-chains/pending 的文件名、锁与 JSONL 校验；应用层只提交
 * 已核对的源码证据，不为每种记录创建额外 Repository 接口。</p>
 */
public interface CallChainStorePort {

    Path dataDir();

    CallChainReference prepare(PrepareInput input);

    CallChainReference confirm(CallChainConfirmation confirmation);

    List<CallChainSummary> list(UUID repositoryId, String repositoryName);

    CallChainDetail detail(UUID repositoryId, UUID callChainId, String repositoryName);

    /** 仅返回该正式 Chain 历史中实际引用的 Node Revision 及其受校验源码快照。 */
    CallChainNodeDetail revisionDetail(UUID repositoryId, UUID callChainId, String nodeId, UUID revisionId);

    CallChainDetail rename(UUID repositoryId, UUID callChainId, String repositoryName, String name);

    CallChainDetail delete(UUID repositoryId, UUID callChainId, String repositoryName);

    /** 应用层已通过 Evidence 的真实路径、行范围与 HEAD 校验的节点材料。 */
    record VerifiedNode(
            String nodeId,
            CallChainNodeInput input,
            String source,
            String sourceHash,
            RepositoryObservation observation
    ) {
    }

    record PrepareInput(
            Path repositoryRoot,
            String repositoryName,
            CallChainPrepareRequest request,
            List<VerifiedNode> nodes,
            List<CallChainEdgeInput> edges
    ) {
        public PrepareInput {
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
            edges = edges == null ? List.of() : List.copyOf(edges);
        }
    }
}

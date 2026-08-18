package com.yuyu.salmonmind.agent.infrastructure.reactagent;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;

import java.util.Collection;
import java.util.Optional;

/**
 * Agent 内部的 Lease-aware RedisSaver。它保持 Spring AI Alibaba 的 Saver 合同，
 * 只在委托成功的 get/list/put 后刷新 Lease；Conversation 模块看不到 Redis 类型或 Key。
 */
final class CheckpointLeaseSaver implements BaseCheckpointSaver {

    private final RedisSaver delegate;
    private final CheckpointLeaseManager leaseManager;

    CheckpointLeaseSaver(RedisSaver delegate, CheckpointLeaseManager leaseManager) {
        this.delegate = delegate;
        this.leaseManager = leaseManager;
    }

    /** 委托列表读取；成功后刷新当前 thread 的完整 Lease。 */
    @Override
    public Collection<Checkpoint> list(RunnableConfig config) {
        Collection<Checkpoint> result = delegate.list(config);
        refresh(config);
        return result;
    }

    /** 委托单点读取；成功后刷新当前 thread 的完整 Lease。 */
    @Override
    public Optional<Checkpoint> get(RunnableConfig config) {
        Optional<Checkpoint> result = delegate.get(config);
        refresh(config);
        return result;
    }

    /** 先由 RedisSaver 写入 Checkpoint，再刷新四类 Lease Key。 */
    @Override
    public RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) throws Exception {
        RunnableConfig result = delegate.put(config, checkpoint);
        refresh(config);
        return result;
    }

    /** 保留 RedisSaver 的 release 语义，并把四类残留 Key 缩短到有限清理窗口。 */
    @Override
    public BaseCheckpointSaver.Tag release(RunnableConfig config) throws Exception {
        String threadId = config.threadId().orElseThrow(() -> new IllegalArgumentException("threadId 不能为空"));
        String internal = leaseManager.activeInternalThreadId(threadId);
        try {
            return delegate.release(config);
        } finally {
            if (internal != null) {
                leaseManager.shortenResidual(threadId, internal);
            }
        }
    }

    private void refresh(RunnableConfig config) {
        config.threadId().ifPresent(leaseManager::refreshExisting);
    }
}

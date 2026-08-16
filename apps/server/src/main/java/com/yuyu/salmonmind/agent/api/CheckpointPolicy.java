package com.yuyu.salmonmind.agent.api;

/**
 * 一次 Agent 调用的 Checkpoint 复用策略。
 *
 * <p>默认 {@link #REUSE_IF_MATCH} 保持 Feature 002 语义：Redis 标记与期望 JSONL
 * 叶子一致才复用 Checkpoint，否则先释放再用调用方提供的完整投影重建。
 * {@link #REBUILD_FROM_PROJECTION} 表达“本轮必须从 JSONL 投影重建”的产品意图，
 * 供工具启用后的轮次使用：释放旧 Checkpoint，只保留调用方提供的投影上下文，
 * 避免 Redis Checkpoint 携带 JSONL 无法恢复的上一轮工具中间消息。
 * 不能伪造 mismatch UUID 来表达强制重建。
 */
public enum CheckpointPolicy {

    /** 默认：Redis 标记与期望 JSONL 叶子一致才复用 Checkpoint，否则释放并重建。 */
    REUSE_IF_MATCH,

    /** 强制：先释放旧 Checkpoint，再只使用调用方提供的 JSONL 模型投影重建本轮上下文。 */
    REBUILD_FROM_PROJECTION
}

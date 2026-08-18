package com.yuyu.salmonmind.agent.infrastructure.reactagent;

/**
 * 当前锁定 RedisSaver 版本的公开可观察 Keyspace。集中定义是为了让依赖升级时
 * 集成 Gate 直接失败，而不是留下只有部分 Key 过期的隐性孤儿状态。
 */
final class CheckpointKeyspace {

    static final String META_PREFIX = "graph:thread:meta:";
    static final String REVERSE_PREFIX = "graph:thread:reverse:";
    static final String CONTENT_PREFIX = "graph:checkpoint:content:";
    static final String LOCK_PREFIX = "graph:checkpoint:lock:";
    static final String LEAF_PREFIX = "salmon:agent:checkpoint-leaf:";

    private CheckpointKeyspace() {
    }

    static String meta(String externalThreadId) {
        return META_PREFIX + externalThreadId;
    }

    static String reverse(String internalThreadId) {
        return REVERSE_PREFIX + internalThreadId;
    }

    static String content(String internalThreadId) {
        return CONTENT_PREFIX + internalThreadId;
    }

    static String lock(String externalThreadId) {
        return LOCK_PREFIX + externalThreadId;
    }

    static String leaf(String externalThreadId) {
        return LEAF_PREFIX + externalThreadId;
    }
}

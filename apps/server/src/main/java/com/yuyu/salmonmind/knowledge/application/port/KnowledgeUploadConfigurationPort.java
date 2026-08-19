package com.yuyu.salmonmind.knowledge.application.port;

import java.time.Duration;

/**
 * 可恢复上传用例所需的有界配置合同。
 *
 * <p>实现可以来自 Spring 配置绑定，但 application 层不依赖具体配置框架或 Redis Adapter。
 * 所有时长、并发和大小在进入用例前已经通过代码级上下限校验。</p>
 */
public interface KnowledgeUploadConfigurationPort {

    boolean resumableEnabled();

    long resumableThresholdBytes();

    int partSizeBytes();

    int maxConcurrentParts();

    Duration sessionIdleTtl();

    Duration maxSessionLifetime();

    Duration orphanGrace();

    Duration terminalRetention();

    Duration cleanupInterval();

    int cleanupBatchSize();

    String keyPrefix();

    Duration apiCallTimeout();

    Duration apiAttemptTimeout();

    /**
     * 返回既有 Knowledge 总对象上限；实现必须拒绝阈值或 part 大于该上限。
     *
     * @param configuredMaxObjectBytes 既有 Knowledge 总对象上限，必须为正数
     * @return 经过校验的总对象上限
     * @throws IllegalArgumentException 配置越过安全边界时抛出
     */
    long maxObjectBytes(long configuredMaxObjectBytes);
}

package com.yuyu.salmonmind.persistence.redis;

import org.redisson.api.RedissonClient;

/**
 * 共享的 Redis 客户端生命周期入口。
 *
 * <p>调用方只拥有 Redisson 的技术能力，不拥有连接配置、初始化和关闭责任；
 * Checkpoint、Stream 等业务 key 仍由各自模块定义。客户端在第一次真正使用时
 * 创建，因此 Redis 未配置或暂时不可用不会阻止普通应用启动。
 */
public interface RedisClientProvider {

    /**
     * 返回可复用的 Redisson 客户端。
     *
     * @return 共享客户端
     * @throws RedisClientUnavailableException 未配置或无法建立 Redis 连接
     */
    RedissonClient client();
}

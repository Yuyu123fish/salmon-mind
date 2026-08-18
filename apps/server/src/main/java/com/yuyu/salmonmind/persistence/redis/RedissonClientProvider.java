package com.yuyu.salmonmind.persistence.redis;

import jakarta.annotation.PreDestroy;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 默认 Redis Provider：集中管理一个进程内的 Redisson 客户端。
 *
 * <p>生产 Bean 与测试构造都经过同一份连接参数和快速失败设置。初始化失败不缓存
 * 失败结果，调用方在 Redis 恢复后可以再次尝试；关闭由 Spring 统一完成。
 */
@Component
public class RedissonClientProvider implements RedisClientProvider {

    private final String redisUrl;
    private final String redisPassword;

    private volatile RedissonClient client;

    @Autowired
    public RedissonClientProvider(
            @Value("${salmon.redis.url:}") String redisUrl,
            @Value("${salmon.redis.password:}") String redisPassword
    ) {
        this.redisUrl = redisUrl;
        this.redisPassword = redisPassword;
    }

    /** 供不启动 Spring 的 Redis/Agent 集成测试复用同一 Provider。 */
    public RedissonClientProvider(String redisUrl, String redisPassword, boolean testProvider) {
        this.redisUrl = redisUrl;
        this.redisPassword = redisPassword;
    }

    @Override
    public synchronized RedissonClient client() {
        if (client != null) {
            return client;
        }
        if (!StringUtils.hasText(redisUrl)) {
            throw new RedisClientUnavailableException("Redis 未配置");
        }
        try {
            Config config = new Config();
            config.useSingleServer()
                    .setAddress(redisUrl)
                    .setPassword(StringUtils.hasText(redisPassword) ? redisPassword : null)
                    .setConnectTimeout(3000)
                    .setTimeout(3000)
                    .setRetryAttempts(1);
            client = Redisson.create(config);
            return client;
        } catch (RedisException | IllegalArgumentException ex) {
            throw new RedisClientUnavailableException("Redis 连接不可用", ex);
        }
    }

    @PreDestroy
    public synchronized void close() {
        RedissonClient current = client;
        client = null;
        if (current != null) {
            current.shutdown();
        }
    }
}

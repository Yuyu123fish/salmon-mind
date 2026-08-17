package com.yuyu.salmonmind.persistence.redis;

/** Redis 客户端尚未配置或无法建立连接时的稳定技术错误。 */
public class RedisClientUnavailableException extends RuntimeException {

    public RedisClientUnavailableException(String message) {
        super(message);
    }

    public RedisClientUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.yuyu.salmonmind.conversation.infrastructure.jsonl;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 注册 Conversation Cache 的有界配置，不创建任何 Redis 连接。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ConversationCacheProperties.class)
class ConversationSnapshotCacheConfiguration {
}

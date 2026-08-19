package com.yuyu.salmonmind.knowledge.infrastructure.redis;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 注册 Upload Session 有界配置。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KnowledgeUploadProperties.class)
class KnowledgeUploadConfiguration {
}

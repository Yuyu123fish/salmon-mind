package com.yuyu.salmonmind.knowledge.domain;

/** Knowledge Source 独立于 Ingestion Job 的来源级生命周期。 */
public enum KnowledgeSourceLifecycle {
    ACTIVE,
    DELETING
}

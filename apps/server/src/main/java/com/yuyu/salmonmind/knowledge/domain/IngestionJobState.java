package com.yuyu.salmonmind.knowledge.domain;

/** 文档处理 Job 的状态机；READY 只在索引验证和 PostgreSQL 发布都完成后出现。 */
public enum IngestionJobState {
    PENDING_DISPATCH,
    QUEUED,
    PARSING,
    EMBEDDING,
    INDEXING,
    READY,
    OCR_REQUIRED,
    FAILED;

    public boolean terminal() {
        return this == READY || this == OCR_REQUIRED || this == FAILED;
    }
}

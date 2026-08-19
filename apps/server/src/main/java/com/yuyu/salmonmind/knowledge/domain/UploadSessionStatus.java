package com.yuyu.salmonmind.knowledge.domain;

/** 可恢复上传的有限状态；COMPLETED 表示 PostgreSQL 文档已存在，不等同于 READY。 */
public enum UploadSessionStatus {
    UPLOADING,
    COMPLETING,
    COMPLETED,
    FAILED,
    ABORTED,
    EXPIRED
}

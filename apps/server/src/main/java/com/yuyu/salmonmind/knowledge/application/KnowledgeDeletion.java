package com.yuyu.salmonmind.knowledge.application;

import java.util.UUID;

/**
 * Knowledge 内部删除深模块的窄入口。它隐藏 PostgreSQL Target 以及跨存储步骤，
 * 对外只接受当前 Workspace 与文档身份，不让 Web 层自行拼接物理清理目标。
 */
interface KnowledgeDeletion {

    /** 按标记 → Elasticsearch → RustFS → PostgreSQL 的顺序幂等收束删除。 */
    void delete(UUID workspaceId, UUID documentId);
}

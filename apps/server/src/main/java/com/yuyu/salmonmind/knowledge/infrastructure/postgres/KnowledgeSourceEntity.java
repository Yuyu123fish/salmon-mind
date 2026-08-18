package com.yuyu.salmonmind.knowledge.infrastructure.postgres;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

/** Workspace 内的稳定 Knowledge Source 身份；Stage 02 只创建 DOCUMENT。 */
@TableName(value = "knowledge_sources", autoResultMap = true)
public class KnowledgeSourceEntity {

    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID workspaceId;
    private String name;
    private String kind;
    private String lifecycle;
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(UUID workspaceId) { this.workspaceId = workspaceId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getLifecycle() { return lifecycle; }
    public void setLifecycle(String lifecycle) { this.lifecycle = lifecycle; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

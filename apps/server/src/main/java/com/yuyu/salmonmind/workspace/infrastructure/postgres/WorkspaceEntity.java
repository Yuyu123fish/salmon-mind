package com.yuyu.salmonmind.workspace.infrastructure.postgres;

import java.time.Instant;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

// autoResultMap 让 PostgreSQL UUID 的 TypeHandler 在查询结果中生效
@TableName(value = "workspaces", autoResultMap = true)
public class WorkspaceEntity {

    @TableId(type = IdType.INPUT)
    private UUID id;
    private Integer singletonKey;
    private String name;
    private Instant createdAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Integer getSingletonKey() {
        return singletonKey;
    }

    public void setSingletonKey(Integer singletonKey) {
        this.singletonKey = singletonKey;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}

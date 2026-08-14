package com.yuyu.salmonmind.workspace.infrastructure.postgres;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yuyu.salmonmind.workspace.api.Workspace;
import com.yuyu.salmonmind.workspace.api.WorkspaceRegistry;
import org.springframework.stereotype.Repository;

/** Workspace PostgreSQL Adapter：封装 Mapper 与 Entity，只向 workspace::api 提供当前 Workspace。 */
@Repository
class PostgresWorkspaceRegistry implements WorkspaceRegistry {

    private final WorkspaceMapper workspaceMapper;

    PostgresWorkspaceRegistry(WorkspaceMapper workspaceMapper) {
        this.workspaceMapper = workspaceMapper;
    }

    @Override
    public Workspace current() {
        WorkspaceEntity row = workspaceMapper.selectOne(
                Wrappers.<WorkspaceEntity>lambdaQuery()
                        .eq(WorkspaceEntity::getSingletonKey, 1)
        );
        if (row == null) {
            throw new IllegalStateException("未找到唯一工作空间");
        }
        return new Workspace(row.getId(), row.getName(), row.getCreatedAt());
    }
}

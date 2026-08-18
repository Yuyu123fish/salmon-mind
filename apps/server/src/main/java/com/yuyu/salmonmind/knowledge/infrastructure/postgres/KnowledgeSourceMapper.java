package com.yuyu.salmonmind.knowledge.infrastructure.postgres;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Mapper;

import java.util.UUID;

@Mapper
/** Knowledge Source 的 MyBatis 映射边界。 */
interface KnowledgeSourceMapper extends BaseMapper<KnowledgeSourceEntity> {

    /** 锁住当前 Workspace 的精确 Source，串行化删除与所有正常写路径。 */
    @Select("""
            SELECT id, workspace_id, name, kind, lifecycle, created_at
            FROM knowledge_sources
            WHERE id = #{sourceId} AND workspace_id = #{workspaceId}
            FOR UPDATE
            """)
    KnowledgeSourceEntity selectForUpdate(
            @Param("workspaceId") UUID workspaceId,
            @Param("sourceId") UUID sourceId
    );

    /** 按 Source 身份加锁，供 Worker 在不知道 Workspace 时执行写入 Fence。 */
    @Select("""
            SELECT id, workspace_id, name, kind, lifecycle, created_at
            FROM knowledge_sources
            WHERE id = #{sourceId}
            FOR UPDATE
            """)
    KnowledgeSourceEntity selectForUpdateById(@Param("sourceId") UUID sourceId);
}

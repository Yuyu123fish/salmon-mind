package com.yuyu.salmonmind.workspace.infrastructure.postgres;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
interface WorkspaceMapper extends BaseMapper<WorkspaceEntity> {
}

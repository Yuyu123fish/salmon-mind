package com.yuyu.salmonmind.knowledge.infrastructure.postgres;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
/** Knowledge Source 的 MyBatis 映射边界。 */
interface KnowledgeSourceMapper extends BaseMapper<KnowledgeSourceEntity> {
}

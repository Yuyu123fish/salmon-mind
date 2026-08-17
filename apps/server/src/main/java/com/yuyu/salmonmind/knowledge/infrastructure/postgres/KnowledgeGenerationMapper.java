package com.yuyu.salmonmind.knowledge.infrastructure.postgres;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
/** Index Generation 元数据的 MyBatis 映射边界。 */
interface KnowledgeGenerationMapper extends BaseMapper<KnowledgeGenerationEntity> {
}

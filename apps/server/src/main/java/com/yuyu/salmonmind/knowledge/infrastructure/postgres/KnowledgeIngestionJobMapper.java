package com.yuyu.salmonmind.knowledge.infrastructure.postgres;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
/** Ingestion Job 的 MyBatis 映射边界。 */
interface KnowledgeIngestionJobMapper extends BaseMapper<KnowledgeIngestionJobEntity> {
}

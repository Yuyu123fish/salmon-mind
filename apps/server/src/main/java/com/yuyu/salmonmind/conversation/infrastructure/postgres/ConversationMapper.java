package com.yuyu.salmonmind.conversation.infrastructure.postgres;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
interface ConversationMapper extends BaseMapper<ConversationEntity> {
}

package com.wzh.agentdemo.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzh.agentdemo.common.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {
}
package com.wzh.agentdemo.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzh.agentdemo.common.entity.ChatSession;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSession> {
}
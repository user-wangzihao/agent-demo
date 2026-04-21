package com.wzh.agentdemo.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzh.agentdemo.common.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
}
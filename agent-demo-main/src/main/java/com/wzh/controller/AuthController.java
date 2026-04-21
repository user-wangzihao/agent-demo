package com.wzh.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wzh.common.Result;
import com.wzh.agentdemo.common.entity.SysUser;
import com.wzh.entity.dto.LoginRequest;
import com.wzh.entity.dto.LoginResponse;
import com.wzh.agentdemo.common.mapper.SysUserMapper;
import com.wzh.utils.TokenUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "认证管理")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final SysUserMapper sysUserMapper;

    @Operation(summary = "用户登录")
    @PostMapping("/login")
    public Result<LoginResponse> login(@RequestBody LoginRequest request) {
        SysUser user = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getUsername, request.getUsername()));

        if (user == null || !user.getPassword().equals(request.getPassword())) {
            return Result.error(401, "用户名或密码错误");
        }

        LoginResponse resp = new LoginResponse();
        resp.setUserId(user.getId());
        resp.setUsername(user.getUsername());
        resp.setNickname(user.getNickname());
        resp.setRole(user.getRole());
        resp.setToken(TokenUtil.generate(user));

        return Result.success(resp);
    }

    @Operation(summary = "获取当前用户信息")
    @GetMapping("/info")
    public Result<LoginResponse> info() {
        // 这个接口在拦截器范围外时不会有 UserContext，放在 /api/auth 下但前端会带 token
        return Result.success(null);
    }
}
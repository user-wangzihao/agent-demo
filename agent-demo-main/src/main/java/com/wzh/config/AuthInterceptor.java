package com.wzh.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzh.common.UserContext;
import com.wzh.utils.TokenUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;

@Slf4j
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 放行 OPTIONS 预检请求
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String token = request.getHeader("Authorization");
        if (token == null || token.isBlank()) {
            writeError(response, 401, "未登录");
            return false;
        }

        // 去除 Bearer 前缀
        if (token.startsWith("Bearer ")) {
            token = token.substring(7);
        }

        TokenUtil.TokenInfo info = TokenUtil.parse(token);
        if (info == null) {
            writeError(response, 401, "token无效，请重新登录");
            return false;
        }

        // 管理员页面权限检查：/api/document/** 和 /api/file/** 和 /api/agent/learn/** 仅管理员可访问
        String uri = request.getRequestURI();
        if (isAdminOnly(uri) && !"admin".equals(info.role)) {
            writeError(response, 403, "权限不足");
            return false;
        }

        UserContext.set(info);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }

    public boolean isAdminOnly(String uri) {
        return uri.startsWith("/api/document")
                || uri.startsWith("/api/file")
                || uri.startsWith("/api/agent/learn")
                || uri.startsWith("/api/admin");  // B4: /api/admin/** 全锁 admin (含 dashboard)
    }

    private void writeError(HttpServletResponse response, int code, String message) throws Exception {
        response.setStatus(code);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Map.of("code", code, "message", message)));
    }
}
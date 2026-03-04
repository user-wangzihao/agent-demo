package com.wzh.utils;



import com.wzh.entity.SysUser;

import java.util.Base64;

/**
 * 极简 Token 工具（不做加密，仅用于区分用户）
 */
public class TokenUtil {

    public static String generate(SysUser user) {
        String raw = user.getId() + ":" + user.getUsername() + ":" + user.getRole();
        return Base64.getEncoder().encodeToString(raw.getBytes());
    }

    public static TokenInfo parse(String token) {
        try {
            String raw = new String(Base64.getDecoder().decode(token));
            String[] parts = raw.split(":");
            if (parts.length != 3) return null;
            TokenInfo info = new TokenInfo();
            info.userId = Long.parseLong(parts[0]);
            info.username = parts[1];
            info.role = parts[2];
            return info;
        } catch (Exception e) {
            return null;
        }
    }

    public static class TokenInfo {
        public Long userId;
        public String username;
        public String role;
    }
}
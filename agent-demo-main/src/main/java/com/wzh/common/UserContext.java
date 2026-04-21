package com.wzh.common;

import com.wzh.utils.TokenUtil;

/**
 * 当前登录用户上下文（基于 ThreadLocal）
 */
public class UserContext {

    private static final ThreadLocal<TokenUtil.TokenInfo> CURRENT_USER = new ThreadLocal<>();

    public static void set(TokenUtil.TokenInfo user) {
        CURRENT_USER.set(user);
    }

    public static TokenUtil.TokenInfo get() {
        return CURRENT_USER.get();
    }

    public static Long getUserId() {
        TokenUtil.TokenInfo info = CURRENT_USER.get();
        return info != null ? info.userId : null;
    }

    public static String getRole() {
        TokenUtil.TokenInfo info = CURRENT_USER.get();
        return info != null ? info.role : null;
    }

    public static boolean isAdmin() {
        return "admin".equals(getRole());
    }

    public static void clear() {
        CURRENT_USER.remove();
    }
}

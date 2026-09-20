package com.fitness.common;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Controller 基类 — 提供从 JWT 拦截器获取用户信息的通用方法
 */
public abstract class BaseController {

    /** 从 JWT 拦截器获取当前用户ID */
    protected Long getUserId(HttpServletRequest request) {
        return (Long) request.getAttribute("userId");
    }

    /** 从 JWT 拦截器获取当前 Token */
    protected String getToken(HttpServletRequest request) {
        return (String) request.getAttribute("token");
    }
}

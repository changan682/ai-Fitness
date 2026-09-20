package com.fitness.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitness.cache.TokenBlacklistService;
import com.fitness.exception.ErrorCode;
import com.fitness.util.JwtUtil;
import com.fitness.util.TraceIdUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JWT 鉴权拦截器 — 从 Authorization Header 提取并校验 Token
 * <p>
 * 校验通过后将 userId 存入 request attribute，后续 Controller 通过
 * {@link com.fitness.common.BaseController#getUserId} 获取当前用户ID。
 * 同时做两级失效校验：
 * <ol>
 *   <li><b>jti 黑名单</b>：登出 / 刷新后作废的那一个 Token（多设备互不覆盖）；</li>
 *   <li><b>iat 水位线</b>：改密码 / 强制下线后作废该用户全部旧 Token。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final TokenBlacklistService tokenBlacklistService;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        // CORS 预检请求直接放行：
        // 浏览器预检（OPTIONS）不会携带 Authorization，若在此处鉴权，
        // 所有跨域的 POST/PUT/DELETE 都会因预检 401 而失败（真正的请求根本发不出去）。
        // 预检的实际鉴权由后续真实请求承担，因此放行不降低安全性。
        if (CorsUtils.isPreFlightRequest(request)) {
            return true;
        }

        // 提取 Token
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return writeTokenInvalid(response, ErrorCode.TOKEN_INVALID.getMsg());
        }
        String token = authHeader.substring(7);

        // 校验 Token 有效性（签名 + 过期）
        if (!jwtUtil.validateToken(token)) {
            return writeTokenInvalid(response, ErrorCode.TOKEN_INVALID.getMsg());
        }

        Long userId = jwtUtil.getUserIdFromToken(token);

        // 按 jti 粒度检查黑名单（已登出 / 已刷新的 Token）。
        // 为什么不用 userId 作 key：同一用户多设备登录时会把记录写进同一个 key，
        // 后登出的覆盖先登出的，先登出的 Token 又变回可用（鉴权漏洞）。
        String jti = jwtUtil.getJti(token);
        if (jti == null || jti.isBlank()) {
            // 本系统签发的 Token 一律带 jti；缺失说明是老版本或伪造 Token，直接拒绝
            return writeTokenInvalid(response, ErrorCode.TOKEN_INVALID.getMsg());
        }
        if (tokenBlacklistService.contains(jti)) {
            return writeTokenInvalid(response, "Token已失效，请重新登录");
        }

        // 检查「失效水位线」：改密码 / 强制下线会作废该用户此前签发的全部 Token。
        // jti 黑名单只能逐个拉黑已知 Token，无法枚举其它设备的 jti，故用 iat 水位线兜底。
        Date issuedAt = jwtUtil.getIssuedAt(token);
        if (tokenBlacklistService.isIssuedBeforeWatermark(
                userId, issuedAt == null ? null : issuedAt.getTime())) {
            return writeTokenInvalid(response, "密码已修改，请重新登录");
        }

        // 将 userId 和 token 存入 request，供后续使用
        request.setAttribute("userId", userId);
        request.setAttribute("token", token);
        // 写入 MDC：该请求后续所有日志自动带上 userId（规范日志示例中的 userId 字段）
        MDC.put(TraceIdUtil.MDC_USER_ID, String.valueOf(userId));
        return true;
    }

    /**
     * 返回鉴权失败响应
     * <p>
     * HTTP 状态码保持 200，业务码用 9001：与全局异常处理保持一致，
     * 前端 axios 在 2xx 分支统一按 body.code 分发（9001 → 清 Token 跳登录页），
     * 若返回 HTTP 401 会落进网络错误分支，前端拿不到「该跳登录」的语义。
     */
    private boolean writeTokenInvalid(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", ErrorCode.TOKEN_INVALID.getCode());
        body.put("msg", message);
        body.put("data", null);

        response.getWriter().write(objectMapper.writeValueAsString(body));
        return false;
    }
}

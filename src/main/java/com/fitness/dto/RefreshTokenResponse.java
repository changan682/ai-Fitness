package com.fitness.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Token 刷新响应 — 对应提示词 1.7 的 data 结构
 * <p>
 * 规范中该接口 data 只有 token / expiresAt，故不复用 LoginResponse
 * （后者会多带一个 user 对象，与契约不符）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefreshTokenResponse {

    /** 新的 JWT Token */
    private String token;

    /** 新 Token 过期时间，yyyy-MM-dd HH:mm:ss */
    private String expiresAt;
}

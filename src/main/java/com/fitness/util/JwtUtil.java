package com.fitness.util;

import com.fitness.config.JwtProperties;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 工具类 — Token 生成、解析、校验
 * <p>
 * 密钥通过 application.yml 的 jwt.secret 配置注入（见 {@link JwtProperties}），至少 256 位（32 字节）
 */
@Slf4j
@Component
public class JwtUtil {

    private final SecretKey key;
    private final int expirationDays;

    public JwtUtil(JwtProperties jwtProperties) {
        String secret = jwtProperties.getSecret();
        // 确保密钥至少 256 位，不足则右补 0
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(keyBytes, 0, padded, 0, keyBytes.length);
            keyBytes = padded;
        }
        this.key = Keys.hmacShaKeyFor(keyBytes);
        this.expirationDays = jwtProperties.getExpirationDays();
    }

    /**
     * 生成 JWT Token
     * <p>
     * 每个 Token 都带 <b>jti</b>（唯一 ID）与 <b>iat</b>（签发时间），它们是登出黑名单的基础：
     * <ul>
     *   <li>jti：登出时按 jti 粒度拉黑，多设备登录互不覆盖（用 userId 作 key 会让后登出的
     *       覆盖先登出的，先登出的 Token 又变可用 —— 属于鉴权漏洞）；</li>
     *   <li>iat：配合 {@code user:token:invalid-after:{userId}} 水位线，支持「改密码后
     *       一次性作废该用户所有已签发 Token」。</li>
     * </ul>
     */
    public String generateToken(Long userId, String phone) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationDays * 24L * 3600_000);

        return Jwts.builder()
                .id(UUID.randomUUID().toString())   // jti：Token 唯一 ID（登出黑名单的 key）
                .subject(String.valueOf(userId))
                .claim("phone", phone)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    /**
     * 获取 Token 的 jti（唯一 ID）
     *
     * @return jti；老版本 Token 没有该 claim 时返回 null
     */
    public String getJti(String token) {
        return parseClaims(token).getId();
    }

    /** 获取 Token 的签发时间（iat）；缺省返回 null */
    public Date getIssuedAt(String token) {
        return parseClaims(token).getIssuedAt();
    }

    /** 从 Token 中解析用户 ID */
    public Long getUserIdFromToken(String token) {
        return Long.parseLong(parseClaims(token).getSubject());
    }

    /** 校验 Token 是否有效（未过期 + 签名正确） */
    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Token 校验失败: {}", e.getMessage());
            return false;
        }
    }

    /** 获取 Token 剩余有效期（毫秒），已过期返回 0 */
    public long getRemainingTime(String token) {
        try {
            Date expiration = parseClaims(token).getExpiration();
            long remaining = expiration.getTime() - System.currentTimeMillis();
            return Math.max(remaining, 0);
        } catch (JwtException e) {
            return 0;
        }
    }

    /** 获取 Token 的过期时间 */
    public Date getExpirationDateFromToken(String token) {
        return parseClaims(token).getExpiration();
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}

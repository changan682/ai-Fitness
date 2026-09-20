package com.fitness.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 配置属性 — 对应 application.yml 的 {@code jwt.*}
 * <p>
 * 与 JwtUtil 里散落的 {@code @Value} 相比，集中绑定能：
 * 一处看到全部配置项、支持 IDE 补全、便于测试覆盖默认值。
 */
@Data
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    /**
     * 签名密钥，至少 256 位（32 字节）。
     * ⚠️ 生产环境务必通过环境变量 JWT_SECRET 覆盖默认值，否则 Token 可被伪造。
     */
    private String secret;

    /** Token 有效期（天），规范 1.2 要求默认 7 天 */
    private int expirationDays = 7;
}

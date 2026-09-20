package com.fitness.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * JWT 配置类（规范「配置类」清单中的 JwtConfig）
 * <p>
 * 只负责启用 {@link JwtProperties} 的属性绑定；
 * Token 的生成/校验逻辑在 {@link com.fitness.util.JwtUtil}。
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {
}

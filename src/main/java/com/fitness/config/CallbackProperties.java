package com.fitness.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 回调签名配置 — 对应 {@code application.yml} 的 {@code callback.*}
 * <p>
 * 与 Python 侧 {@code python-agent/.env} 的 {@code HMAC_SECRET} 必须完全一致，
 * 否则所有回调都会被判为「签名校验失败」（9002）。
 */
@Data
@ConfigurationProperties(prefix = "callback")
public class CallbackProperties {

    /**
     * Python → Java 回调的 HMAC-SHA256 共享密钥
     * <p>
     * ⚠️ 生产环境必须通过环境变量 {@code HMAC_SECRET} 覆盖默认值，否则任何人都能伪造回调
     * （回调接口是内网免鉴权路径，签名是唯一的身份凭证）。
     */
    private String hmacSecret;
}

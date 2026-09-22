package com.fitness.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置 — 注册 JWT 鉴权拦截器
 * <p>
 * 跨域配置见 {@link CorsConfig}（独立成类，与规范要求的 config 清单一致）。
 */
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(AvatarProperties.class)
public class WebConfig implements WebMvcConfigurer {

    /**
     * JWT 白名单（免鉴权路径）—— 集中管理，便于核对与测试
     *
     * <h3>头像那条为什么是单星号 {@code *} 而不是 {@code /**}</h3>
     * 头顶像有**两个**接口：
     * <ul>
     *   <li>{@code GET /api/v1/user/avatar/{userId}} —— 读取，必须免鉴权
     *       （浏览器给 {@code <img src>} 发请求不带 Authorization 头）；</li>
     *   <li>{@code POST /api/v1/user/avatar} —— 上传，**必须鉴权**，
     *       用户 id 只能从 Token 里取。</li>
     * </ul>
     * 而 Ant 风格里 {@code /api/v1/user/avatar/**} **同样匹配 {@code /api/v1/user/avatar} 本身**，
     * 于是上传接口会被一起放行 —— 联调时的表现是 500/9999
     * （{@code getUserId(request)} 拿不到用户 → 拿 null 去查库），
     * 而更坏的情况是"未登录也能写"。{@code *} 只匹配**恰好一段**路径，
     * 因此 {@code /api/v1/user/avatar/36} 放行、{@code /api/v1/user/avatar} 仍然拦。
     * <p>
     * 这一条由 {@code WebConfigAuthWhitelistTest} 用真实的 AntPathMatcher 锁住。
     */
    public static final String[] JWT_EXCLUDE_PATTERNS = {
            "/api/v1/user/register",
            "/api/v1/user/login",
            // 头像**读取**免鉴权：<img> 不带 Token；只返回图片字节、不含隐私字段
            "/api/v1/user/avatar/*",
            "/api/ai/callback/**",       // Python 回调走 HMAC 验签，第7周实现
            "/api/v1/health"             // 健康检查供 Docker/K8s 探针调用，无需鉴权
    };

    private final JwtInterceptor jwtInterceptor;

    /** 注册 JWT 鉴权拦截器 — 仅拦截 /api/**，白名单见 {@link #JWT_EXCLUDE_PATTERNS} */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/api/**")          // 仅拦截 API 路径（Swagger等非/api路径不受影响）
                .excludePathPatterns(JWT_EXCLUDE_PATTERNS);
        // 说明：/api/v1/food-library/** 已移出白名单 —— 规范 5.1 标注该接口需携带
        // Authorization: Bearer <token>，此前整体免鉴权与接口清单不一致。
    }
}

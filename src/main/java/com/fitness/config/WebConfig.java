package com.fitness.config;

import lombok.RequiredArgsConstructor;
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
public class WebConfig implements WebMvcConfigurer {

    private final JwtInterceptor jwtInterceptor;

    /** 注册 JWT 鉴权拦截器 — 仅拦截 /api/**，白名单在此集中管理 */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/api/**")          // 仅拦截 API 路径（Swagger等非/api路径不受影响）
                .excludePathPatterns(
                        "/api/v1/user/register",
                        "/api/v1/user/login",
                        "/api/ai/callback/**",       // Python 回调走 HMAC 验签，第7周实现
                        "/api/v1/health"             // 健康检查供 Docker/K8s 探针调用，无需鉴权
                );
        // 说明：/api/v1/food-library/** 已移出白名单 —— 规范 5.1 标注该接口需携带
        // Authorization: Bearer <token>，此前整体免鉴权与接口清单不一致。
    }
}

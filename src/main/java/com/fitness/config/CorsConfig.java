package com.fitness.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS 跨域配置 — 允许 React 开发服务器访问（规范第十一章第 4 条）
 * <p>
 * React 开发服务器默认端口为 {@code http://localhost:5173}（Vite）。
 * 这里用 {@code allowedOriginPatterns} 而非 {@code allowedOrigins}：
 * 因为 {@code allowCredentials(true)} 时不能用通配符 {@code *}，
 * 而 patterns 既满足「精确放行 localhost」又能兼容 Vite 端口被占用后自动换端口的情况
 * （5174/5175…）。生产环境应改为具体域名。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("X-Trace-Id")
                .allowCredentials(true)
                .maxAge(3600);
    }
}

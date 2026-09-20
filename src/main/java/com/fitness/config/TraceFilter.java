package com.fitness.config;

import com.fitness.util.TraceIdUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 链路追踪过滤器 — 提示词第十章强制要求
 * <p>
 * 职责：
 * <ol>
 *   <li>从 {@code X-Trace-Id} Header 提取 traceId（React 传入），无或非法则生成新的</li>
 *   <li>写入 MDC，使该请求内所有日志自动携带 traceId / userId</li>
 *   <li>回写响应头，便于前端与后端日志按同一 traceId 对齐排查</li>
 *   <li>记录请求开始/结束（含耗时），满足「每个 HTTP 请求/响应」的关键日志节点要求</li>
 * </ol>
 * 顺序设为最高优先级：必须早于 JwtInterceptor（后者会向 MDC 写入 userId），
 * 且 {@code finally} 中清理 MDC，避免线程复用导致的 traceId 串味。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = request.getHeader(TraceIdUtil.TRACE_ID_HEADER);
        if (!TraceIdUtil.isValid(traceId)) {
            traceId = TraceIdUtil.generate();
        }
        MDC.put(TraceIdUtil.MDC_TRACE_ID, traceId);
        response.setHeader(TraceIdUtil.TRACE_ID_HEADER, traceId);

        long start = System.currentTimeMillis();
        String method = request.getMethod();
        String uri = request.getRequestURI();

        try {
            log.info("HTTP请求开始: {} {}", method, uri);
            filterChain.doFilter(request, response);
        } finally {
            log.info("HTTP请求结束: {} {} -> status={}, durationMs={}",
                    method, uri, response.getStatus(), System.currentTimeMillis() - start);
            // 必须在 finally 清理：Tomcat 线程池复用线程，残留 MDC 会污染下一个请求
            MDC.remove(TraceIdUtil.MDC_TRACE_ID);
            MDC.remove(TraceIdUtil.MDC_USER_ID);
        }
    }

    /** 静态资源与健康探针不产生访问日志，避免噪音 */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.startsWith("/swagger-ui")
                || uri.startsWith("/v3/api-docs")
                || uri.startsWith("/doc.html")
                || uri.startsWith("/webjars");
    }
}

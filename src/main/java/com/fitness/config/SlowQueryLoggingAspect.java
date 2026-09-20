package com.fitness.config;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 慢查询日志切面 — 规范第十章「关键日志节点」要求记录数据库慢查询（&gt;1s）
 * <p>
 * 为什么用 AOP 而不是开 Hibernate 的 {@code hibernate.log_slow_query}：
 * <ul>
 *   <li>后者依赖 Hibernate 统计监听器，开关语义在不同版本间有差异，容易「配了但没生效」；</li>
 *   <li>切面方式对 Repository 方法整体计时，能把「N+1 次小查询累积成大耗时」也暴露出来，
 *       而且不依赖任何额外依赖（spring-boot-starter-aop 已在 pom 中）。</li>
 * </ul>
 * 只对 {@code com.fitness.repository} 包下的方法生效，异常照常抛出，不影响业务语义。
 */
@Slf4j
@Aspect
@Component
public class SlowQueryLoggingAspect {

    /** 慢查询阈值（毫秒），与规范「&gt;1s」一致 */
    private static final long SLOW_QUERY_THRESHOLD_MS = 1000L;

    @Around("execution(* com.fitness.repository..*(..))")
    public Object logSlowQuery(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.currentTimeMillis();
        try {
            return joinPoint.proceed();
        } finally {
            long cost = System.currentTimeMillis() - start;
            if (cost >= SLOW_QUERY_THRESHOLD_MS) {
                log.warn("数据库慢查询: {}.{}, durationMs={}, args={}",
                        joinPoint.getSignature().getDeclaringType().getSimpleName(),
                        joinPoint.getSignature().getName(),
                        cost,
                        summarizeArgs(joinPoint.getArgs()));
            }
        }
    }

    /**
     * 参数摘要 — 只保留可安全打印的简单类型，避免把整实体（可能含敏感字段）打进日志
     */
    private String summarizeArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return "[]";
        }
        return Arrays.stream(args)
                .map(a -> {
                    if (a == null) return "null";
                    if (a instanceof CharSequence || a instanceof Number
                            || a instanceof Boolean || a instanceof Enum) {
                        return a.toString();
                    }
                    if (a instanceof java.time.temporal.Temporal || a instanceof java.util.Collection) {
                        return a.toString();
                    }
                    // 复杂对象只打印类型名，避免日志泄漏与体积膨胀
                    return a.getClass().getSimpleName();
                })
                .toList()
                .toString();
    }
}

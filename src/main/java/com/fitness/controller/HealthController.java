package com.fitness.controller;

import com.fitness.common.Result;
import com.fitness.dto.HealthResponse;
import com.fitness.service.HealthCheckService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查模块 Controller — /api/v1/health
 * <p>
 * 该接口在 WebConfig 中已加入 JWT 白名单（无需鉴权），
 * 供 Docker healthcheck / K8s liveness probe / 监控告警使用。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class HealthController {

    private final HealthCheckService healthCheckService;

    /** Java 应用健康检查 — 提示词 10.1 */
    @GetMapping("/health")
    public Result<HealthResponse> health() {
        return Result.ok(healthCheckService.check());
    }
}

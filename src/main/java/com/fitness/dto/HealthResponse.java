package com.fitness.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 健康检查响应 — 对应提示词 10.1
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HealthResponse {

    /** 总体状态：UP / DOWN */
    private String status;

    /** 检查时间，由 JacksonConfig 统一格式化为 yyyy-MM-dd HH:mm:ss */
    private LocalDateTime timestamp;

    /** 各依赖服务状态：mysql / redis / rabbitmq / pythonAgent */
    private Map<String, String> services;
}

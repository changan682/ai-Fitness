package com.fitness.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 训练总结响应数据 — Python {@code /agent/v1/summary} 的 data 部分
 * <p>
 * {@code generated_at} 刻意用 String 而不是 LocalDateTime：
 * Python 的 datetime 序列化是 ISO8601（如 {@code 2026-07-30T15:35:00}），
 * 而本项目 Java 侧全局把 LocalDateTime 配成了 {@code yyyy-MM-dd HH:mm:ss}，
 * 直接声明成 LocalDateTime 会因格式不匹配而反序列化失败。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PySummaryData {

    /** Markdown 格式的训练总结 */
    private String summary;

    /** 生成时间，ISO8601 */
    @JsonProperty("generated_at")
    private String generatedAt;
}

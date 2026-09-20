package com.fitness.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 训练智能总结响应 — 对应规范 7.1
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiSummaryResponse {

    /** Markdown 格式的训练总结 */
    private String summary;

    /** 是否来自缓存（true=未调用大模型，前端可据此提示「已缓存」并允许手动重新生成） */
    private boolean cached;

    /** 生成时间，由 JacksonConfig 统一格式化为 yyyy-MM-dd HH:mm:ss */
    private LocalDateTime generatedAt;
}

package com.fitness.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 身体状态主动问询响应 — 对应前端契约（体验优化批次 D）
 *
 * <p>与其它 AI 接口一样，**降级必须在响应里看得见**：
 * {@link #dataSource} 为 {@code rule_based} 时表示内容由阈值规则生成、未经大模型，
 * 前端会据此显示「规则生成（未使用大模型）」。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AiBodyConsultResponse {

    /** 2-3 句整体判断（含具体数字） */
    private String assessment;

    /** 体重/围度的走向与幅度 */
    private String trendSummary;

    /** 主动追问（最多 3 条） */
    private List<Question> questions;

    /** 建议（最多 3 条） */
    private List<Suggestion> suggestions;

    /** 风险/提示（level = info / warn / high） */
    private List<RiskFlag> riskFlags;

    /** {@code llm} = 大模型生成；{@code rule_based} = 规则兜底（未使用大模型） */
    private String dataSource;

    private Boolean degraded;

    private String degradationReason;

    private LocalDateTime generatedAt;

    /** 是否命中服务端缓存（同一份数据不重复调用大模型） */
    private Boolean cached;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Question {
        private String id;
        private String text;
        /** 为什么问这个 —— 让用户知道不是随机提问 */
        private String why;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Suggestion {
        private String title;
        private String detail;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RiskFlag {
        private String level;
        private String text;
    }
}

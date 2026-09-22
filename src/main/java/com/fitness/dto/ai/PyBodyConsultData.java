package com.fitness.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 身体状态问询的 Python 响应 data（snake_case）— 体验优化批次 D
 * <p>
 * ⚠️ 每个字段都要显式 {@code @JsonProperty}：本类带
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)}，没声明的字段会被**静默丢弃**，
 * 表现是"后端返回了但前端永远拿不到"（项目在 data_source 上踩过一次）。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PyBodyConsultData {

    private String assessment;

    @JsonProperty("trend_summary")
    private String trendSummary;

    private List<Question> questions;
    private List<Suggestion> suggestions;

    @JsonProperty("risk_flags")
    private List<RiskFlag> riskFlags;

    /** {@code llm} = 大模型生成；{@code rule_based} = 规则兜底（未使用大模型） */
    @JsonProperty("data_source")
    private String dataSource;

    private Boolean degraded;

    @JsonProperty("degradation_reason")
    private String degradationReason;

    @JsonProperty("generated_at")
    private String generatedAt;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Question {
        private String id;
        private String text;
        private String why;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Suggestion {
        private String title;
        private String detail;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RiskFlag {
        private String level;
        private String text;
    }
}

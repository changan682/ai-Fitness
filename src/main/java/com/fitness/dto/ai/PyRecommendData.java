package com.fitness.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 动作推荐响应数据 — Python {@code /agent/v1/recommend} 的 data 部分
 * <p>
 * 对应规范 models.py 的 {@code RecommendResponse} + {@code ActionRecommendation}。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PyRecommendData {

    private List<Recommendation> recommendations;

    @JsonProperty("generated_at")
    private String generatedAt;

    /** 单个推荐动作 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Recommendation {

        @JsonProperty("action_name")
        private String actionName;

        @JsonProperty("target_muscle")
        private String targetMuscle;

        /** 重点刺激部位，如「上胸」 */
        @JsonProperty("focus_area")
        private String focusArea;

        @JsonProperty("recommended_sets")
        private String recommendedSets;

        @JsonProperty("recommended_reps")
        private String recommendedReps;

        /** 难度：新手/进阶/高级 */
        private String difficulty;

        /** 动作要点 */
        private String notes;

        /** 所需器械 */
        private List<String> equipment;
    }
}

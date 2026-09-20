package com.fitness.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 动作智能推荐响应 — 对应规范 7.2
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiRecommendResponse {

    private List<Recommendation> recommendations;

    private LocalDateTime generatedAt;

    /** 单个推荐动作 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Recommendation {
        private String actionName;
        private String targetMuscle;
        /** 重点刺激部位，如「上胸」 */
        private String focusArea;
        private String recommendedSets;
        private String recommendedReps;
        /** 难度：新手/进阶/高级 */
        private String difficulty;
        private String notes;
        private List<String> equipment;
    }
}

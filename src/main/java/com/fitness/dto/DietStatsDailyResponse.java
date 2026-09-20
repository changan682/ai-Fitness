package com.fitness.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 每日热量区间统计响应（提示词 4.3）
 * {@code GET /api/v1/diet/stats/daily?startDate&endDate}
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DietStatsDailyResponse {

    /** 区间内每一天的统计 */
    private List<DailyStat> list;

    /** 区间日均热量（仅统计有记录的天数） */
    private BigDecimal avgCalories;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DailyStat {
        private String date;
        private BigDecimal totalCalories;
        private int mealCount;
    }
}

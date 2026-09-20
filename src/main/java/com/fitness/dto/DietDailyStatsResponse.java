package com.fitness.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/** 每日饮食统计响应 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DietDailyStatsResponse {
    private String date;
    /** 各餐次合计 */
    private BigDecimal totalCalories;
    /** 餐次明细 */
    private List<DietRecordResponse> records;
    /** 按餐次分组统计 */
    private MealSummary breakfast;
    private MealSummary lunch;
    private MealSummary dinner;
    private MealSummary snack;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MealSummary {
        private int itemCount;
        private BigDecimal calories;
    }
}

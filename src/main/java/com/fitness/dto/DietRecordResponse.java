package com.fitness.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 饮食记录响应 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DietRecordResponse {
    private Long id;
    private Long userId;
    private LocalDate recordDate;
    private String mealType;
    private String foodName;
    private Integer weightG;
    private BigDecimal caloriesKcal;
    private LocalDateTime createdAt;
}

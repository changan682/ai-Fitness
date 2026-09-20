package com.fitness.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 身体数据响应 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BodyMetricResponse {
    private Long id;
    private Long userId;
    private LocalDate recordDate;
    private BigDecimal weightKg;
    private BigDecimal waistCm;
    private BigDecimal armCm;
    private BigDecimal legCm;
    private BigDecimal bodyFatPct;
    private LocalDateTime createdAt;
}

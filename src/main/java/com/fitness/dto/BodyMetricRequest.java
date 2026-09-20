package com.fitness.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.math.BigDecimal;

/** 身体数据录入请求 — 对应提示词 3.1 */
@Data
public class BodyMetricRequest {

    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "记录日期格式需为yyyy-MM-dd")
    private String recordDate;

    /** 体重(kg) — 规范：30-300kg */
    @NotNull(message = "体重不能为空")
    @DecimalMin(value = "30", message = "体重范围30-300kg")
    @DecimalMax(value = "300", message = "体重范围30-300kg")
    private BigDecimal weightKg;

    @DecimalMin(value = "20", message = "腰围范围20-200cm")
    @DecimalMax(value = "200", message = "腰围范围20-200cm")
    private BigDecimal waistCm;

    @DecimalMin(value = "10", message = "臂围范围10-100cm")
    @DecimalMax(value = "100", message = "臂围范围10-100cm")
    private BigDecimal armCm;

    @DecimalMin(value = "20", message = "腿围范围20-150cm")
    @DecimalMax(value = "150", message = "腿围范围20-150cm")
    private BigDecimal legCm;

    /** 体脂率(%) — 规范：3-60% */
    @DecimalMin(value = "3", message = "体脂率范围3-60%")
    @DecimalMax(value = "60", message = "体脂率范围3-60%")
    private BigDecimal bodyFatPct;
}

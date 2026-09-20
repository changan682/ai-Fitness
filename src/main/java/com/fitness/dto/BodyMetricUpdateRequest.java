package com.fitness.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 修改当天身体数据请求 — 所有字段均可选，传了就更新
 * <p>
 * 与新增接口（weightKg 必填）区分，满足「只改腰围」等部分更新场景；
 * 范围约束与新增保持一致，避免绕过校验写入非法值。
 */
@Data
public class BodyMetricUpdateRequest {

    /** 体重(kg) */
    @DecimalMin(value = "30", message = "体重范围30-300kg")
    @DecimalMax(value = "300", message = "体重范围30-300kg")
    private BigDecimal weightKg;

    /** 腰围(cm) */
    @DecimalMin(value = "20", message = "腰围范围20-200cm")
    @DecimalMax(value = "200", message = "腰围范围20-200cm")
    private BigDecimal waistCm;

    /** 臂围(cm) */
    @DecimalMin(value = "10", message = "臂围范围10-100cm")
    @DecimalMax(value = "100", message = "臂围范围10-100cm")
    private BigDecimal armCm;

    /** 腿围(cm) */
    @DecimalMin(value = "20", message = "腿围范围20-150cm")
    @DecimalMax(value = "150", message = "腿围范围20-150cm")
    private BigDecimal legCm;

    /** 体脂率(%) */
    @DecimalMin(value = "3", message = "体脂率范围3-60%")
    @DecimalMax(value = "60", message = "体脂率范围3-60%")
    private BigDecimal bodyFatPct;
}

package com.fitness.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 修改训练记录请求 — 对应提示词 2.5
 * <p>
 * 规范明确「所有字段均为可选，传了就更新」，因此本 DTO 不含 @NotNull/@NotBlank，
 * 只保留取值范围校验。
 * <p>
 * 为什么不能复用 {@link TrainingRecordRequest}：后者为新增场景设计，
 * actionName/sets/reps/weightKg 都是必填，用它接修改请求会导致
 * 只传 {"sets":5} 这样合法的部分更新被校验拦下（返回 9003）。
 */
@Data
public class TrainingRecordUpdateRequest {

    @Min(value = 1, message = "组数至少为1")
    private Integer sets;

    @Min(value = 1, message = "次数至少为1")
    private Integer reps;

    @DecimalMin(value = "0", message = "重量不能为负数")
    private BigDecimal weightKg;

    @Min(value = 1, message = "训练时长至少为1分钟")
    private Integer durationMin;

    /** RPE 1-10 */
    @Min(value = 1, message = "RPE最小为1")
    @Max(value = 10, message = "RPE最大为10")
    private Integer rpe;

    @Size(max = 500, message = "备注最长500字符")
    private String remark;
}

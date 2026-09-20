package com.fitness.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/** 新增单条训练记录请求 */
@Data
public class TrainingRecordRequest {

    /** 训练日期，格式 yyyy-MM-dd，默认当天 */
    @Pattern(regexp = "^(\\d{4}-\\d{2}-\\d{2})?$", message = "训练日期格式需为yyyy-MM-dd")
    private String trainingDate;

    @NotBlank(message = "动作名称不能为空")
    @Size(max = 100, message = "动作名称最长100字符")
    private String actionName;

    @NotNull(message = "组数不能为空")
    @Min(value = 1, message = "组数至少为1")
    private Integer sets;

    @NotNull(message = "次数不能为空")
    @Min(value = 1, message = "次数至少为1")
    private Integer reps;

    /** 重量(kg) — 规范要求 >=0 */
    @NotNull(message = "重量不能为空")
    @DecimalMin(value = "0", message = "重量不能为负数")
    private BigDecimal weightKg;

    /** 训练时长(分钟) */
    @Min(value = 1, message = "训练时长至少为1分钟")
    private Integer durationMin;

    /** RPE 1-10 */
    @Min(value = 1, message = "RPE最小为1")
    @Max(value = 10, message = "RPE最大为10")
    private Integer rpe;

    /** 备注 */
    @Size(max = 500, message = "备注最长500字符")
    private String remark;
}

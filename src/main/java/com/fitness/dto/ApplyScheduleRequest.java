package com.fitness.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 套用模板生成训练安排请求 — 对应提示词 6.2
 */
@Data
public class ApplyScheduleRequest {

    /** 模板ID */
    @NotNull(message = "模板ID不能为空")
    private Long templateId;

    /** 起始日期（周一），格式 yyyy-MM-dd */
    @NotBlank(message = "起始日期不能为空")
    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "起始日期格式需为yyyy-MM-dd")
    private String startDate;
}

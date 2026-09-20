package com.fitness.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 训练计划模板响应 — 对应提示词 6.1 接口 list 数组元素
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkoutTemplateResponse {

    private Long id;

    private String templateName;

    private String description;

    private String splitType;

    private String targetLevel;

    /** 模板下的动作明细（按 dayOfCycle 升序） */
    private List<TemplateExerciseResponse> exercises;
}

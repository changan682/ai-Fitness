package com.fitness.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 模板动作明细响应 — 对应提示词 6.1 接口 exercises 数组元素
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TemplateExerciseResponse {

    /** 周期内第几天（1-7） */
    private Integer dayOfCycle;

    /** 训练日标签（如：胸+三头） */
    private String dayLabel;

    /** 动作名称 */
    private String actionName;

    /** 目标肌群 */
    private String targetMuscle;

    /** 推荐组数范围 */
    private String recommendedSets;

    /** 推荐次数范围 */
    private String recommendedReps;

    /** 动作要点 */
    private String notes;
}

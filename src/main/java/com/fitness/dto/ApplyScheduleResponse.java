package com.fitness.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 套用模板生成训练安排响应 — 对应提示词 6.2
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApplyScheduleResponse {

    /** 本次生成安排的首条记录ID（t_user_workout_schedule 无周维度主表，故取首行ID） */
    private Long scheduleId;

    /** 周起始日期（周一） */
    private String weekStart;

    /** 本周各训练日安排（休息日不返回） */
    private List<ScheduleDayResponse> days;
}

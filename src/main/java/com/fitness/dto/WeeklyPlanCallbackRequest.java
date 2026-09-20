package com.fitness.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.Map;

/**
 * 周计划回调请求体 — Python → Java {@code POST /api/ai/callback/weekly-plan}
 * <p>
 * 对应规范 8.1。注意请求体先以**原始字符串**接收（验签需要原始字节），
 * 再由 Controller 反序列化成本类，因此这里的字段名必须与 Python 发送的一致。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WeeklyPlanCallbackRequest {

    /** MQ 消息唯一 ID，与 t_weekly_plan.task_id 对应（幂等键） */
    private String taskId;

    private Long userId;

    /** 周起始日期（周一），yyyy-MM-dd */
    private String weekStart;

    /** AI 生成的周计划建议（Markdown） */
    private String suggestionText;

    /**
     * 本周数据摘要（扁平结构：trainingDays/totalVolume/avgRpe/weightChange/avgCalories）
     * <p>
     * 用 Map 而不是强类型 DTO：它整份以 JSON 文本存进 {@code t_weekly_plan.week_summary}，
     * 字段由 Python 侧决定，Java 不该因为多一个字段就丢数据。
     */
    private Map<String, Object> weekSummary;
}

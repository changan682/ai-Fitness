package com.fitness.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 最新周计划响应 — 对应提示词 9.2
 * <p>
 * 无数据时 Service 返回 null，Controller 包装为 {@code {code:0, msg:"success", data:null}}，
 * 前端据此展示 Empty 状态（规范明确要求，不返回 404 或错误码）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeeklyPlanResponse {

    private Long id;

    /** 周起始日期（周一），yyyy-MM-dd */
    private String weekStart;

    /** AI生成的周计划建议（Markdown），第7周由Python回调填充 */
    private String suggestionText;

    /** 本周训练数据摘要（JSON字符串），第2周定时任务写入 */
    private String weekSummary;

    /** 用户是否已读：false-未读 true-已读 */
    private Boolean isRead;

    private LocalDateTime createdAt;
}

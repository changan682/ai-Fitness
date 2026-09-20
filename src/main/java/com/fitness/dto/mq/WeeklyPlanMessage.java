package com.fitness.dto.mq;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 周计划请求消息体 — Java → RabbitMQ {@code ai.weekly.plan}
 * <p>
 * 字段与规范「MQ 消息体 JSON Schema（每周复盘）」逐字段一致。它同时是**跨语言契约**：
 * Python 侧的 {@code mq_consumer} 直接按这些字段名取值并拼进 Prompt，
 * 因此改字段名等于改协议，两端必须同步。
 * <p>
 * 用 DTO 而不是 {@code Map} 发消息的原因：字段名由编译器与 Jackson 保证，
 * 不会出现「拼错一个 key 导致 Python 取到 null、模型收到一份空数据」这种静默错误。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeeklyPlanMessage {

    /** 唯一任务 ID：{@code weekly-plan-{userId}-{weekStart}}（幂等去重依据） */
    private String taskId;

    private Long userId;

    /** 周起始日期（周一），yyyy-MM-dd */
    private String weekStart;

    /** 周结束日期（周日），yyyy-MM-dd */
    private String weekEnd;

    private TrainingSummary trainingSummary;

    private BodyMetrics bodyMetrics;

    private DietSummary dietSummary;

    /** 消息生成时间，ISO8601（如 2026-08-03T21:00:00） */
    private String timestamp;

    // ==================== 嵌套结构 ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TrainingSummary {

        /** 本周训练天数（按日期去重） */
        private Integer trainingDays;

        /** 本周训练记录条数 */
        private Integer totalActions;

        /** 本周总容量（kg） */
        private BigDecimal totalVolume;

        /** 平均 RPE（无记录时为 null） */
        private BigDecimal avgRpe;

        /** 容量 Top-5 动作 */
        private List<TopAction> topActions;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TopAction {

        private String actionName;

        /** 该动作本周出现次数 */
        private Integer count;

        /** 该动作本周总容量 */
        private BigDecimal totalVolume;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BodyMetrics {

        private BigDecimal startWeight;

        private BigDecimal endWeight;

        /** 体重变化 = endWeight - startWeight */
        private BigDecimal weightChange;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DietSummary {

        /** 本周日均热量（仅统计有饮食记录的天） */
        private BigDecimal avgDailyCalories;

        /** 本周饮食记录条数 */
        private Integer totalMeals;
    }
}

package com.fitness.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 身体数据趋势响应 — 对应提示词 3.2
 * <p>
 * 结构：{@code list[{id,recordDate,weightKg,waistCm,armCm,legCm,bodyFatPct,weightAvg7d}]}
 * + {@code latestWeight} + {@code weightChange} + {@code totalRecords}。
 * 每个数据点自带 7 日滑动平均体重，前端画图时无需再对齐两条序列。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BodyMetricTrendResponse {

    /** 区间内的身体数据点（按日期升序），每点携带 weightAvg7d */
    private List<TrendPoint> list;

    /** 区间内最新一次体重 */
    private BigDecimal latestWeight;

    /** 相比 7 天前的体重变化（正数为增重）；缺少 7 天前基准数据时为 null */
    private BigDecimal weightChange;

    /** 区间内记录总条数 */
    private int totalRecords;

    /** 单条身体数据 + 其 7 日滑动平均体重 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TrendPoint {
        private Long id;
        private String recordDate;
        private BigDecimal weightKg;
        private BigDecimal waistCm;
        private BigDecimal armCm;
        private BigDecimal legCm;
        private BigDecimal bodyFatPct;

        /** 后端计算的 7 日滑动平均体重（自然日窗口 [d-6, d]） */
        private BigDecimal weightAvg7d;
    }
}

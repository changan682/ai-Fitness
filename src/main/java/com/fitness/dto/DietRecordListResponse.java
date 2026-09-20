package com.fitness.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 按日期查询饮食记录响应 — 对应提示词 4.2
 * <p>
 * 规范要求返回 {@code {list, date, totalCalories}}，而不是裸数组：
 * 前端「今日饮食」卡片需要直接展示当日总热量，裸数组会迫使前端再调一次统计接口。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DietRecordListResponse {

    /** 当日饮食记录（按餐次排序） */
    private List<DietRecordResponse> list;

    /** 查询日期，yyyy-MM-dd */
    private String date;

    /** 当日总热量(kcal) */
    private BigDecimal totalCalories;
}

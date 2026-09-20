package com.fitness.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 单个训练日安排 — 对应提示词 6.2 接口 days 数组元素
 * <p>
 * actions 为拼接好的可读文案，如 "杠铃卧推 3-4组×8-12次"，
 * 前端可直接渲染，无需再拼接字段。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleDayResponse {

    /** 安排日期 */
    private String scheduleDate;

    /** 训练日标签（如：胸+三头） */
    private String dayLabel;

    /** 动作描述列表 */
    private List<String> actions;
}

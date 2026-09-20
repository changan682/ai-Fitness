package com.fitness.dto;

import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 训练智能总结请求 — 对应规范 7.1
 */
@Data
public class AiSummaryRequest {

    /** 可选，默认今天 */
    @Pattern(regexp = "^(\\d{4}-\\d{2}-\\d{2})?$", message = "日期格式需为yyyy-MM-dd")
    private String date;
}

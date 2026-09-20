package com.fitness.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.List;

/**
 * 动作智能推荐请求 — 对应规范 7.2
 */
@Data
public class AiRecommendRequest {

    /** 目标肌群：胸/背/腿/肩/手臂/核心 */
    @NotBlank(message = "目标肌群不能为空")
    @Pattern(regexp = "^(胸|背|腿|肩|手臂|核心)$", message = "目标肌群需为：胸/背/腿/肩/手臂/核心")
    private String targetMuscle;

    /** 可用器械列表 */
    @NotEmpty(message = "器械列表不能为空")
    private List<String> equipment;

    /** 推荐数量，默认5（与 Python 侧 ge=1/le=10 约束保持一致） */
    @Min(value = 1, message = "推荐数量最小为1")
    @Max(value = 10, message = "推荐数量最大为10")
    private Integer count;
}

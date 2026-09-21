package com.fitness.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 动作姿态评估响应 — 对应规范 7.3
 * <p>
 * 请求侧是 {@code multipart/form-data}（image + actionName），
 * 因此没有对应的请求 DTO，由 Controller 直接接收 MultipartFile 与表单字段。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiPoseResponse {

    /** 标准度评分 0-100 */
    private Integer score;

    /** 评级：优秀/良好/一般/需改进 */
    private String scoreLevel;

    private List<String> issues;

    private List<String> suggestions;

    private List<String> goodPoints;

    /**
     * 结果来源：{@code qwen_vl}=真实多模态推理；{@code mock_local}=本地模拟打分。
     * <p>
     * ⚠️ 这个字段是**必须**的：{@code MOCK_MODE=true} 时 Python 返回的分数由图片哈希派生
     * （45-95），与真实推理结果在结构上完全一致。没有它，前端会把编造的数字
     * 当成真实评估结果展示给用户 —— 既是产品问题，也是诚信问题。
     */
    private String dataSource;

    private LocalDateTime evaluatedAt;
}

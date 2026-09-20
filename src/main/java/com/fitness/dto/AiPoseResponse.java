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

    private LocalDateTime evaluatedAt;
}

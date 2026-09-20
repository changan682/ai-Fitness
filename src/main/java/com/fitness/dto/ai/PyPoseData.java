package com.fitness.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 姿态评估响应数据 — Python {@code /agent/v1/pose-evaluate} 的 data 部分
 * <p>
 * 对应规范 models.py 的 {@code PoseEvaluateResponse}。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PyPoseData {

    /** 标准度评分 0-100 */
    private Integer score;

    /** 评级：优秀(>=90)/良好(70-89)/一般(50-69)/需改进(<50) */
    @JsonProperty("score_level")
    private String scoreLevel;

    /** 存在的问题 */
    private List<String> issues;

    /** 纠正建议 */
    private List<String> suggestions;

    /** 做得好的地方 */
    @JsonProperty("good_points")
    private List<String> goodPoints;

    @JsonProperty("evaluated_at")
    private String evaluatedAt;
}

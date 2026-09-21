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

    /**
     * 结果来源：{@code qwen_vl}（真实多模态推理）/ {@code mock_local}（本地模拟打分）。
     * <p>
     * 加了 {@code @JsonIgnoreProperties(ignoreUnknown = true)}，因此 Python 侧新增这个字段时
     * 老版本 Java 不会报错 —— 但也意味着**必须显式声明**，否则它会静默丢失，
     * 前端就再也分不清真实分数与模拟分数了。
     */
    @JsonProperty("data_source")
    private String dataSource;

    @JsonProperty("evaluated_at")
    private String evaluatedAt;
}

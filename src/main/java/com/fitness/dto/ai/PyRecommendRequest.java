package com.fitness.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 动作推荐请求 — Java → Python {@code POST /agent/v1/recommend}
 * <p>
 * 对应规范 models.py 的 {@code RecommendRequest}。
 * {@code count} 在 Python 侧有 {@code ge=1, le=10} 约束，Java 侧 DTO 也做了同样的范围校验，
 * 避免把必然 422 的请求发出去。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PyRecommendRequest {

    @JsonProperty("target_muscle")
    private String targetMuscle;

    private List<String> equipment;

    private Integer count;
}

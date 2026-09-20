package com.fitness.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 知识库问答请求 — Java → Python {@code POST /agent/v1/chat}
 * <p>
 * 对应规范 models.py 的 {@code ChatRequest}。
 * Python 侧对 question 有 {@code min_length=1, max_length=500} 约束，Java 侧同步校验。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PyChatRequest {

    private String question;

    /** 可选，限定知识分类检索范围 */
    private String category;

    @JsonProperty("user_id")
    private Long userId;
}

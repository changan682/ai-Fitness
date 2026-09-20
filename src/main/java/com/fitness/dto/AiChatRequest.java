package com.fitness.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 健身知识库 RAG 问答请求 — 对应规范 7.4
 */
@Data
public class AiChatRequest {

    /** 用户问题（长度上限与 Python 侧 ChatRequest 的 max_length=500 对齐） */
    @NotBlank(message = "问题不能为空")
    @Size(max = 500, message = "问题最长500字符")
    private String question;

    /** 可选，限定知识分类检索范围 */
    private String category;
}

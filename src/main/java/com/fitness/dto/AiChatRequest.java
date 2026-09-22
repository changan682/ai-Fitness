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

    /**
     * 会话 id（可选）—— 对话记忆的钥匙（体验优化批次 C）
     * <p>
     * - 不传 / 非法格式：后端当作**新会话**并生成一个返回给前端；
     * - 传合法 UUID：后端会把该会话最近几轮问答作为上下文一起送给大模型，
     *   于是"那做几组？"这类省略主语的追问能正确指代上文。
     * <p>
     * 归属校验在服务端：会话一律按「当前登录用户 + sessionId」存取，
     * 且 userId 只从 Token 取 —— 传别人的 sessionId 也只会得到自己的（空）会话。
     */
    private String sessionId;
}

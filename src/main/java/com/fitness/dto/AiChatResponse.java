package com.fitness.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 健身知识库 RAG 问答响应 — 对应规范 7.4
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiChatResponse {

    private String question;

    /** Markdown 回答（含引用标注） */
    private String answer;

    /** 检索到的知识来源 */
    private List<Source> sources;

    private LocalDateTime generatedAt;

    /** 单条知识来源 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Source {
        private String category;
        private String title;
        private String content;
        /** 向量相似度得分 */
        private BigDecimal score;
    }
}

package com.fitness.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 知识库问答响应数据 — Python {@code /agent/v1/chat} 的 data 部分
 * <p>
 * 对应规范 models.py 的 {@code ChatResponse} + {@code ChatSource}。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PyChatData {

    /** 原问题回显 */
    private String question;

    /** Markdown 回答（含引用标注） */
    private String answer;

    /** 检索到的知识来源（Top-5） */
    private List<Source> sources;

    @JsonProperty("generated_at")
    private String generatedAt;

    /** 单条知识来源 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Source {

        private String category;

        private String title;

        private String content;

        /** 向量相似度得分 */
        private BigDecimal score;
    }
}

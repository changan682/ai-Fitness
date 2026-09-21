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

    /** 来源库：milvus / builtin / none */
    @JsonProperty("data_source")
    private String dataSource;

    /** 是否走了降级路径 */
    private Boolean degraded;

    /** 降级原因（中文说明） */
    @JsonProperty("degradation_reason")
    private String degradationReason;

    /** 单条知识来源 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Source {

        private String category;

        private String title;

        private String content;

        /** 相关度得分；口径由 {@link #scoreType} 声明 */
        private BigDecimal score;

        /** cosine=真实余弦相似度；heuristic=内置兜底的启发式合成分数 */
        @JsonProperty("score_type")
        private String scoreType;
    }
}

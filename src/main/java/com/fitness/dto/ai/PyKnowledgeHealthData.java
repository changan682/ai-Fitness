package com.fitness.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Milvus 知识库健康数据 — {@code GET /agent/v1/knowledge/health} 的 data 部分
 * <p>
 * 对应规范 7.5（Java 侧暴露为 {@code GET /api/ai/knowledge/health}）。
 * 第 5-6 周接入 Milvus 后这些字段才会有真实值，当前 Mock 阶段返回占位数据。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PyKnowledgeHealthData {

    @JsonProperty("milvus_connected")
    private Boolean milvusConnected;

    @JsonProperty("collection_name")
    private String collectionName;

    @JsonProperty("total_documents")
    private Integer totalDocuments;

    @JsonProperty("last_updated")
    private String lastUpdated;

    @JsonProperty("index_type")
    private String indexType;

    /** 向量维度，规范固定为 768 */
    @JsonProperty("embedding_dim")
    private Integer embeddingDim;
}

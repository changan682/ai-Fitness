package com.fitness.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Milvus 知识库健康检查响应 — 对应规范 7.5
 * <p>
 * 第 5-6 周接入 Milvus 后这些字段才有真实值，当前为 Python Mock 返回的占位数据。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiKnowledgeHealthResponse {

    private Boolean milvusConnected;

    private String collectionName;

    private Integer totalDocuments;

    private String lastUpdated;

    private String indexType;

    /** 向量维度，规范固定 768 */
    private Integer embeddingDim;
}

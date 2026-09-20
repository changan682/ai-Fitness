package com.fitness.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Python 服务健康检查数据 — {@code GET /agent/v1/health} 的 data 部分
 * <p>
 * 对应规范 10.2（内网接口，仅 Java 调用）：
 * Java 启动与 {@code /api/v1/health} 都靠它判断 Python 侧是否可用。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PyAgentHealthData {

    /** UP / DOWN */
    private String status;

    @JsonProperty("milvus_connected")
    private Boolean milvusConnected;

    @JsonProperty("llm_api_configured")
    private Boolean llmApiConfigured;

    @JsonProperty("knowledge_base_ready")
    private Boolean knowledgeBaseReady;

    /** ISO8601 时间戳 */
    private String timestamp;
}

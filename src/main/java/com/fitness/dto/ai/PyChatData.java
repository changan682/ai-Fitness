package com.fitness.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

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

    /**
     * Agent 模式下的工具调用记录（阶段一：最多一条）—— 加性字段
     * <p>
     * 「这轮有没有用知识库」的**客观证据**（工具名 / 参数 / 是否命中 / 最高相似度），
     * 比大模型自报的 {@code [[KB:USED]]} 标记可靠。
     * <p>
     * ⚠️ 必须显式 {@code @JsonProperty}：本类带
     * {@code @JsonIgnoreProperties(ignoreUnknown = true)}，没声明的字段会被
     * **静默丢弃**（项目在 {@code data_source} 上踩过一次这个坑）。
     * <p>
     * 常规模式为 {@code null}；Agent 模式的空数组表示"进入了 Agent 路径但模型判断无需检索"。
     */
    @JsonProperty("tool_calls")
    private List<Map<String, Object>> toolCalls;

    /** 是否真的拿到了知识依据（Agent 模式专属，{@code null} = 非 Agent 模式） */
    @JsonProperty("knowledge_used")
    private Boolean knowledgeUsed;

    /**
     * Agent 循环的步骤记录（阶段二）—— 加性字段
     * <p>
     * 每步含 {@code step / tool / arguments / ok / summary / elapsed_ms / error}。
     * 这是给用户看的「AI 用了什么数据」的**可验证依据**，
     * 也是本项目「降级/过程必须可见」原则在 Agent 时代的延伸。
     */
    private List<Map<String, Object>> steps;

    /** Agent 循环的元信息：{@code steps_used / tokens / latency_ms / stopped_by / max_steps} */
    private Map<String, Object> agent;

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

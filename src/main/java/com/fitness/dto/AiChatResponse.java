package com.fitness.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 健身知识库 RAG 问答响应 — 对应规范 7.4
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AiChatResponse {

    private String question;

    /** Markdown 回答（含引用标注） */
    private String answer;

    /** 检索到的知识来源 */
    private List<Source> sources;

    private LocalDateTime generatedAt;

    /**
     * 来源库与「这轮回答到底有没有用知识库」：
     * {@code milvus}=基于 200 条真实知识库；{@code llm_only}=检索到了资料但判定无关、
     * 改用大模型通用知识（来源为空）；{@code none}=没检索到来源；
     * {@code builtin}=内置 18 条兜底。
     */
    private String dataSource;

    /** 是否走了降级路径（知识库没覆盖、检索失败/无命中、无 LLM Key 本地拼装、内置兜底） */
    private Boolean degraded;

    /** 降级原因（给人看的中文说明），未降级时为 null */
    private String degradationReason;

    /**
     * 会话 id —— 前端必须把它带回下一次提问，对话才连得上（体验优化批次 C）
     * <p>
     * 首轮请求不传，后端生成并返回；此后每次都要原样带上。
     */
    private String sessionId;

    /**
     * Agent 模式下的工具调用记录（阶段一：最多一条）—— **加性字段**
     * <p>
     * 这是「这轮到底有没有用知识库」的**客观证据**：调没调工具、传了什么参数、
     * 有没有命中、最高相似度多少，全部可查；比大模型自报的
     * {@code [[KB:USED]]} / {@code [[KB:MISS]]} 标记可靠。
     * <p>
     * <b>为什么必须在这里显式声明</b>：本类上游的 Python DTO 若新增字段而此处没声明，
     * 由于反序列化忽略未知字段，新证据会被**静默丢弃** —— 表现是"Python 明明返回了，
     * 前端永远拿不到"。项目在 {@code data_source} 上踩过一次这个坑。
     * <p>
     * 常规模式（非 Agent）为 {@code null}，与 Agent 模式的空数组
     * （= 进入了 Agent 路径但模型判断无需检索）语义不同。
     */
    private List<Map<String, Object>> toolCalls;

    /**
     * 是否真的拿到了知识依据（Agent 模式专属）—— **加性字段**
     * <p>
     * {@code null} 表示不是 Agent 模式、无从判断；{@code false} 明确表示
     * 「这轮没用上知识库」（未调用工具 / 检索零命中 / 工具失败）。
     */
    private Boolean knowledgeUsed;

    /**
     * Agent 循环的步骤记录 —— **加性字段**
     * <p>
     * 每步含 {@code step / tool / arguments / ok / summary / elapsedMs / error}。
     * 前端据此渲染「AI 的思考过程 / 用到的数据」折叠面板 ——
     * 让用户能**自己验证**结论是从哪些数据推出来的，而不是只能相信一个数字。
     * <p>
     * 非 Agent 路径为 {@code null}。
     */
    private List<Map<String, Object>> steps;

    /** Agent 循环元信息：{@code stepsUsed / tokens / latencyMs / stoppedBy / maxSteps} */
    private Map<String, Object> agent;

    /** 单条知识来源 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Source {
        private String category;
        private String title;
        private String content;
        /** 相关度得分；口径由 {@link #scoreType} 声明 */
        private BigDecimal score;

        /**
         * 分数口径：{@code cosine}=真实 Milvus 余弦相似度；
         * {@code heuristic}=内置兜底时的启发式合成分数（**不是**向量相似度）。
         * <p>
         * 前端据此决定是否给出「合成分数，仅供参考」的提示 ——
         * 把启发式分数当余弦相似度展示，与编造数据没有区别。
         */
        private String scoreType;
    }
}

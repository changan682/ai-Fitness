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

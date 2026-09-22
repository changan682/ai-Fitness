package com.fitness.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 知识库问答请求 — Java → Python {@code POST /agent/v1/chat}
 * <p>
 * 对应规范 models.py 的 {@code ChatRequest}。
 * Python 侧对 question 有 {@code min_length=1, max_length=500} 约束，Java 侧同步校验。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PyChatRequest {

    private String question;

    /** 可选，限定知识分类检索范围 */
    private String category;

    @JsonProperty("user_id")
    private Long userId;

    /**
     * 对话历史（时间正序，最近若干轮）—— 体验优化批次 C
     * <p>
     * ⚠️ 必须显式声明：本类带 {@code @JsonIgnoreProperties(ignoreUnknown = true)}，
     * 没声明的字段会被**静默丢弃**（项目在 data_source 上踩过一次），
     * 表现就是"记忆功能看起来写好了但完全没生效"。
     * <p>
     * ⚠️ 还必须 {@code NON_NULL}：首轮没有历史时，默认序列化会把 {@code "history": null}
     * 发给 Python，而 Pydantic 的 `List[ChatTurn]` 收到 null 会判定为**入参非法**
     * （实测报 `history: Input should be a valid list` → 前端拿到 9003）。
     * 干脆不发这个字段，让 Python 用它自己的默认值。
     * <p>
     * Python 侧会对条数/长度再截断一次（不信任调用方）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<Turn> history;

    /** 一条历史消息：{@code role} = user / assistant */
    @Data
    public static class Turn {
        private String role;
        private String content;
    }
}

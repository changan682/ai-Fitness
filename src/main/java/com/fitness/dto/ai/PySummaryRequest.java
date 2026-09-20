package com.fitness.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 训练总结请求 — Java → Python {@code POST /agent/v1/summary}
 * <p>
 * 对应规范 models.py 的 {@code SummaryRequest}，字段名必须是 snake_case（Python 侧协议）。
 * 注意：规范要求「与上次同部位对比的数据需 Java 提前查好传给 Python」，
 * 因为 Python 侧不连业务库，所有业务数据都由 Java 组装后推送。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PySummaryRequest {

    @JsonProperty("user_id")
    private Long userId;

    /** 训练日期 yyyy-MM-dd */
    private String date;

    /** 当日训练记录 */
    private List<RecordBrief> records;

    /**
     * 上次对比数据（可选）
     * <p>
     * 约定 key：{@code previousDate}、{@code records}、{@code volumeChangePct}。
     * 用 Map 而不是强类型：第 5-6 周接入真实 Prompt 后对比维度可能扩展
     * （如按肌群、按动作分别对比），用 Map 避免每加一个维度就改协议类。
     */
    private Map<String, Object> comparison;

    /** 单条训练记录摘要（对应 Python 侧 {@code records: List[dict]}） */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RecordBrief {

        /** 动作名称 */
        private String action;

        private Integer sets;

        private Integer reps;

        /** 重量(kg) */
        private BigDecimal weight;

        /** 主观感受 1-10 */
        private Integer rpe;
    }
}

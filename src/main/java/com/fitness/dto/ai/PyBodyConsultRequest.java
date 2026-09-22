package com.fitness.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 身体状态问询请求 — Java → Python {@code POST /agent/v1/body-consult}（体验优化批次 D）
 *
 * <h3>为什么快照由 Java 组装</h3>
 * 这个接口的输入全是"库里的数"：体测、训练、饮食、档案。Python 侧刻意不查数据库 ——
 * 本项目里取数口径只写在 Java 一处（"最新体重取体测而不是档案"这种口径已经踩过坑），
 * 让它跨语言复制一份迟早会分叉。
 *
 * <h3>字段全部可空</h3>
 * 新用户可能一次体测都没记过，此时 Python 会识别出来并直接走规则回复（不花 token 调大模型）。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PyBodyConsultRequest {

    @JsonProperty("user_id")
    private Long userId;

    private Profile profile;

    /** 最新一条体测 */
    @JsonProperty("latest_metric")
    private Metric latestMetric;

    /** 上一条体测（用于算变化） */
    @JsonProperty("prev_metric")
    private Metric prevMetric;

    /** 近 7 天趋势 */
    @JsonProperty("trend7d")
    private Trend7d trend7d;

    /** 近 7 天训练 */
    @JsonProperty("training7d")
    private Training7d training7d;

    /** 近 7 天有饮食记录的天数 */
    @JsonProperty("diet_days_recorded")
    private Integer dietDaysRecorded;

    /** 档案（只取与训练建议相关的字段，不下发手机号等隐私） */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Profile {
        private Integer gender;
        private Double height;
        private Double weight;
        @JsonProperty("training_goal")
        private String trainingGoal;
        @JsonProperty("training_level")
        private String trainingLevel;
        @JsonProperty("injury_record")
        private List<String> injuryRecord;
    }

    /** 一条体测 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Metric {
        @JsonProperty("record_date")
        private String recordDate;
        @JsonProperty("weight_kg")
        private Double weightKg;
        @JsonProperty("waist_cm")
        private Double waistCm;
        @JsonProperty("arm_cm")
        private Double armCm;
        @JsonProperty("leg_cm")
        private Double legCm;
        @JsonProperty("body_fat_pct")
        private Double bodyFatPct;
    }

    /** 近 7 天趋势 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Trend7d {
        @JsonProperty("weight_delta")
        private Double weightDelta;
        @JsonProperty("waist_delta")
        private Double waistDelta;
        @JsonProperty("weight_avg7d")
        private Double weightAvg7d;
        /** 这 7 天里有几条体测记录 —— Python 用它判断"样本够不够，别硬编趋势" */
        private Integer samples;
    }

    /** 近 7 天训练 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Training7d {
        private Integer sessions;
        @JsonProperty("total_volume")
        private Double totalVolume;
        @JsonProperty("avg_rpe")
        private Double avgRpe;
        private List<String> muscles;
    }
}

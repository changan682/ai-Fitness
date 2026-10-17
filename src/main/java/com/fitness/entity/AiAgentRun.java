package com.fitness.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Agent 运行审计 — 对应 {@code t_ai_agent_run}（阶段二）
 * <p>
 * <b>为什么需要它</b>：Agent 的回答是非确定性的 —— 同一个问题，模型这次可能调 1 个工具、
 * 下次调 3 个，参数也可能不同。线上出问题时**无法靠复现来定位**，
 * 唯一能查的就是"当时它到底做了什么"。所以每一步的工具名 / 参数 / 是否命中 / 耗时、
 * 以及中途为什么停下，都必须落库。
 * <p>
 * 与 {@link AiChatHistory} 的分工：后者存「用户看到的那句话」（热路径，每次问答都读写）；
 * 本表存「系统为这句话做了什么」（冷路径，只写不读）。分开是为了不让热表越来越重。
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "t_ai_agent_run")
public class AiAgentRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "session_id", length = 36)
    private String sessionId;

    @Column(name = "question", nullable = false, columnDefinition = "TEXT")
    private String question;

    /** 步骤记录（JSON 数组字符串，原样来自 Python 响应，不做解析 —— 审计要的是原貌） */
    @Column(name = "steps_json", columnDefinition = "TEXT")
    private String stepsJson;

    /** 用到的工具名（逗号分隔），便于按工具做聚合查询 */
    @Column(name = "tools_used", length = 255)
    private String toolsUsed;

    @Column(name = "steps_used")
    private Integer stepsUsed;

    @Column(name = "tokens")
    private Integer tokens;

    @Column(name = "latency_ms")
    private Long latencyMs;

    /** 停止原因：completed / max_steps / deadline / budget / tool_error / error */
    @Column(name = "stopped_by", length = 32)
    private String stoppedBy;

    @Column(name = "data_source", length = 20)
    private String dataSource;

    @Column(name = "degraded")
    private Integer degraded;

    @Column(name = "created_at", updatable = false,
            columnDefinition = "DATETIME DEFAULT CURRENT_TIMESTAMP")
    private LocalDateTime createdAt;

    @jakarta.persistence.PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (stepsUsed == null) stepsUsed = 0;
        if (tokens == null) tokens = 0;
        if (latencyMs == null) latencyMs = 0L;
        if (degraded == null) degraded = 0;
    }
}

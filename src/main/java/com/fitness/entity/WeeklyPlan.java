package com.fitness.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * AI周计划实体 — 对应 t_weekly_plan 表
 * <p>
 * 第2周定时任务写入 {@code week_summary}（纯Java统计，无AI）；
 * 第7周 Python 回调写入 {@code suggestion_text}（AI 生成的 Markdown 建议）。
 * task_id 唯一，用于幂等去重。
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "t_weekly_plan")
public class WeeklyPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户ID */
    @Column(nullable = false)
    private Long userId;

    /** MQ任务ID（用于幂等去重），第2周为 weekly-stats-{userId}-{weekStart} */
    @Column(nullable = false, length = 64)
    private String taskId;

    /** 周起始日期（周一） */
    @Column(nullable = false)
    private LocalDate weekStart;

    /** AI生成的周计划建议（Markdown格式），第7周AI回调填充，第2周为空占位 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String suggestionText;

    /** 本周训练数据摘要（JSON：训练次数、总容量、体重变化等） */
    @Column(columnDefinition = "TEXT")
    private String weekSummary;

    /** 用户是否已读：0-未读 1-已读 */
    @Column(columnDefinition = "TINYINT DEFAULT 0")
    private Integer isRead;

    /** 创建时间 */
    @Column(updatable = false, columnDefinition = "DATETIME DEFAULT CURRENT_TIMESTAMP")
    private LocalDateTime createdAt;

    /** 更新时间 */
    @Column(columnDefinition = "DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (isRead == null) isRead = 0;
        if (suggestionText == null) suggestionText = "";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

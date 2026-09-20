package com.fitness.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 模板动作明细实体 — 对应 t_template_exercise
 * <p>
 * 一个模板由 7 天周期（day_of_cycle 1-7）内的多条动作组成；
 * 休息日不写记录行，因此「按天分组」后自然只剩训练日。
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "t_template_exercise", indexes = {
        // columnList 用物理列名（snake_case），与 sql/init.sql 保持一致
        @Index(name = "idx_template_id", columnList = "template_id"),
        @Index(name = "idx_target_muscle", columnList = "target_muscle")
})
public class TemplateExercise {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 模板ID，关联 t_workout_plan_template.id */
    @Column(nullable = false)
    private Long templateId;

    /** 周期内第几天（1-7） */
    @Column(nullable = false, columnDefinition = "TINYINT")
    private Integer dayOfCycle;

    /** 训练日标签（如：胸+三头） */
    @Column(nullable = false, length = 20)
    private String dayLabel;

    /** 动作名称 */
    @Column(nullable = false, length = 100)
    private String actionName;

    /** 目标肌群（胸/背/腿/肩/手臂/核心） */
    @Column(nullable = false, length = 50)
    private String targetMuscle;

    /** 推荐组数范围 */
    @Column(length = 20)
    @Builder.Default
    private String recommendedSets = "3-4";

    /** 推荐次数范围 */
    @Column(length = 20)
    @Builder.Default
    private String recommendedReps = "8-12";

    /** 排序号 */
    @Column(columnDefinition = "INT DEFAULT 0")
    @Builder.Default
    private Integer sortOrder = 0;

    /** 动作要点 */
    @Column(length = 300)
    private String notes;

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
        if (sortOrder == null) sortOrder = 0;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

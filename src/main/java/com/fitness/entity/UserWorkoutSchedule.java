package com.fitness.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 用户训练安排实体 — 对应 t_user_workout_schedule
 * <p>
 * 由「套用模板」接口按周生成：每个动作一行，schedule_date 为模板 day_of_cycle 换算出的实际日期。
 * 同一用户同一周重复套用时，先删后建（覆盖），避免安排堆积。
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "t_user_workout_schedule", indexes = {
        // columnList 用物理列名（snake_case），与 sql/init.sql 保持一致
        @Index(name = "idx_user_date", columnList = "user_id,schedule_date"),
        @Index(name = "idx_template_id", columnList = "template_id")
})
public class UserWorkoutSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户ID */
    @Column(nullable = false)
    private Long userId;

    /** 安排日期 */
    @Column(nullable = false)
    private LocalDate scheduleDate;

    /** 来源模板ID */
    private Long templateId;

    /** 动作名称 */
    @Column(nullable = false, length = 100)
    private String actionName;

    /** 目标肌群 */
    @Column(length = 50)
    private String targetMuscle;

    /** 目标组数 */
    @Column(columnDefinition = "INT DEFAULT 3")
    @Builder.Default
    private Integer targetSets = 3;

    /** 目标次数 */
    @Column(columnDefinition = "INT DEFAULT 10")
    @Builder.Default
    private Integer targetReps = 10;

    /** 是否完成：0-未完成 1-已完成 */
    @Column(columnDefinition = "TINYINT DEFAULT 0")
    @Builder.Default
    private Integer isCompleted = 0;

    /** 完成时间 */
    private LocalDateTime completedAt;

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
        if (isCompleted == null) isCompleted = 0;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

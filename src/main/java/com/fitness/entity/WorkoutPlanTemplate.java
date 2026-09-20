package com.fitness.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 训练计划模板主表实体 — 对应 t_workout_plan_template
 * <p>
 * 预设三分化/推拉腿/五分化三种模板，数据变更频率极低，
 * 启动时全量预加载到 Redis（workout:template:all，TTL 24h）。
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "t_workout_plan_template",
        uniqueConstraints = @UniqueConstraint(name = "uk_template_name", columnNames = "template_name"))
public class WorkoutPlanTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 模板名称（如：三分化训练） */
    @Column(nullable = false, length = 50)
    private String templateName;

    /** 模板描述 */
    @Column(length = 500)
    private String description;

    /** 适合人群：新手/进阶/老手 */
    @Column(length = 10)
    @Builder.Default
    private String targetLevel = "新手";

    /** 分化方式：三分化/推拉腿/五分化/全身 */
    @Column(nullable = false, length = 20)
    private String splitType;

    /** 是否启用：0-禁用 1-启用 */
    @Column(columnDefinition = "TINYINT DEFAULT 1")
    @Builder.Default
    private Integer isActive = 1;

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
        if (isActive == null) isActive = 1;
        if (targetLevel == null) targetLevel = "新手";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

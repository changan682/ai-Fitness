package com.fitness.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 身体数据追踪实体 — 对应 t_body_metric 表
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "t_body_metric", indexes = {
        // columnList 用物理列名（snake_case），与 sql/init.sql 保持一致
        @Index(name = "idx_user_id", columnList = "user_id"),
        @Index(name = "idx_user_date", columnList = "user_id,record_date")
})
public class BodyMetric {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户ID */
    @Column(nullable = false)
    private Long userId;

    /** 记录日期 */
    @Column(nullable = false)
    private LocalDate recordDate;

    /** 体重(kg) */
    @Column(precision = 5, scale = 1)
    private BigDecimal weightKg;

    /** 腰围(cm) */
    @Column(precision = 5, scale = 1)
    private BigDecimal waistCm;

    /** 臂围(cm) */
    @Column(precision = 5, scale = 1)
    private BigDecimal armCm;

    /** 腿围(cm) */
    @Column(precision = 5, scale = 1)
    private BigDecimal legCm;

    /** 体脂率(%) */
    @Column(precision = 4, scale = 1)
    private BigDecimal bodyFatPct;

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
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

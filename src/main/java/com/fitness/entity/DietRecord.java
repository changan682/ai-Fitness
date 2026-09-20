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
 * 饮食记录实体 — 对应 t_diet_record 表
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "t_diet_record", indexes = {
        // columnList 用物理列名（snake_case），与 sql/init.sql 保持一致
        @Index(name = "idx_user_id", columnList = "user_id"),
        @Index(name = "idx_user_date", columnList = "user_id,record_date"),
        @Index(name = "idx_record_date", columnList = "record_date")
})
public class DietRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户ID */
    @Column(nullable = false)
    private Long userId;

    /** 记录日期 */
    @Column(nullable = false)
    private LocalDate recordDate;

    /** 餐次：早餐/午餐/晚餐/加餐 */
    @Column(nullable = false, length = 10)
    private String mealType;

    /** 食物名称 */
    @Column(nullable = false, length = 100)
    private String foodName;

    /** 重量(克) */
    @Column(nullable = false)
    private Integer weightG;

    /** 热量(kcal) */
    @Column(nullable = false, precision = 7, scale = 1)
    private BigDecimal caloriesKcal;

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

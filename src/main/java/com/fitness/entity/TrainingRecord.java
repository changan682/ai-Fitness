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
 * 训练记录实体 — 对应 t_training_record 表
 * <p>
 * volume（训练容量）= sets × reps × weightKg，插入时自动计算
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "t_training_record", indexes = {
        // columnList 必须写物理列名（snake_case）：Hibernate 不对外键/索引列名套用隐式命名策略，
        // 写 Java 属性名会导致 create-drop（H2 测试库）建索引时报「列不存在」。
        @Index(name = "idx_user_id", columnList = "user_id"),
        @Index(name = "idx_user_date", columnList = "user_id,training_date"),
        @Index(name = "idx_user_action", columnList = "user_id,action_name"),
        @Index(name = "idx_training_date", columnList = "training_date")
})
public class TrainingRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户ID */
    @Column(nullable = false)
    private Long userId;

    /** 训练日期 */
    @Column(nullable = false)
    private LocalDate trainingDate;

    /** 动作名称（如：杠铃卧推） */
    @Column(nullable = false, length = 100)
    private String actionName;

    /** 组数 */
    @Column(nullable = false)
    private Integer sets;

    /** 每组的次数 */
    @Column(nullable = false)
    private Integer reps;

    /** 重量(kg) */
    @Column(nullable = false, precision = 6, scale = 1)
    private BigDecimal weightKg;

    /** 训练时长(分钟) */
    private Integer durationMin;

    /** 主观感受 RPE（1-10） */
    @Column(columnDefinition = "TINYINT")
    private Integer rpe;

    /** 训练容量 = sets × reps × weightKg，插入时自动计算 */
    @Column(nullable = false, precision = 10, scale = 1)
    private BigDecimal volume;

    /** 备注 */
    @Column(length = 500)
    private String remark;

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
        // 自动计算训练容量（BigDecimal 乘法避免 int 溢出）
        if (volume == null && sets != null && reps != null && weightKg != null) {
            this.volume = weightKg.multiply(BigDecimal.valueOf(sets))
                                   .multiply(BigDecimal.valueOf(reps));
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        // 更新时重新计算容量（BigDecimal 乘法避免 int 溢出）
        if (sets != null && reps != null && weightKg != null) {
            this.volume = weightKg.multiply(BigDecimal.valueOf(sets))
                                   .multiply(BigDecimal.valueOf(reps));
        }
    }
}

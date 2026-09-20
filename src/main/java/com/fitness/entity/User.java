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
 * 用户实体 — 对应 t_user 表
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "t_user")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 昵称 */
    @Column(nullable = false, length = 50)
    private String nickname;

    /** 性别：0-未设置 1-男 2-女 */
    @Column(columnDefinition = "TINYINT DEFAULT 0")
    private Integer gender;

    /** 出生日期 */
    private LocalDate birthDate;

    /**
     * 身高(cm)
     * <p>
     * 必须用 BigDecimal 而不是 Double：DDL 里是 {@code DECIMAL(5,1)}，
     * 而 Hibernate 6 对浮点类型（Double/Float）带 {@code scale} 会直接抛
     * {@code IllegalArgumentException: scale has no meaning for SQL floating point types}，
     * 导致 entityManagerFactory 创建失败、**应用完全起不来**。
     * 项目其余 5 个实体的 DECIMAL 列也都用的是 BigDecimal，此处与之统一。
     */
    @Column(precision = 5, scale = 1)
    private BigDecimal height;

    /** 体重(kg) — 注册时初始体重，后续体重变化记录在 t_body_metric */
    @Column(precision = 5, scale = 1)
    private BigDecimal weight;

    /** 训练目标：增肌/减脂/保持 */
    @Column(length = 20, columnDefinition = "VARCHAR(20) DEFAULT '保持'")
    private String trainingGoal;

    /** 训练年限：新手/进阶/老手 */
    @Column(length = 10, columnDefinition = "VARCHAR(10) DEFAULT '新手'")
    private String trainingLevel;

    /** 伤病记录（JSON数组字符串） */
    @Column(columnDefinition = "TEXT")
    private String injuryRecord;

    /** 手机号 — 唯一登录凭证 */
    @Column(nullable = false, unique = true, length = 20)
    private String phone;

    /** 密码（BCrypt 加密） */
    @Column(nullable = false, length = 255)
    private String password;

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
        if (gender == null) gender = 0;
        if (trainingGoal == null) trainingGoal = "保持";
        if (trainingLevel == null) trainingLevel = "新手";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

package com.fitness.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 食物热量库实体 — 对应 t_food_library 表
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "t_food_library")
public class FoodLibrary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 食物名称 */
    @Column(nullable = false, length = 100, unique = true)
    private String foodName;

    /** 分类 */
    @Column(nullable = false, length = 20)
    private String category;

    /**
     * 每100g热量(kcal)
     * <p>
     * ⚠️ <b>必须显式写 name</b>：隐式物理名推导（Spring Boot 默认的
     * {@code CamelCaseToUnderscoresNamingStrategy}）只在「小写→大写→小写」处插下划线，
     * 数字不构成词边界，因此 {@code caloriesPer100g} 会被推导成 {@code calories_per100g}，
     * 而 {@code sql/init.sql} 里的列名是 {@code calories_per_100g}。
     * 由于 {@code ddl-auto: none}（DDL 由 init.sql 手工管理），Hibernate 不会校验，
     * 不一致的表现是运行期 MySQL 1054 {@code Unknown column}，
     * 且测试用的 H2 是 create-drop（由实体反推建表），根本测不出来。
     */
    @Column(name = "calories_per_100g", nullable = false, precision = 6, scale = 1)
    private BigDecimal caloriesPer100g;

    /** 每100g蛋白质(g) — 同上，隐式名会得到 {@code protein_per100g}，故显式指定 */
    @Column(name = "protein_per_100g", precision = 5, scale = 1)
    private BigDecimal proteinPer100g;

    /** 每100g脂肪(g) — 同上 */
    @Column(name = "fat_per_100g", precision = 5, scale = 1)
    private BigDecimal fatPer100g;

    /** 每100g碳水(g) — 同上 */
    @Column(name = "carbs_per_100g", precision = 5, scale = 1)
    private BigDecimal carbsPer100g;

    @Column(updatable = false, columnDefinition = "DATETIME DEFAULT CURRENT_TIMESTAMP")
    private LocalDateTime createdAt;

    @Column(columnDefinition = "DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP")
    private LocalDateTime updatedAt;
}

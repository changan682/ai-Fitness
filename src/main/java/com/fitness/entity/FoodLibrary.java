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

    /** 每100g热量(kcal) */
    @Column(nullable = false, precision = 6, scale = 1)
    private BigDecimal caloriesPer100g;

    /** 每100g蛋白质(g) */
    @Column(precision = 5, scale = 1)
    private BigDecimal proteinPer100g;

    /** 每100g脂肪(g) */
    @Column(precision = 5, scale = 1)
    private BigDecimal fatPer100g;

    /** 每100g碳水(g) */
    @Column(precision = 5, scale = 1)
    private BigDecimal carbsPer100g;

    @Column(updatable = false, columnDefinition = "DATETIME DEFAULT CURRENT_TIMESTAMP")
    private LocalDateTime createdAt;

    @Column(columnDefinition = "DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP")
    private LocalDateTime updatedAt;
}

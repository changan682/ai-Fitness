package com.fitness.repository;

import com.fitness.entity.TrainingRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface TrainingRecordRepository extends JpaRepository<TrainingRecord, Long> {

    /** 按用户ID和日期范围查询 */
    Page<TrainingRecord> findByUserIdAndTrainingDateBetween(
            Long userId, LocalDate startDate, LocalDate endDate, Pageable pageable);

    /** 按用户ID和日期范围查询（不分页） */
    List<TrainingRecord> findByUserIdAndTrainingDateBetween(
            Long userId, LocalDate startDate, LocalDate endDate);

    /** 按动作名称模糊查询 */
    @Query("SELECT t FROM TrainingRecord t WHERE t.userId = :userId " +
            "AND t.actionName LIKE %:actionName% " +
            "AND t.trainingDate BETWEEN :startDate AND :endDate " +
            "ORDER BY t.trainingDate DESC")
    Page<TrainingRecord> findByUserIdAndActionNameContaining(
            @Param("userId") Long userId,
            @Param("actionName") String actionName,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            Pageable pageable);

    /** 查询某用户某天的所有训练记录 */
    List<TrainingRecord> findByUserIdAndTrainingDate(Long userId, LocalDate date);

    /**
     * 查询某用户「指定日期之前」最近一次有训练记录的日期
     * <p>
     * 供 AI 训练总结组装「与上次训练对比」数据使用（规范 7.1 第2步）。
     * 用方法名派生而非原生 SQL，避免再写一份 MySQL 专有语法（H2 测试也能跑）。
     */
    Optional<TrainingRecord> findFirstByUserIdAndTrainingDateLessThanOrderByTrainingDateDesc(
            Long userId, LocalDate date);

    /** 查询某动作的历史最大重量 */
    @Query("SELECT MAX(t.weightKg) FROM TrainingRecord t WHERE t.userId = :userId AND t.actionName = :actionName")
    Optional<java.math.BigDecimal> findMaxWeightByUserIdAndActionName(
            @Param("userId") Long userId, @Param("actionName") String actionName);

    /** 查询某动作的历史最大容量 */
    @Query("SELECT MAX(t.volume) FROM TrainingRecord t WHERE t.userId = :userId AND t.actionName = :actionName")
    Optional<java.math.BigDecimal> findMaxVolumeByUserIdAndActionName(
            @Param("userId") Long userId, @Param("actionName") String actionName);

    /** 查询指定日期有训练记录的去重用户ID（每日提醒使用） */
    @Query("SELECT DISTINCT t.userId FROM TrainingRecord t WHERE t.trainingDate = :date")
    List<Long> findDistinctUserIdsByTrainingDate(@Param("date") LocalDate date);

    /**
     * 查询用户在 [startDate, endDate] 区间内「有训练记录的日期」（按日期去重、倒序）
     * <p>
     * 这是「连续训练天数」（规范 9.1）的取数入口：把有记录的日期取回来，
     * 在 {@code StatsService} 里按日历逐日回溯算出连续天数。
     * <p>
     * <b>为什么不写成 MySQL 递归 CTE</b>：原生 SQL 迁移性差 —— 之前那版 CTE 用了
     * {@code DATE_SUB} / {@code INTERVAL 1 DAY}，H2 测试库都跑不了，只能额外写 SQL 改写器
     * 去给测试打补丁；而且「今天没练不算断」这类业务规则埋进 SQL 后既难读也难测。
     * 改成「JPQL 取日期 + Java 算逻辑」后，MySQL 与 H2 通用，规则也变成可单测的普通代码。
     */
    @Query("SELECT DISTINCT t.trainingDate FROM TrainingRecord t " +
            "WHERE t.userId = :userId AND t.trainingDate BETWEEN :startDate AND :endDate " +
            "ORDER BY t.trainingDate DESC")
    List<LocalDate> findDistinctTrainingDates(@Param("userId") Long userId,
                                              @Param("startDate") LocalDate startDate,
                                              @Param("endDate") LocalDate endDate);
}

package com.fitness.repository;

import com.fitness.entity.DietRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface DietRecordRepository extends JpaRepository<DietRecord, Long> {

    /** 按用户ID和日期查询 */
    List<DietRecord> findByUserIdAndRecordDateOrderByMealTypeAsc(Long userId, LocalDate recordDate);

    /** 按用户ID和日期范围查询 */
    List<DietRecord> findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(
            Long userId, LocalDate startDate, LocalDate endDate);

    /** 某天总热量统计 */
    @Query("SELECT COALESCE(SUM(d.caloriesKcal), 0) FROM DietRecord d " +
            "WHERE d.userId = :userId AND d.recordDate = :recordDate")
    BigDecimal sumCaloriesByUserIdAndDate(@Param("userId") Long userId,
                                           @Param("recordDate") LocalDate recordDate);
}

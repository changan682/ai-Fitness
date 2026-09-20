package com.fitness.repository;

import com.fitness.entity.BodyMetric;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface BodyMetricRepository extends JpaRepository<BodyMetric, Long> {

    /** 按用户ID和日期范围查询（升序） */
    List<BodyMetric> findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(
            Long userId, LocalDate startDate, LocalDate endDate);

    /** 查询某用户的最新体测记录 */
    Optional<BodyMetric> findTopByUserIdOrderByRecordDateDesc(Long userId);

    /** 查询某用户某日已存在的记录（用于去重） */
    Optional<BodyMetric> findByUserIdAndRecordDate(Long userId, LocalDate recordDate);

    /** 查询某用户指定日期（含当天）之前最近的一条记录 — 用于计算7日体重变化基准 */
    Optional<BodyMetric> findFirstByUserIdAndRecordDateLessThanEqualOrderByRecordDateDesc(
            Long userId, LocalDate recordDate);
}

package com.fitness.repository;

import com.fitness.entity.UserWorkoutSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * 用户训练安排 Repository
 */
@Repository
public interface UserWorkoutScheduleRepository extends JpaRepository<UserWorkoutSchedule, Long> {

    /** 查用户在指定日期区间内的安排（按日期升序） */
    List<UserWorkoutSchedule> findByUserIdAndScheduleDateBetweenOrderByScheduleDateAscIdAsc(
            Long userId, LocalDate startDate, LocalDate endDate);

    /** 查某天的安排 */
    List<UserWorkoutSchedule> findByUserIdAndScheduleDateOrderByIdAsc(Long userId, LocalDate scheduleDate);

    /** 删除某周已有安排 — 重复套用模板时先清空本周，实现「覆盖」语义 */
    void deleteByUserIdAndScheduleDateBetween(Long userId, LocalDate startDate, LocalDate endDate);
}

package com.fitness.repository;

import com.fitness.entity.WeeklyPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface WeeklyPlanRepository extends JpaRepository<WeeklyPlan, Long> {

    /** 按用户和周起始日期查询（周统计幂等更新使用） */
    Optional<WeeklyPlan> findByUserIdAndWeekStart(Long userId, LocalDate weekStart);

    /**
     * 按 MQ 任务 ID 查询
     * <p>
     * 供 AI 回调的幂等检查使用：先显式查一次（走 uk_task_id），
     * 而不是等 INSERT 撞唯一索引抛异常 —— 显式查询能把「重复回调」这条正常业务分支
     * 和「数据库异常」区分开，日志与返回值都不一样。
     */
    Optional<WeeklyPlan> findByTaskId(String taskId);

    /** 查询某用户最新的一条周计划 */
    Optional<WeeklyPlan> findFirstByUserIdOrderByWeekStartDesc(Long userId);
}

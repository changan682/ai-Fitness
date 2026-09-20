package com.fitness.service;

import com.fitness.dto.WeeklyPlanResponse;
import com.fitness.entity.WeeklyPlan;
import com.fitness.repository.WeeklyPlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 周计划查询服务 — 提示词 9.2
 * <p>
 * 数据来源：t_weekly_plan。第2周由 ScheduledTasks#weeklyStatsReport 写入 week_summary；
 * 第7周由 Python 异步回调写入 suggestion_text。此处只负责读取最新一条。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeeklyPlanService {

    private final WeeklyPlanRepository weeklyPlanRepository;

    /**
     * 查询用户最新周计划
     *
     * @return 无数据时返回 null（规范要求 data 为 null，而非报错）
     */
    public WeeklyPlanResponse getLatest(Long userId) {
        Optional<WeeklyPlan> latest = weeklyPlanRepository.findFirstByUserIdOrderByWeekStartDesc(userId);
        if (latest.isEmpty()) {
            log.debug("用户暂无周计划: userId={}", userId);
            return null;
        }
        return toResponse(latest.get());
    }

    private WeeklyPlanResponse toResponse(WeeklyPlan plan) {
        return WeeklyPlanResponse.builder()
                .id(plan.getId())
                .weekStart(plan.getWeekStart() == null ? null : plan.getWeekStart().toString())
                .suggestionText(plan.getSuggestionText())
                .weekSummary(plan.getWeekSummary())
                .isRead(plan.getIsRead() != null && plan.getIsRead() == 1)
                .createdAt(plan.getCreatedAt())
                .build();
    }
}

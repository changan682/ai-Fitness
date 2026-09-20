package com.fitness.controller;

import com.fitness.common.BaseController;
import com.fitness.common.Result;
import com.fitness.service.StatsService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

/**
 * 统计模块 Controller — /api/v1/stats/*
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/stats")
@RequiredArgsConstructor
public class StatsController extends BaseController {

    private final StatsService statsService;

    /** 9.1 Dashboard 首页统计卡片 */
    @GetMapping("/dashboard")
    public Result<Map<String, Object>> getDashboard(
            HttpServletRequest request,
            @RequestParam(required = false) String date) {
        LocalDate queryDate = date != null && !date.isBlank() ? LocalDate.parse(date) : LocalDate.now();
        return Result.ok(statsService.getDashboard(getUserId(request), queryDate));
    }

    /**
     * 9.3 本周训练统计
     * <p>
     * weekStart 可选：不传则统计「今天所在周」；传任意一天会归一到该周周一。
     * 此前该参数缺失，前端按规范传 ?weekStart=… 会被静默忽略、永远只能看到当前周。
     */
    @GetMapping("/weekly")
    public Result<Map<String, Object>> getWeeklyStats(
            HttpServletRequest request,
            @RequestParam(required = false) String weekStart) {
        Long userId = getUserId(request);
        Map<String, Object> stats = (weekStart != null && !weekStart.isBlank())
                ? statsService.getWeeklyStats(userId, LocalDate.parse(weekStart))
                : statsService.getWeeklyStats(userId);
        return Result.ok(stats);
    }
}

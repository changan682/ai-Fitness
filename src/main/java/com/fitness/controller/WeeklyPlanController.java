package com.fitness.controller;

import com.fitness.common.BaseController;
import com.fitness.common.Result;
import com.fitness.dto.WeeklyPlanResponse;
import com.fitness.service.WeeklyPlanService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 周计划查询模块 Controller — /api/v1/weekly-plan/*
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/weekly-plan")
@RequiredArgsConstructor
public class WeeklyPlanController extends BaseController {

    private final WeeklyPlanService weeklyPlanService;

    /**
     * 查询最新周计划 — 提示词 9.2
     * <p>
     * 无数据时返回 data=null（前端展示 Empty 状态），不返回错误码。
     */
    @GetMapping("/latest")
    public Result<WeeklyPlanResponse> getLatest(HttpServletRequest request) {
        return Result.ok(weeklyPlanService.getLatest(getUserId(request)));
    }
}

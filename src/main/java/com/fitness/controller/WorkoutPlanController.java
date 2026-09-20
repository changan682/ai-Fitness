package com.fitness.controller;

import com.fitness.common.BaseController;
import com.fitness.common.Result;
import com.fitness.dto.ApplyScheduleRequest;
import com.fitness.dto.ApplyScheduleResponse;
import com.fitness.dto.WorkoutTemplateResponse;
import com.fitness.service.WorkoutPlanService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 训练计划模板模块 Controller — /api/v1/workout/*
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/workout")
@RequiredArgsConstructor
public class WorkoutPlanController extends BaseController {

    private final WorkoutPlanService workoutPlanService;

    /** 查看所有模板（含动作明细）— 提示词 6.1 */
    @GetMapping("/templates")
    public Result<Map<String, Object>> listTemplates() {
        List<WorkoutTemplateResponse> templates = workoutPlanService.listTemplates();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", templates);
        return Result.ok(data);
    }

    /** 套用模板生成一周训练安排 — 提示词 6.2 */
    @PostMapping("/schedule/apply")
    public Result<ApplyScheduleResponse> applySchedule(HttpServletRequest request,
                                                       @Valid @RequestBody ApplyScheduleRequest req) {
        ApplyScheduleResponse response = workoutPlanService.applyTemplate(getUserId(request), req);
        return Result.ok("本周训练安排已生成（共" + response.getDays().size() + "个训练日）", response);
    }
}

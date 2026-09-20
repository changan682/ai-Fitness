package com.fitness.controller;

import com.fitness.common.BaseController;
import com.fitness.common.Result;
import com.fitness.dto.DietDailyStatsResponse;
import com.fitness.dto.DietRecordListResponse;
import com.fitness.dto.DietRecordRequest;
import com.fitness.dto.DietRecordResponse;
import com.fitness.dto.DietStatsDailyResponse;
import com.fitness.service.DietRecordService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * 饮食记录模块 Controller — /api/v1/diet/*（3个接口）
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/diet")
@RequiredArgsConstructor
public class DietController extends BaseController {

    private final DietRecordService dietRecordService;

    /** 4.1 录入饮食记录（热量按食物库自动换算） */
    @PostMapping("/record")
    public Result<DietRecordResponse> addRecord(HttpServletRequest request,
                                                @Valid @RequestBody DietRecordRequest req) {
        DietRecordResponse response = dietRecordService.addRecord(getUserId(request), req);
        return Result.ok("饮食记录已保存", response);
    }

    /** 4.2 按日期查询饮食记录（返回 {list, date, totalCalories}） */
    @GetMapping("/records")
    public Result<DietRecordListResponse> queryByDate(
            HttpServletRequest request,
            @RequestParam String date) {
        DietRecordListResponse response = dietRecordService.getRecordsWithTotal(
                getUserId(request), LocalDate.parse(date));
        return Result.ok(response);
    }

    /** 每日饮食统计（按餐次汇总） */
    @GetMapping("/daily-stats")
    public Result<DietDailyStatsResponse> getDailyStats(
            HttpServletRequest request,
            @RequestParam String date) {
        DietDailyStatsResponse stats = dietRecordService.getDailyStats(
                getUserId(request), LocalDate.parse(date));
        return Result.ok(stats);
    }

    /** 4.3 区间每日热量统计 */
    @GetMapping("/stats/daily")
    public Result<DietStatsDailyResponse> getStatsDaily(
            HttpServletRequest request,
            @RequestParam String startDate,
            @RequestParam String endDate) {
        DietStatsDailyResponse stats = dietRecordService.getStatsByDateRange(
                getUserId(request), LocalDate.parse(startDate), LocalDate.parse(endDate));
        return Result.ok(stats);
    }
}

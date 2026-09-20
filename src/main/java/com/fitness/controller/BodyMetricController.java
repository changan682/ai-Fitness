package com.fitness.controller;

import com.fitness.common.BaseController;
import com.fitness.common.Result;
import com.fitness.dto.BodyMetricRequest;
import com.fitness.dto.BodyMetricResponse;
import com.fitness.dto.BodyMetricTrendResponse;
import com.fitness.dto.BodyMetricUpdateRequest;
import com.fitness.service.BodyMetricService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * 身体数据模块 Controller — /api/v1/body-metric*
 * <p>
 * 路径说明：接口清单里 3.1/3.1.1 用单数 {@code /api/v1/body-metric}，
 * 而 3.2 趋势接口写作复数 {@code /api/v1/body-metrics/trend}（规范内部不一致）。
 * 这里同时映射单复数两套前缀，两种写法都能调通，避免前端按任一写法对接时 404。
 */
@Slf4j
@RestController
@RequestMapping({"/api/v1/body-metric", "/api/v1/body-metrics"})
@RequiredArgsConstructor
public class BodyMetricController extends BaseController {

    private final BodyMetricService bodyMetricService;

    /** 3.1 录入身体数据 */
    @PostMapping
    public Result<BodyMetricResponse> addMetric(HttpServletRequest request,
                                                @Valid @RequestBody BodyMetricRequest req) {
        BodyMetricResponse response = bodyMetricService.addMetric(getUserId(request), req);
        return Result.ok("身体数据已记录", response);
    }

    /** 3.1.1 修改当天身体数据（只能改当天的记录） */
    @PutMapping("/{id}")
    public Result<BodyMetricResponse> updateMetric(HttpServletRequest request,
                                                   @PathVariable Long id,
                                                   @Valid @RequestBody BodyMetricUpdateRequest req) {
        BodyMetricResponse response = bodyMetricService.updateMetric(getUserId(request), id, req);
        return Result.ok("身体数据已更新", response);
    }

    /** 3.2 查询身体数据趋势（每个数据点含 7 日滑动平均体重） */
    @GetMapping("/trend")
    public Result<BodyMetricTrendResponse> getTrend(
            HttpServletRequest request,
            @RequestParam String startDate,
            @RequestParam String endDate) {
        BodyMetricTrendResponse response = bodyMetricService.getTrend(
                getUserId(request), LocalDate.parse(startDate), LocalDate.parse(endDate));
        return Result.ok(response);
    }

    /** 查询最新身体数据（Dashboard 体重卡片使用） */
    @GetMapping("/latest")
    public Result<BodyMetricResponse> getLatest(HttpServletRequest request) {
        return Result.ok(bodyMetricService.getLatest(getUserId(request)));
    }
}

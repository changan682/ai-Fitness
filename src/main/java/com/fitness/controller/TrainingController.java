package com.fitness.controller;

import com.fitness.common.BaseController;
import com.fitness.common.PageResult;
import com.fitness.common.Result;
import com.fitness.dto.TrainingBatchRequest;
import com.fitness.dto.TrainingRecordRequest;
import com.fitness.dto.TrainingRecordUpdateRequest;
import com.fitness.entity.TrainingRecord;
import com.fitness.service.TrainingRecordService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 训练记录模块 Controller — /api/v1/training/*（6个核心接口）
 * <p>
 * 核心业务：训练记录 CRUD + 批量录入 + 按动作查询。
 * 成功 msg 文案按接口清单逐条对齐。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/training")
@RequiredArgsConstructor
public class TrainingController extends BaseController {

    private final TrainingRecordService trainingRecordService;

    /** 2.1 新增单条训练记录 */
    @PostMapping("/record")
    public Result<TrainingRecord> addRecord(HttpServletRequest request,
                                            @Valid @RequestBody TrainingRecordRequest req) {
        return Result.ok(trainingRecordService.addRecord(getUserId(request), req));
    }

    /** 2.2 批量新增训练记录 */
    @PostMapping("/records/batch")
    public Result<Map<String, Object>> batchAddRecords(HttpServletRequest request,
                                                       @Valid @RequestBody TrainingBatchRequest req) {
        Map<String, Object> result = trainingRecordService.batchAddRecords(getUserId(request), req);
        return Result.ok("成功录入" + result.get("count") + "条训练记录", result);
    }

    /** 2.3 按日期范围查询训练记录（分页） */
    @GetMapping("/records")
    public Result<PageResult<TrainingRecord>> queryByDateRange(
            HttpServletRequest request,
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        PageResult<TrainingRecord> result = trainingRecordService.queryByDateRange(
                getUserId(request),
                LocalDate.parse(startDate),
                LocalDate.parse(endDate),
                page, size);
        return Result.ok(result);
    }

    /** 2.4 按动作名称查询训练记录（含该动作历史最大重量/容量） */
    @GetMapping("/records/by-action")
    public Result<Map<String, Object>> queryByAction(
            HttpServletRequest request,
            @RequestParam String actionName,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        // 不传区间则查全部历史（起始取一个足够早的日期）
        LocalDate start = startDate != null && !startDate.isBlank()
                ? LocalDate.parse(startDate) : LocalDate.of(2000, 1, 1);
        LocalDate end = endDate != null && !endDate.isBlank()
                ? LocalDate.parse(endDate) : LocalDate.now();
        return Result.ok(trainingRecordService.queryByAction(
                getUserId(request), actionName, start, end, page, size));
    }

    /** 2.5 修改训练记录 — 所有字段可选，传了就更新 */
    @PutMapping("/record/{id}")
    public Result<Map<String, Object>> updateRecord(HttpServletRequest request,
                                                    @PathVariable Long id,
                                                    @Valid @RequestBody TrainingRecordUpdateRequest req) {
        Map<String, Object> result = trainingRecordService.updateRecord(getUserId(request), id, req);
        return Result.ok("记录已更新", result);
    }

    /** 2.6 删除训练记录 */
    @DeleteMapping("/record/{id}")
    public Result<Void> deleteRecord(HttpServletRequest request, @PathVariable Long id) {
        trainingRecordService.deleteRecord(getUserId(request), id);
        return Result.ok("记录已删除", null);
    }

    /** 查询今日训练记录（快捷接口，供 Dashboard 使用） */
    @GetMapping("/today")
    public Result<List<TrainingRecord>> getTodayRecords(HttpServletRequest request) {
        return Result.ok(trainingRecordService.getTodayRecords(getUserId(request)));
    }
}

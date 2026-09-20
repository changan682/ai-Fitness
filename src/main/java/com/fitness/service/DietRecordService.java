package com.fitness.service;

import com.fitness.dto.DietDailyStatsResponse;
import com.fitness.dto.DietRecordListResponse;
import com.fitness.dto.DietRecordRequest;
import com.fitness.dto.DietRecordResponse;
import com.fitness.dto.DietStatsDailyResponse;
import com.fitness.entity.DietRecord;
import com.fitness.exception.BusinessException;
import com.fitness.exception.ErrorCode;
import com.fitness.repository.DietRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 饮食记录服务 — CRUD + 每日热量统计
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DietRecordService {

    private final DietRecordRepository dietRecordRepository;
    private final FoodLibraryService foodLibraryService;

    /** 录入饮食记录 — 自动从食物库查询热量计算 */
    @Transactional
    public DietRecordResponse addRecord(Long userId, DietRecordRequest req) {
        LocalDate recordDate = req.getRecordDate() != null
                ? LocalDate.parse(req.getRecordDate()) : LocalDate.now();

        // 从食物库查询热量
        BigDecimal caloriesPer100g = foodLibraryService.getCaloriesPer100g(req.getFoodName());
        if (caloriesPer100g == null) {
            throw new BusinessException(ErrorCode.FOOD_NOT_FOUND);
        }

        // 热量 = (每100g热量) × (实际重量g / 100)
        BigDecimal calories = caloriesPer100g
                .multiply(BigDecimal.valueOf(req.getWeightG()))
                .divide(BigDecimal.valueOf(100), 1, RoundingMode.HALF_UP);

        DietRecord record = DietRecord.builder()
                .userId(userId)
                .recordDate(recordDate)
                .mealType(req.getMealType())
                .foodName(req.getFoodName())
                .weightG(req.getWeightG())
                .caloriesKcal(calories)
                .build();

        record = dietRecordRepository.save(record);
        log.info("饮食记录录入: userId={}, food={}, calories={}kcal", userId, req.getFoodName(), calories);
        return toResponse(record);
    }

    /** 按日期查询饮食记录 */
    public List<DietRecordResponse> queryByDate(Long userId, LocalDate date) {
        return dietRecordRepository.findByUserIdAndRecordDateOrderByMealTypeAsc(userId, date)
                .stream().map(this::toResponse).toList();
    }

    /**
     * 按日期查询饮食记录（含当日总热量）— 规范 4.2
     * <p>
     * 规范要求返回 {@code {list, date, totalCalories}}，总热量走仓储层 SUM 聚合，
     * 避免把全量记录拉回内存再求和。
     */
    public DietRecordListResponse getRecordsWithTotal(Long userId, LocalDate date) {
        List<DietRecordResponse> list = queryByDate(userId, date);
        BigDecimal totalCalories = dietRecordRepository.sumCaloriesByUserIdAndDate(userId, date);

        return DietRecordListResponse.builder()
                .list(list)
                .date(date.toString())
                .totalCalories(totalCalories == null ? BigDecimal.ZERO : totalCalories)
                .build();
    }

    /** 区间每日热量统计（提示词 4.3）— 返回每天总热量+餐次数，及区间日均热量 */
    public DietStatsDailyResponse getStatsByDateRange(Long userId, LocalDate startDate, LocalDate endDate) {
        if (startDate.isAfter(endDate)) {
            throw new BusinessException(ErrorCode.DATE_RANGE_INVALID);
        }

        List<DietRecord> records = dietRecordRepository
                .findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(userId, startDate, endDate);

        // 按日期分组
        Map<LocalDate, List<DietRecord>> byDate = records.stream()
                .collect(Collectors.groupingBy(DietRecord::getRecordDate));

        List<DietStatsDailyResponse.DailyStat> list = new ArrayList<>();
        BigDecimal totalCalories = BigDecimal.ZERO;
        int daysWithRecords = 0;

        // 遍历区间内每一天，只统计有记录的天
        for (LocalDate d = startDate; !d.isAfter(endDate); d = d.plusDays(1)) {
            List<DietRecord> dayRecords = byDate.get(d);
            if (dayRecords == null || dayRecords.isEmpty()) continue;

            BigDecimal dayCalories = dayRecords.stream()
                    .map(DietRecord::getCaloriesKcal)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            list.add(DietStatsDailyResponse.DailyStat.builder()
                    .date(d.toString())
                    .totalCalories(dayCalories)
                    .mealCount(dayRecords.size())
                    .build());

            totalCalories = totalCalories.add(dayCalories);
            daysWithRecords++;
        }

        BigDecimal avgCalories = daysWithRecords > 0
                ? totalCalories.divide(BigDecimal.valueOf(daysWithRecords), 1, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return DietStatsDailyResponse.builder()
                .list(list)
                .avgCalories(avgCalories)
                .build();
    }

    /** 每日饮食统计 */
    public DietDailyStatsResponse getDailyStats(Long userId, LocalDate date) {
        List<DietRecord> records = dietRecordRepository
                .findByUserIdAndRecordDateOrderByMealTypeAsc(userId, date);

        BigDecimal totalCalories = records.stream()
                .map(DietRecord::getCaloriesKcal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return DietDailyStatsResponse.builder()
                .date(date.toString())
                .totalCalories(totalCalories)
                .records(records.stream().map(this::toResponse).toList())
                .breakfast(buildMealSummary(records, "早餐"))
                .lunch(buildMealSummary(records, "午餐"))
                .dinner(buildMealSummary(records, "晚餐"))
                .snack(buildMealSummary(records, "加餐"))
                .build();
    }

    private DietDailyStatsResponse.MealSummary buildMealSummary(List<DietRecord> records, String mealType) {
        List<DietRecord> filtered = records.stream()
                .filter(r -> mealType.equals(r.getMealType())).toList();
        BigDecimal calories = filtered.stream()
                .map(DietRecord::getCaloriesKcal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return DietDailyStatsResponse.MealSummary.builder()
                .itemCount(filtered.size())
                .calories(calories)
                .build();
    }

    private DietRecordResponse toResponse(DietRecord r) {
        return DietRecordResponse.builder()
                .id(r.getId()).userId(r.getUserId()).recordDate(r.getRecordDate())
                .mealType(r.getMealType()).foodName(r.getFoodName())
                .weightG(r.getWeightG()).caloriesKcal(r.getCaloriesKcal())
                .createdAt(r.getCreatedAt()).build();
    }
}

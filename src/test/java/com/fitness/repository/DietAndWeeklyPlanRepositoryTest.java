package com.fitness.repository;

import com.fitness.entity.DietRecord;
import com.fitness.entity.WeeklyPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Repository 层切片测试（@DataJpaTest + H2）
 * <p>
 * 覆盖 DietRecordRepository（3 个查询，含聚合 {@code @Query}）与 WeeklyPlanRepository（2 个派生查询）。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
// 测试专用方言：绕过主代码「Double + @Column(scale=1)」导致的元数据构建异常（见报告「发现的主代码问题」）
@TestPropertySource(properties = "spring.jpa.properties.hibernate.dialect=com.fitness.testsupport.LenientH2Dialect")
@DisplayName("Repository 测试：DietRecordRepository + WeeklyPlanRepository")
class DietAndWeeklyPlanRepositoryTest {

    private static final Long USER = 1001L;
    private static final Long OTHER_USER = 2002L;

    @Autowired
    private DietRecordRepository dietRecordRepository;

    @Autowired
    private WeeklyPlanRepository weeklyPlanRepository;

    @BeforeEach
    void cleanUp() {
        dietRecordRepository.deleteAll();
        weeklyPlanRepository.deleteAll();
    }

    // ==================== DietRecordRepository ====================

    @Test
    @DisplayName("sumCaloriesByUserIdAndDate：按日聚合总热量，无记录时由 COALESCE 兜底为 0")
    void sumCaloriesShouldAggregatePerDay() {
        // 用户 1001 当天：早餐 350.5 + 午餐 620.0 = 970.5
        saveDiet(USER, "2026-07-30", "早餐", "燕麦粥", 300, "350.5");
        saveDiet(USER, "2026-07-30", "午餐", "鸡胸肉", 200, "620.0");
        // 干扰数据：他人同一天、本人前一天，都不得计入
        saveDiet(OTHER_USER, "2026-07-30", "早餐", "包子", 200, "500.0");
        saveDiet(USER, "2026-07-29", "晚餐", "米饭", 200, "232.0");
        dietRecordRepository.flush();

        BigDecimal total = dietRecordRepository.sumCaloriesByUserIdAndDate(USER, LocalDate.parse("2026-07-30"));

        assertEquals(0, total.compareTo(new BigDecimal("970.5")),
                "当天总热量应为 350.5+620.0=970.5，且不含他人/其他日期的数据");
    }

    @Test
    @DisplayName("sumCaloriesByUserIdAndDate：无记录日期返回 0 而不是 null（避免前端 NaN）")
    void sumCaloriesShouldReturnZeroWhenNoRecord() {
        saveDiet(USER, "2026-07-30", "早餐", "燕麦粥", 300, "350.5");
        dietRecordRepository.flush();

        BigDecimal total = dietRecordRepository.sumCaloriesByUserIdAndDate(USER, LocalDate.parse("2026-07-31"));

        assertEquals(0, total.compareTo(BigDecimal.ZERO),
                "COALESCE(SUM(...), 0) 必须返回 0，否则前端展示热量会变成 NaN");
    }

    @Test
    @DisplayName("findByUserIdAndRecordDateOrderByMealTypeAsc：取某天全部饮食记录")
    void findByDateShouldReturnAllMealsOfThatDay() {
        saveDiet(USER, "2026-07-30", "早餐", "燕麦粥", 300, "350.5");
        saveDiet(USER, "2026-07-30", "午餐", "鸡胸肉", 200, "620.0");
        saveDiet(USER, "2026-07-30", "晚餐", "米饭", 200, "232.0");
        saveDiet(USER, "2026-07-31", "早餐", "鸡蛋", 100, "155.0");
        dietRecordRepository.flush();

        List<DietRecord> meals = dietRecordRepository
                .findByUserIdAndRecordDateOrderByMealTypeAsc(USER, LocalDate.parse("2026-07-30"));

        assertEquals(3, meals.size());
        assertEquals(Set.of("早餐", "午餐", "晚餐"),
                meals.stream().map(DietRecord::getMealType).collect(Collectors.toSet()));
        assertEquals(meals.stream().map(DietRecord::getMealType).sorted().collect(Collectors.toList()),
                meals.stream().map(DietRecord::getMealType).collect(Collectors.toList()),
                "方法名约定按 mealType 升序返回");
    }

    @Test
    @DisplayName("findByUserIdAndRecordDateBetweenOrderByRecordDateAsc：区间升序（周热量趋势用）")
    void findByDateRangeShouldReturnAscendingRecords() {
        saveDiet(USER, "2026-07-03", "早餐", "鸡蛋", 100, "155.0");
        saveDiet(USER, "2026-07-01", "早餐", "燕麦粥", 300, "350.5");
        saveDiet(USER, "2026-07-02", "午餐", "鸡胸肉", 200, "620.0");
        saveDiet(USER, "2026-07-10", "晚餐", "米饭", 200, "232.0");   // 区间外
        dietRecordRepository.flush();

        List<DietRecord> records = dietRecordRepository
                .findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(
                        USER, LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-03"));

        assertEquals(List.of("2026-07-01", "2026-07-02", "2026-07-03"),
                records.stream().map(r -> r.getRecordDate().toString()).toList(),
                "必须按日期升序返回且不含区间外数据");
    }

    // ==================== WeeklyPlanRepository ====================

    @Test
    @DisplayName("findFirstByUserIdOrderByWeekStartDesc：取该用户最新一周的计划")
    void findFirstShouldReturnLatestWeekPlan() {
        savePlan(USER, "2026-07-06", "{\"trainingCount\":2}");
        savePlan(USER, "2026-07-27", "{\"trainingCount\":5}");
        savePlan(USER, "2026-07-20", "{\"trainingCount\":4}");
        savePlan(OTHER_USER, "2026-08-03", "{\"trainingCount\":9}");   // 他人更新周，不得影响
        weeklyPlanRepository.flush();

        Optional<WeeklyPlan> latest = weeklyPlanRepository.findFirstByUserIdOrderByWeekStartDesc(USER);

        assertTrue(latest.isPresent());
        assertEquals("2026-07-27", latest.get().getWeekStart().toString(), "应取 weekStart 最大的一条");
        assertEquals("{\"trainingCount\":5}", latest.get().getWeekSummary());
    }

    @Test
    @DisplayName("findByUserIdAndWeekStart：周统计幂等写入依赖的定位查询")
    void findByUserIdAndWeekStartShouldLocatePlanForIdempotentUpdate() {
        savePlan(USER, "2026-07-06", "{\"trainingCount\":2}");
        weeklyPlanRepository.flush();

        Optional<WeeklyPlan> plan = weeklyPlanRepository
                .findByUserIdAndWeekStart(USER, LocalDate.parse("2026-07-06"));
        assertTrue(plan.isPresent(), "同一用户同一周应能定位到已有记录（避免重复插入）");
        assertEquals("weekly-stats-" + USER + "-2026-07-06", plan.get().getTaskId());

        assertTrue(weeklyPlanRepository
                        .findByUserIdAndWeekStart(USER, LocalDate.parse("2026-07-13")).isEmpty(),
                "不存在的一周应返回空，Service 据此新建记录");
    }

    @Test
    @DisplayName("保存时 @PrePersist 补齐 isRead/suggestionText 默认值（非空列依赖）")
    void saveShouldFillNotNullDefaults() {
        weeklyPlanRepository.save(WeeklyPlan.builder()
                .userId(USER)
                .taskId("weekly-stats-" + USER + "-2026-07-06")
                .weekStart(LocalDate.parse("2026-07-06"))
                .build());
        weeklyPlanRepository.flush();

        WeeklyPlan saved = weeklyPlanRepository
                .findByUserIdAndWeekStart(USER, LocalDate.parse("2026-07-06")).orElseThrow();
        assertEquals(0, saved.getIsRead().intValue(), "isRead 默认 0（未读）");
        assertEquals("", saved.getSuggestionText(), "suggestionText 为 NOT NULL 列，缺省时必须写成空串");
    }

    // ==================== 测试数据 ====================

    private void saveDiet(Long userId, String date, String mealType, String foodName,
                          int weightG, String calories) {
        dietRecordRepository.save(DietRecord.builder()
                .userId(userId)
                .recordDate(LocalDate.parse(date))
                .mealType(mealType)
                .foodName(foodName)
                .weightG(weightG)
                .caloriesKcal(new BigDecimal(calories))
                .build());
    }

    private void savePlan(Long userId, String weekStart, String summary) {
        weeklyPlanRepository.save(WeeklyPlan.builder()
                .userId(userId)
                .taskId("weekly-stats-" + userId + "-" + weekStart)
                .weekStart(LocalDate.parse(weekStart))
                .weekSummary(summary)
                .suggestionText("")
                .build());
    }
}

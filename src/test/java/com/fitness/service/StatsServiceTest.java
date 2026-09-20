package com.fitness.service;

import com.fitness.cache.RedisCacheService;
import com.fitness.entity.BodyMetric;
import com.fitness.entity.TrainingRecord;
import com.fitness.repository.BodyMetricRepository;
import com.fitness.repository.DietRecordRepository;
import com.fitness.repository.TrainingRecordRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 统计服务单元测试 —— 重点覆盖「连续训练天数」的边界规则（规范 9.1 / 第 8 条）
 * <p>
 * 纯 Mockito 单元测试：连续天数的判定逻辑已从 MySQL 原生递归 CTE 搬到 Java
 * （{@link StatsService#countConsecutiveTrainingDays}），因此可以直接对规则做边界断言。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StatsServiceTest {

    private static final Long USER = 1001L;

    /** 统计基准日：2026-07-30（周四），所在周的周一是 07-27 */
    private static final LocalDate TODAY = LocalDate.parse("2026-07-30");

    @Mock
    private TrainingRecordRepository trainingRecordRepository;

    @Mock
    private BodyMetricRepository bodyMetricRepository;

    @Mock
    private DietRecordRepository dietRecordRepository;

    @Mock
    private RedisCacheService redisCacheService;

    @Mock
    private BodyMetricService bodyMetricService;

    @InjectMocks
    private StatsService statsService;

    // ==================== 连续训练天数：3 条判定规则 ====================

    @Test
    @DisplayName("规则(1)：今天有训练记录 → 从今天开始逐日倒查")
    void streakShouldStartFromTodayWhenTrainedToday() {
        givenTrainedDates("2026-07-30", "2026-07-29", "2026-07-28", "2026-07-26");

        assertEquals(3, statsService.countConsecutiveTrainingDays(USER, TODAY),
                "07-30/29/28 连续 3 天，07-27 断档 → 3");
    }

    @Test
    @DisplayName("规则(2)：今天没练但昨天练了 → 从昨天算，今天未练不算断")
    void streakShouldStartFromYesterdayWhenNotTrainedToday() {
        givenTrainedDates("2026-07-29", "2026-07-28", "2026-07-27");

        assertEquals(3, statsService.countConsecutiveTrainingDays(USER, TODAY),
                "用户今天还没练，但 07-29/28/27 连续 → 返回 3 而不是 0");
    }

    @Test
    @DisplayName("规则(3)：今天与昨天都没记录 → 返回 0")
    void streakShouldReturnZeroWhenNeitherTodayNorYesterdayTrained() {
        givenTrainedDates("2026-07-28", "2026-07-27");

        assertEquals(0, statsService.countConsecutiveTrainingDays(USER, TODAY),
                "07-29 与 07-30 都无记录 → 连续记录已中断，返回 0");
    }

    @Test
    @DisplayName("宽限期只有 1 天：不允许跳过更早的断档")
    void streakShouldNotTolerateGapsOlderThanYesterday() {
        givenTrainedDates("2026-07-29", "2026-07-27", "2026-07-26");

        assertEquals(1, statsService.countConsecutiveTrainingDays(USER, TODAY),
                "从 07-29 起算，07-28 断档 → 只有 1 天（不能把 07-27/26 接上来）");
    }

    @Test
    @DisplayName("完全没有训练记录 → 0（不抛异常）")
    void streakShouldReturnZeroWhenNoRecords() {
        givenTrainedDates();

        assertEquals(0, statsService.countConsecutiveTrainingDays(USER, TODAY));
    }

    @Test
    @DisplayName("同一天练多次只算 1 天（日期去重）")
    void streakShouldCountEachDayOnce() {
        // repository 已按日期去重，这里给出 3 个不同日期，模拟「每天都有练」
        givenTrainedDates("2026-07-30", "2026-07-29", "2026-07-28");

        assertEquals(3, statsService.countConsecutiveTrainingDays(USER, TODAY));
    }

    // ==================== Dashboard 响应字段 ====================

    @Test
    @DisplayName("getDashboard：consecutiveTrainingDays 采用「今日未练不算断」口径")
    void dashboardShouldExposeStreakWithGracePeriod() {
        when(trainingRecordRepository.findByUserIdAndTrainingDate(eq(USER), eq(TODAY)))
                .thenReturn(List.of());                       // 今天还没练
        when(trainingRecordRepository.findDistinctTrainingDates(eq(USER), any(), eq(TODAY)))
                .thenReturn(List.of(LocalDate.parse("2026-07-29"), LocalDate.parse("2026-07-28")));
        when(trainingRecordRepository.findByUserIdAndTrainingDateBetween(eq(USER), any(), eq(TODAY)))
                .thenReturn(List.of(record("2026-07-29"), record("2026-07-28")));
        when(bodyMetricRepository.findTopByUserIdOrderByRecordDateDesc(USER))
                .thenReturn(Optional.of(BodyMetric.builder()
                        .userId(USER).recordDate(TODAY).weightKg(new BigDecimal("70.5")).build()));
        when(bodyMetricRepository.findFirstByUserIdAndRecordDateLessThanEqualOrderByRecordDateDesc(
                eq(USER), any())).thenReturn(Optional.empty());
        when(bodyMetricService.getLatestAvg7d(USER)).thenReturn(null);

        Map<String, Object> dashboard = statsService.getDashboard(USER, TODAY);

        assertEquals(2, dashboard.get("consecutiveTrainingDays"),
                "今天未练但昨前天有记录 → 连续 2 天（若返回 0 就是体验缺陷）");
        assertEquals("2026-07-30", dashboard.get("date"));
        assertEquals(2L, dashboard.get("weeklyTrainingDays"), "本周（07-27 起）已训练 07-28/29 两天");
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) dashboard.get("todayTotalVolume")),
                "今天无记录 → 今日总容量为 0");
    }

    // ==================== 测试数据 ====================

    private void givenTrainedDates(String... dates) {
        List<LocalDate> trained = java.util.Arrays.stream(dates)
                .map(LocalDate::parse)
                .sorted(java.util.Comparator.reverseOrder())    // repository 返回倒序
                .toList();
        when(trainingRecordRepository.findDistinctTrainingDates(eq(USER), any(), eq(TODAY)))
                .thenReturn(trained);
    }

    private TrainingRecord record(String date) {
        return TrainingRecord.builder()
                .userId(USER)
                .trainingDate(LocalDate.parse(date))
                .actionName("杠铃深蹲")
                .sets(4).reps(10)
                .weightKg(new BigDecimal("80.0"))
                .volume(new BigDecimal("3200.0"))
                .build();
    }
}

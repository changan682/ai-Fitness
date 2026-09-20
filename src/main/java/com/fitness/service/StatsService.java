package com.fitness.service;

import com.fitness.cache.CacheKeys;
import com.fitness.cache.RedisCacheService;
import com.fitness.entity.BodyMetric;
import com.fitness.entity.TrainingRecord;
import com.fitness.repository.BodyMetricRepository;
import com.fitness.repository.DietRecordRepository;
import com.fitness.repository.TrainingRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 统计服务 — Dashboard数据 + 本周训练统计
 * <p>
 * 字段结构与提示词 9.1 / 9.3 对齐；本周统计走 Redis 缓存（规范 2.10，TTL 1小时）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatsService {

    /** 本周统计缓存基准 TTL（秒）— 规范 2.10 */
    private static final long WEEKLY_STATS_TTL_SECONDS = CacheKeys.STATS_WEEKLY_TTL_SECONDS;

    /** 回源互斥锁持有时间（秒），需大于一次统计查询耗时 */
    private static final long WEEKLY_STATS_LOCK_SECONDS = 30L;

    /**
     * 连续训练天数最多回溯多少天
     * <p>
     * 用户可能常年训练，不加上限时这条「逐日回溯」在最坏情况下要循环很多次；
     * 365 天足够覆盖任何真实的打卡连续记录，同时把单次查询的取数区间固定下来。
     */
    private static final int MAX_STREAK_LOOKBACK_DAYS = 365;

    private final TrainingRecordRepository trainingRecordRepository;
    private final BodyMetricRepository bodyMetricRepository;
    private final DietRecordRepository dietRecordRepository;
    private final RedisCacheService redisCacheService;
    private final BodyMetricService bodyMetricService;

    /** Dashboard 统计数据（提示词 9.1） */
    public Map<String, Object> getDashboard(Long userId, LocalDate date) {
        // 今日训练
        List<TrainingRecord> todayRecords = trainingRecordRepository
                .findByUserIdAndTrainingDate(userId, date);

        int actionCount = (int) todayRecords.stream()
                .map(TrainingRecord::getActionName).distinct().count();

        BigDecimal totalVolume = todayRecords.stream()
                .map(TrainingRecord::getVolume)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        int consecutiveDays = countConsecutiveTrainingDays(userId, date);

        // 本周已训练天数（本周一 至 查询日期）
        LocalDate weekStart = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        List<TrainingRecord> weekRecords = trainingRecordRepository
                .findByUserIdAndTrainingDateBetween(userId, weekStart, date);
        long weeklyTrainingDays = weekRecords.stream()
                .map(TrainingRecord::getTrainingDate).distinct().count();

        // 最新体重 + 7日变化（来自 t_body_metric，非 t_user）
        BodyMetric latest = bodyMetricRepository
                .findTopByUserIdOrderByRecordDateDesc(userId).orElse(null);
        BigDecimal latestWeight = latest != null ? latest.getWeightKg() : null;

        BigDecimal weightChange7d = null;
        if (latestWeight != null) {
            BodyMetric base = bodyMetricRepository
                    .findFirstByUserIdAndRecordDateLessThanEqualOrderByRecordDateDesc(
                            userId, date.minusDays(7))
                    .orElse(null);
            if (base != null && base.getWeightKg() != null) {
                weightChange7d = latestWeight.subtract(base.getWeightKg());
            }
        }

        // 7日滑动平均体重 — 走 metric:avg7d 缓存（规范 2.5），供前端体重卡片展示趋势
        BigDecimal weightAvg7d = null;
        try {
            weightAvg7d = bodyMetricService.getLatestAvg7d(userId);
        } catch (Exception e) {
            log.warn("7日滑动均值计算失败，Dashboard 该项返回 null: userId={}", userId);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", date.toString());
        result.put("todayActionCount", actionCount);
        result.put("todayTotalVolume", totalVolume);
        result.put("consecutiveTrainingDays", consecutiveDays);
        result.put("weeklyTrainingDays", weeklyTrainingDays);
        result.put("latestWeight", latestWeight);
        result.put("weightChange7d", weightChange7d);
        result.put("weightAvg7d", weightAvg7d);
        return result;
    }

    /**
     * 计算「连续训练天数」（规范 9.1 口径，论文需写明边界规则）
     * <p>
     * 规则：
     * <ol>
     *   <li>{@code date} 当天有训练记录 → 从 {@code date} 开始逐日倒查，遇到第一个无记录的日期停止；</li>
     *   <li>{@code date} 当天无记录、但 {@code date-1} 有记录 → 从 {@code date-1} 开始倒查
     *       （<b>今天还没练不算断</b>，否则用户上午打开 App 就会看到连续天数被清零）；</li>
     *   <li>{@code date} 与 {@code date-1} 都没有记录 → 返回 0。</li>
     * </ol>
     * 只允许「跳过今天这一天」，不允许跳过更早的任何日期（宽限期严格为 1 天）；
     * 同一天练多次只算 1 天（按日期去重）。
     * <p>
     * 例：用户在 07-28、07-29 有记录，查询日期为 07-30（当天尚未训练）→ 返回 2，而不是 0。
     *
     * @param userId 用户 ID
     * @param date   统计基准日期（通常为今天）
     * @return 连续训练天数
     */
    public int countConsecutiveTrainingDays(Long userId, LocalDate date) {
        LocalDate lookbackStart = date.minusDays(MAX_STREAK_LOOKBACK_DAYS);
        Set<LocalDate> trainedDates = new HashSet<>(trainingRecordRepository
                .findDistinctTrainingDates(userId, lookbackStart, date));

        // 起点：今天练了从今天算；今天没练但昨天练了，从昨天算（宽限 1 天）
        LocalDate cursor;
        if (trainedDates.contains(date)) {
            cursor = date;
        } else if (trainedDates.contains(date.minusDays(1))) {
            cursor = date.minusDays(1);
        } else {
            return 0;
        }

        int days = 0;
        while (trainedDates.contains(cursor)) {
            days++;
            cursor = cursor.minusDays(1);
        }
        return days;
    }

    /** 本周训练统计 — 默认统计「今天所在周」 */
    public Map<String, Object> getWeeklyStats(Long userId) {
        return getWeeklyStats(userId, LocalDate.now());
    }

    /**
     * 本周训练统计（提示词 9.3）
     * <p>
     * 入参 anyDayInWeek 会被归一化到所在周的周一：规范要求 weekStart 传周一，
     * 但前端若传了周中某天，静默按「那一条数据算不出结果」处理会很迷惑，
     * 因此统一按该日期所在周计算，响应里的 weekStart/weekEnd 即实际统计区间。
     * <p>
     * 缓存：stats:weekly:{userId}:{weekStart}，TTL 1小时 + ±300s 扰动；
     * 回源时用互斥锁防击穿（规范第五章 5）；训练记录增删改会主动失效该 key。
     */
    public Map<String, Object> getWeeklyStats(Long userId, LocalDate anyDayInWeek) {
        LocalDate weekStart = anyDayInWeek.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        String cacheKey = CacheKeys.statsWeekly(userId, weekStart);
        String lockKey = CacheKeys.lockStatsWeekly(userId, weekStart);

        return redisCacheService.getOrLoad(cacheKey, lockKey,
                WEEKLY_STATS_TTL_SECONDS, WEEKLY_STATS_LOCK_SECONDS,
                () -> computeWeeklyStats(userId, weekStart));
    }

    /** 实际统计计算（无缓存逻辑，便于单测与缓存回源） */
    private Map<String, Object> computeWeeklyStats(Long userId, LocalDate weekStart) {
        LocalDate weekEnd = weekStart.plusDays(6);
        LocalDate today = LocalDate.now();

        List<TrainingRecord> weekRecords = trainingRecordRepository
                .findByUserIdAndTrainingDateBetween(userId, weekStart, weekEnd);

        int trainingDays = (int) weekRecords.stream()
                .map(TrainingRecord::getTrainingDate).distinct().count();

        int totalActions = weekRecords.size();

        BigDecimal totalVolume = weekRecords.stream()
                .map(TrainingRecord::getVolume)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 平均 RPE（仅统计有 RPE 的记录）
        BigDecimal avgRpe = null;
        double[] rpeValues = weekRecords.stream()
                .map(TrainingRecord::getRpe)
                .filter(Objects::nonNull)
                .mapToDouble(Integer::doubleValue)
                .toArray();
        if (rpeValues.length > 0) {
            avgRpe = BigDecimal.valueOf(Arrays.stream(rpeValues).average().orElse(0))
                    .setScale(1, RoundingMode.HALF_UP);
        }

        // Top 动作（按容量降序，取前 5）
        // 注意：这里用 ArrayList/LinkedHashMap 而非 List.of/toList() 的不可变集合 ——
        // 缓存序列化会写入 @class，JDK 不可变集合无法反序列化，会导致缓存读取失败。
        Map<String, List<TrainingRecord>> byAction = weekRecords.stream()
                .collect(Collectors.groupingBy(TrainingRecord::getActionName));
        List<Map<String, Object>> topActions = byAction.entrySet().stream()
                .map(e -> {
                    List<TrainingRecord> rs = e.getValue();
                    BigDecimal volume = rs.stream()
                            .map(TrainingRecord::getVolume)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("actionName", e.getKey());
                    m.put("totalVolume", volume);
                    m.put("count", rs.size());
                    return m;
                })
                .sorted((a, b) -> ((BigDecimal) b.get("totalVolume"))
                        .compareTo((BigDecimal) a.get("totalVolume")))
                .limit(5)
                .collect(Collectors.toCollection(ArrayList::new));

        // 体重变化：起始体重取本周一当天或之前最近的记录，结束体重取本周内最后一条
        BodyMetric startMetric = bodyMetricRepository
                .findFirstByUserIdAndRecordDateLessThanEqualOrderByRecordDateDesc(userId, weekStart)
                .orElse(null);
        BodyMetric endMetric = bodyMetricRepository
                .findFirstByUserIdAndRecordDateLessThanEqualOrderByRecordDateDesc(userId, weekEnd)
                .orElse(null);

        BigDecimal startWeight = startMetric != null ? startMetric.getWeightKg() : null;
        BigDecimal endWeight = endMetric != null ? endMetric.getWeightKg() : null;
        BigDecimal weightChange = (startWeight != null && endWeight != null)
                ? endWeight.subtract(startWeight) : null;

        Map<String, Object> bodyMetrics = new LinkedHashMap<>();
        bodyMetrics.put("startWeight", startWeight);
        bodyMetrics.put("endWeight", endWeight);
        bodyMetrics.put("weightChange", weightChange);

        // 每日热量均值（仅统计有饮食记录的天，且不超过今天）
        BigDecimal avgCalories = BigDecimal.ZERO;
        int daysWithDiet = 0;
        LocalDate dietEnd = weekEnd.isAfter(today) ? today : weekEnd;
        for (LocalDate d = weekStart; !d.isAfter(dietEnd); d = d.plusDays(1)) {
            BigDecimal dayCalories = dietRecordRepository.sumCaloriesByUserIdAndDate(userId, d);
            if (dayCalories != null && dayCalories.compareTo(BigDecimal.ZERO) > 0) {
                avgCalories = avgCalories.add(dayCalories);
                daysWithDiet++;
            }
        }
        if (daysWithDiet > 0) {
            avgCalories = avgCalories.divide(
                    BigDecimal.valueOf(daysWithDiet), 1, RoundingMode.HALF_UP);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("weekStart", weekStart.toString());
        result.put("weekEnd", weekEnd.toString());
        result.put("trainingDays", trainingDays);
        result.put("totalActions", totalActions);
        result.put("totalVolume", totalVolume);
        result.put("avgRpe", avgRpe);
        result.put("topActions", topActions);
        result.put("bodyMetrics", bodyMetrics);
        // 规范 9.3 未列该字段，但饮食模块已实现每日热量统计，Dashboard 直接展示「本周日均热量」
        // 比让前端再拼一次区间统计更省事；前端可忽略该字段，不影响契约兼容。
        result.put("avgCalories", avgCalories);
        return result;
    }
}

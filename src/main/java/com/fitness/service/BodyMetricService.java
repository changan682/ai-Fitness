package com.fitness.service;

import com.fitness.cache.CacheKeys;
import com.fitness.cache.RedisCacheService;
import com.fitness.dto.BodyMetricRequest;
import com.fitness.dto.BodyMetricResponse;
import com.fitness.dto.BodyMetricTrendResponse;
import com.fitness.dto.BodyMetricUpdateRequest;
import com.fitness.entity.BodyMetric;
import com.fitness.exception.BusinessException;
import com.fitness.exception.ErrorCode;
import com.fitness.repository.BodyMetricRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 身体数据服务 — CRUD + 7日滑动平均 + Redis 缓存（规范第五章 2.4 / 2.5）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BodyMetricService {

    /** 7 日滑动窗口长度（含当天） */
    private static final int AVG_WINDOW_DAYS = 7;

    /**
     * 计算滑动平均所需的最少样本数
     * <p>
     * 规范第 9 条：窗口内记录数 &lt; 3 时返回 null。
     * 只用 1-2 天的数据算「7 日均值」，结果完全被个别波动主导，展示出来会误导用户；
     * 且前端图表对 null 不绘制（折线断开）比画出一个假均值更诚实。
     */
    private static final int MIN_SAMPLES_FOR_AVG = 3;

    /** metric:latest Hash 的字段名（规范 2.4 指定六个字段，另加 id/user_id/created_at 以完整还原响应） */
    private static final String F_ID = "id";
    private static final String F_USER_ID = "user_id";
    private static final String F_RECORD_DATE = "record_date";
    private static final String F_WEIGHT = "weight_kg";
    private static final String F_WAIST = "waist_cm";
    private static final String F_ARM = "arm_cm";
    private static final String F_LEG = "leg_cm";
    private static final String F_BODY_FAT = "body_fat_pct";
    private static final String F_CREATED_AT = "created_at";

    private static final DateTimeFormatter DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final BodyMetricRepository bodyMetricRepository;
    private final RedisCacheService redisCacheService;

    // ==================== 录入 ====================

    /** 录入身体数据 — 当天已有记录时返回 3001（规范要求引导使用修改接口） */
    @Transactional
    public BodyMetricResponse addMetric(Long userId, BodyMetricRequest req) {
        LocalDate recordDate = req.getRecordDate() != null && !req.getRecordDate().isBlank()
                ? LocalDate.parse(req.getRecordDate()) : LocalDate.now();

        // 同一天已有记录则报错（DB 层 uk_user_date 唯一索引作为最终兜底）
        if (bodyMetricRepository.findByUserIdAndRecordDate(userId, recordDate).isPresent()) {
            throw new BusinessException(ErrorCode.BODY_METRIC_DUPLICATE);
        }

        BodyMetric metric = BodyMetric.builder()
                .userId(userId)
                .recordDate(recordDate)
                .weightKg(req.getWeightKg())
                .waistCm(req.getWaistCm())
                .armCm(req.getArmCm())
                .legCm(req.getLegCm())
                .bodyFatPct(req.getBodyFatPct())
                .build();

        // 上面的预检能挡住绝大多数重复，但它有竞态窗口：两个并发请求可能同时查到「没有」，
        // 于是都去 INSERT。uk_user_date 唯一索引是并发下的最终兜底 —— 这里必须显式捕获并
        // 翻译成 3001，否则异常会冒到全局处理器变成 9999「系统内部错误」，
        // 注释里承诺的「DB 唯一索引兜底」等于没有实现。
        // 用 saveAndFlush 而不是 save：让 INSERT 立刻执行，异常才能在这里被抓住
        //（save 可能把 INSERT 推迟到事务提交，那时已经出了本方法的 try 范围）。
        try {
            metric = bodyMetricRepository.saveAndFlush(metric);
        } catch (DataIntegrityViolationException e) {
            log.warn("并发录入撞 uk_user_date，按「当天已有记录」处理: userId={}, date={}", userId, recordDate);
            throw new BusinessException(ErrorCode.BODY_METRIC_DUPLICATE);
        }

        // 写穿透：录入后立即刷新 metric:latest，并失效 metric:avg7d
        writeLatestCache(userId, metric);
        evictAvg7dCache(userId);

        log.info("身体数据录入: userId={}, date={}, weight={}", userId, recordDate, req.getWeightKg());
        return toResponse(metric);
    }

    // ==================== 修改 ====================

    /**
     * 修改当天身体数据（规范 3.1.1）
     * 只能修改当天的记录，历史记录不允许修改（保证数据真实性）。
     * 更新后失效 Redis 缓存 metric:latest:{userId} + metric:avg7d:{userId}。
     */
    @Transactional
    public BodyMetricResponse updateMetric(Long userId, Long id, BodyMetricUpdateRequest req) {
        BodyMetric metric = bodyMetricRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.BODY_METRIC_NOT_FOUND));

        // 校验归属，避免越权改他人数据
        if (!metric.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.BODY_METRIC_NOT_FOUND);
        }

        // 只能修改当天的记录
        if (!metric.getRecordDate().equals(LocalDate.now())) {
            throw new BusinessException(ErrorCode.BODY_METRIC_NOT_FOUND, "历史身体数据不允许修改");
        }

        // 传了才更新，不传保持原值
        if (req.getWeightKg() != null) metric.setWeightKg(req.getWeightKg());
        if (req.getWaistCm() != null) metric.setWaistCm(req.getWaistCm());
        if (req.getArmCm() != null) metric.setArmCm(req.getArmCm());
        if (req.getLegCm() != null) metric.setLegCm(req.getLegCm());
        if (req.getBodyFatPct() != null) metric.setBodyFatPct(req.getBodyFatPct());

        metric = bodyMetricRepository.save(metric);

        writeLatestCache(userId, metric);
        evictAvg7dCache(userId);

        log.info("身体数据更新: userId={}, id={}", userId, id);
        return toResponse(metric);
    }

    // ==================== 查询 ====================

    /**
     * 查询最新身体数据 — Cache-Aside + 空值标记防穿透
     * <p>
     * 读取顺序：Hash 缓存 → 命中直接返回；命中空值标记则确认无数据返回 null；
     * 未命中回源 MySQL，有数据则回写缓存，无数据则写空值标记（TTL 60s）。
     */
    public BodyMetricResponse getLatest(Long userId) {
        String cacheKey = CacheKeys.metricLatest(userId);

        Map<Object, Object> hash = redisCacheService.hGetAll(cacheKey);
        if (hash != null && !hash.isEmpty()) {
            if (redisCacheService.hIsNullMarker(cacheKey)) {
                log.debug("最新体测缓存命中空值标记: userId={}", userId);
                return null;
            }
            log.debug("最新体测缓存命中: userId={}", userId);
            return fromLatestCacheHash(hash);
        }

        BodyMetric metric = bodyMetricRepository.findTopByUserIdOrderByRecordDateDesc(userId).orElse(null);
        if (metric == null) {
            redisCacheService.hSetNullMarker(cacheKey, RedisCacheService.NULL_MARKER_TTL_SECONDS);
            return null;
        }
        writeLatestCache(userId, metric);
        return toResponse(metric);
    }

    /**
     * 查询身体数据趋势（含7日滑动平均）— 规范 3.2
     * <p>
     * 7 日滑动平均按<b>自然日窗口</b> [d-6, d] 计算（而非按记录条数滑窗）：
     * 用户隔天记录时，按条数滑窗会把 12 天跨度的数据平均成「7日均值」，结论会失真。
     * 因此额外向前多查 6 天数据用于窗口计算，但只返回请求区间内的数据点。
     */
    public BodyMetricTrendResponse getTrend(Long userId, LocalDate startDate, LocalDate endDate) {
        if (startDate.isAfter(endDate)) {
            throw new BusinessException(ErrorCode.DATE_RANGE_INVALID);
        }

        LocalDate windowStart = startDate.minusDays(AVG_WINDOW_DAYS - 1L);
        List<BodyMetric> window = bodyMetricRepository
                .findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(userId, windowStart, endDate);

        // 日期 → 体重 索引，供自然日窗口求平均
        Map<LocalDate, BigDecimal> weightByDate = new LinkedHashMap<>();
        for (BodyMetric m : window) {
            if (m.getWeightKg() != null) {
                weightByDate.put(m.getRecordDate(), m.getWeightKg());
            }
        }

        List<BodyMetricTrendResponse.TrendPoint> points = new ArrayList<>();
        for (BodyMetric m : window) {
            if (m.getRecordDate().isBefore(startDate)) {
                continue;   // 窗口辅助数据不返回给前端
            }
            points.add(BodyMetricTrendResponse.TrendPoint.builder()
                    .id(m.getId())
                    .recordDate(m.getRecordDate().toString())
                    .weightKg(m.getWeightKg())
                    .waistCm(m.getWaistCm())
                    .armCm(m.getArmCm())
                    .legCm(m.getLegCm())
                    .bodyFatPct(m.getBodyFatPct())
                    .weightAvg7d(averageOverWindow(weightByDate, m.getRecordDate()))
                    .build());
        }

        // 最新体重 = 区间内最后一条有体重的记录
        BigDecimal latestWeight = null;
        for (int i = points.size() - 1; i >= 0; i--) {
            if (points.get(i).getWeightKg() != null) {
                latestWeight = points.get(i).getWeightKg();
                break;
            }
        }

        // 体重变化 = 最新体重 - 7天前的体重（基准取「不晚于 latest-7天」的最近一条）
        BigDecimal weightChange = null;
        if (latestWeight != null && !points.isEmpty()) {
            LocalDate latestDate = LocalDate.parse(points.get(points.size() - 1).getRecordDate());
            BodyMetric baseline = bodyMetricRepository
                    .findFirstByUserIdAndRecordDateLessThanEqualOrderByRecordDateDesc(
                            userId, latestDate.minusDays(AVG_WINDOW_DAYS))
                    .orElse(null);
            if (baseline != null && baseline.getWeightKg() != null) {
                weightChange = latestWeight.subtract(baseline.getWeightKg());
            }
        }

        // 计算完顺带刷新「最新 7 日均值」缓存（规范 2.5）
        if (!points.isEmpty()) {
            BodyMetricTrendResponse.TrendPoint last = points.get(points.size() - 1);
            if (last.getWeightAvg7d() != null) {
                redisCacheService.setWithJitter(CacheKeys.metricAvg7d(userId),
                        last.getWeightAvg7d(), CacheKeys.METRIC_AVG7D_TTL_SECONDS);
            }
        }

        log.debug("身体数据趋势查询: userId={}, range=[{},{}], 记录数={}",
                userId, startDate, endDate, points.size());

        return BodyMetricTrendResponse.builder()
                .list(points)
                .latestWeight(latestWeight)
                .weightChange(weightChange)
                .totalRecords(points.size())
                .build();
    }

    /**
     * 查询最新 7 日滑动平均体重 — Cache-Aside（规范 2.5，TTL 1小时 + ±300s 扰动）
     * <p>
     * 供 Dashboard 体重卡片使用；缓存未命中时按「最新记录日」的窗口现算并回写。
     * <p>
     * 与 {@link #averageOverWindow} 同一口径：缺失日期不补 0、分母为实际记录数、
     * 样本数 &lt; {@value #MIN_SAMPLES_FOR_AVG} 一律返回 null。
     * <b>null 结果不写缓存</b>（规范 2.5）：缓存 null 需要额外约定哨兵值，
     * 而这种情况本身只发生在数据很少的用户身上，直接下次重算成本可接受。
     */
    public BigDecimal getLatestAvg7d(Long userId) {
        String cacheKey = CacheKeys.metricAvg7d(userId);

        BigDecimal cached = redisCacheService.get(cacheKey);
        if (cached != null && !redisCacheService.isNullMarker(cached)) {
            return cached;
        }

        BodyMetric latest = bodyMetricRepository.findTopByUserIdOrderByRecordDateDesc(userId).orElse(null);
        if (latest == null) {
            // 一条体测记录都没有 → 结果为 null，且不写缓存
            return null;
        }

        LocalDate latestDate = latest.getRecordDate();
        List<BodyMetric> window = bodyMetricRepository
                .findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(
                        userId, latestDate.minusDays(AVG_WINDOW_DAYS - 1L), latestDate);

        BigDecimal sum = BigDecimal.ZERO;
        int count = 0;
        for (BodyMetric m : window) {
            if (m.getWeightKg() != null) {
                sum = sum.add(m.getWeightKg());
                count++;
            }
        }
        // 样本不足 3 天：均值无统计意义，返回 null 且不写缓存
        if (count < MIN_SAMPLES_FOR_AVG) {
            log.debug("7日滑动均值样本不足: userId={}, 窗口内记录数={}", userId, count);
            return null;
        }

        BigDecimal avg = sum.divide(BigDecimal.valueOf(count), 1, RoundingMode.HALF_UP);
        redisCacheService.setWithJitter(cacheKey, avg, CacheKeys.METRIC_AVG7D_TTL_SECONDS);
        return avg;
    }

    // ==================== 滑动平均工具 ====================

    /**
     * 自然日窗口 [date-6, date] 内的平均体重
     * <p>
     * 口径（规范第 9 条，论文需写明）：<b>缺失日期不补 0</b>，分母是窗口内的<b>实际记录条数</b>，
     * 即 {@code avg7d = sum(weight_kg) / count(记录数)}。
     * 这样「7 天里只记了 3 天」得到的是 3 天的均值（70.0/70.5/71.0 → 70.5），
     * 而不是把缺的 4 天当 0 算成 30.2。
     * <p>
     * 窗口内没有记录（count=0）或样本不足（count &lt; {@value #MIN_SAMPLES_FOR_AVG}）时返回 null，
     * 而不是 0 或沿用上一天的值，避免前端把「无数据」画成体重骤降。
     */
    private BigDecimal averageOverWindow(Map<LocalDate, BigDecimal> weightByDate, LocalDate date) {
        BigDecimal sum = BigDecimal.ZERO;
        int count = 0;
        for (int offset = 0; offset < AVG_WINDOW_DAYS; offset++) {
            BigDecimal weight = weightByDate.get(date.minusDays(offset));
            if (weight != null) {
                sum = sum.add(weight);
                count++;
            }
        }
        if (count < MIN_SAMPLES_FOR_AVG) {
            return null;
        }
        return sum.divide(BigDecimal.valueOf(count), 1, RoundingMode.HALF_UP);
    }

    // ==================== metric:latest 缓存（写穿透） ====================

    /** 写穿透：录入/修改后同步刷新 metric:latest Hash */
    private void writeLatestCache(Long userId, BodyMetric metric) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(F_ID, String.valueOf(metric.getId()));
        fields.put(F_USER_ID, String.valueOf(metric.getUserId()));
        fields.put(F_RECORD_DATE, metric.getRecordDate().toString());
        putIfNotNull(fields, F_WEIGHT, metric.getWeightKg());
        putIfNotNull(fields, F_WAIST, metric.getWaistCm());
        putIfNotNull(fields, F_ARM, metric.getArmCm());
        putIfNotNull(fields, F_LEG, metric.getLegCm());
        putIfNotNull(fields, F_BODY_FAT, metric.getBodyFatPct());
        if (metric.getCreatedAt() != null) {
            fields.put(F_CREATED_AT, metric.getCreatedAt().format(DATE_TIME_FORMAT));
        }

        String cacheKey = CacheKeys.metricLatest(userId);
        redisCacheService.hSetAll(cacheKey, fields);
        // TTL 语义见 CacheKeys.METRIC_LATEST_TTL_SECONDS 的说明（规范 2.4 与强约束第8条的折中）
        redisCacheService.expireWithJitter(cacheKey, CacheKeys.METRIC_LATEST_TTL_SECONDS);
    }

    private void putIfNotNull(Map<String, String> fields, String field, BigDecimal value) {
        if (value != null) {
            fields.put(field, value.toPlainString());
        }
    }

    /** 从 metric:latest Hash 还原响应对象 */
    private BodyMetricResponse fromLatestCacheHash(Map<Object, Object> hash) {
        return BodyMetricResponse.builder()
                .id(parseLong(hash.get(F_ID)))
                .userId(parseLong(hash.get(F_USER_ID)))
                .recordDate(parseDate(hash.get(F_RECORD_DATE)))
                .weightKg(parseDecimal(hash.get(F_WEIGHT)))
                .waistCm(parseDecimal(hash.get(F_WAIST)))
                .armCm(parseDecimal(hash.get(F_ARM)))
                .legCm(parseDecimal(hash.get(F_LEG)))
                .bodyFatPct(parseDecimal(hash.get(F_BODY_FAT)))
                .createdAt(parseDateTime(hash.get(F_CREATED_AT)))
                .build();
    }

    /** 失效 7 日滑动均值缓存（录入/修改后调用，下次查询重新计算） */
    private void evictAvg7dCache(Long userId) {
        redisCacheService.delete(CacheKeys.metricAvg7d(userId));
    }

    // ==================== 解析工具 ====================

    private LocalDate parseDate(Object value) {
        try {
            return value == null ? null : LocalDate.parse(value.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private Long parseLong(Object value) {
        try {
            return value == null ? null : Long.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal parseDecimal(Object value) {
        try {
            return value == null ? null : new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private LocalDateTime parseDateTime(Object value) {
        try {
            return value == null ? null : LocalDateTime.parse(value.toString(), DATE_TIME_FORMAT);
        } catch (Exception e) {
            return null;
        }
    }

    private BodyMetricResponse toResponse(BodyMetric m) {
        return BodyMetricResponse.builder()
                .id(m.getId()).userId(m.getUserId()).recordDate(m.getRecordDate())
                .weightKg(m.getWeightKg()).waistCm(m.getWaistCm())
                .armCm(m.getArmCm()).legCm(m.getLegCm())
                .bodyFatPct(m.getBodyFatPct()).createdAt(m.getCreatedAt())
                .build();
    }
}

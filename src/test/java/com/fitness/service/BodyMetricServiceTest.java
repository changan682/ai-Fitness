package com.fitness.service;

import com.fitness.cache.RedisCacheService;
import com.fitness.dto.BodyMetricRequest;
import com.fitness.dto.BodyMetricResponse;
import com.fitness.dto.BodyMetricTrendResponse;
import com.fitness.dto.BodyMetricUpdateRequest;
import com.fitness.entity.BodyMetric;
import com.fitness.exception.BusinessException;
import com.fitness.exception.ErrorCode;
import com.fitness.repository.BodyMetricRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * 身体数据服务单元测试（提示词测试策略：BodyMetricService 滑动平均算法）
 * <p>
 * 纯 Mockito 单元测试，不启动 Spring 上下文，不依赖真实 Redis/MySQL。
 */
@ExtendWith(MockitoExtension.class)
class BodyMetricServiceTest {

    @Mock
    private BodyMetricRepository repository;

    @Mock
    private RedisCacheService redisCacheService;

    @InjectMocks
    private BodyMetricService service;

    // ==================== 新增 ====================

    @Test
    void addMetricShouldRejectDuplicateDate() {
        when(repository.findByUserIdAndRecordDate(anyLong(), any()))
                .thenReturn(Optional.of(new BodyMetric()));

        BodyMetricRequest req = new BodyMetricRequest();
        req.setWeightKg(new BigDecimal("70.0"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.addMetric(1001L, req));
        assertEquals(ErrorCode.BODY_METRIC_DUPLICATE.getCode(), ex.getCode(),
                "当天已有记录应返回 3001");
    }

    // ==================== 7日滑动平均：自然日窗口 + 最少样本数 ====================

    @Test
    @DisplayName("7日滑动平均：缺失日期不补 0、分母是实际记录数；样本 <3 条时返回 null")
    void getTrendShouldCalculate7DayAverage() {
        List<BodyMetric> metrics = List.of(
                metric(1L, "2026-07-01", "70.0"),
                metric(2L, "2026-07-02", "71.0"),
                metric(3L, "2026-07-03", "72.0"),
                metric(4L, "2026-07-04", "73.0"),
                metric(5L, "2026-07-05", "74.0")
        );
        when(repository.findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(anyLong(), any(), any()))
                .thenReturn(metrics);

        BodyMetricTrendResponse resp = service.getTrend(1001L,
                LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-05"));

        assertEquals(5, resp.getList().size(), "应有 5 个数据点");
        assertEquals(5, resp.getTotalRecords(), "totalRecords 应为区间内记录数");

        // 规范第 9 条：窗口内样本 <3 天 → null（不能拿 1-2 天的数据冒充「7 日均值」）
        assertNull(resp.getList().get(0).getWeightAvg7d(), "07-01 窗口内仅 1 条 → null");
        assertNull(resp.getList().get(1).getWeightAvg7d(), "07-02 窗口内仅 2 条 → null");

        // 样本达到 3 条后开始出值，窗口 [d-6, d]
        assertEquals(0, resp.getList().get(2).getWeightAvg7d().compareTo(new BigDecimal("71.0")),
                "07-03 → (70+71+72)/3 = 71.0");
        // 07-05 → (70+71+72+73+74)/5 = 72.0；分母是 5 条实际记录，而不是补齐成 7 天的 7
        assertEquals(0, resp.getList().get(4).getWeightAvg7d().compareTo(new BigDecimal("72.0")),
                "缺失日期不补 0，分母为实际记录数（5 而不是 7）");

        // 数据点字段与规范 3.2 对齐
        assertEquals(Long.valueOf(1L), resp.getList().get(0).getId());
        assertEquals("2026-07-01", resp.getList().get(0).getRecordDate());
        assertEquals(0, resp.getList().get(4).getWeightKg().compareTo(new BigDecimal("74.0")));
        // 最新体重 = 最后一条记录
        assertEquals(0, resp.getLatestWeight().compareTo(new BigDecimal("74.0")));
    }

    /**
     * 关键回归用例：窗口内只有 3 条记录时，分母必须是 3 而不是 7。
     * <p>
     * 用户 7 天里只记录了 3 天（07-01/03/05）。若「缺失日期补 0」，结果会是
     * (70+72+74)/7 = 30.9 —— 一个把用户吓一跳的假数据。正确结果 = 216/3 = 72.0。
     */
    @Test
    @DisplayName("7天里只记3天：分母是3不是7（不补 0）")
    void getTrendShouldDivideByActualRecordCountNotWindowLength() {
        List<BodyMetric> metrics = List.of(
                metric(1L, "2026-07-01", "70.0"),
                metric(2L, "2026-07-03", "72.0"),
                metric(3L, "2026-07-05", "74.0")
        );
        when(repository.findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(anyLong(), any(), any()))
                .thenReturn(metrics);

        BodyMetricTrendResponse resp = service.getTrend(1001L,
                LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-05"));

        // 07-05 的窗口 [06-29, 07-05] 内正好 3 条 → 达到样本下限，出值
        assertEquals(0, resp.getList().get(2).getWeightAvg7d().compareTo(new BigDecimal("72.0")),
                "(70+72+74)/3 = 72.0；若补 0 会得到 30.9，属于会误导用户的错误结果");
        // 07-03 窗口内只有 2 条 → 仍然 null
        assertNull(resp.getList().get(1).getWeightAvg7d(), "样本 2 条 → null");
    }

    /**
     * 关键回归用例：滑动平均必须按「自然日窗口」而非「记录条数」滑窗。
     * <p>
     * 两组记录相隔 12 天（07-01~03 与 07-13~15）。07-15 的自然日窗口 [07-09, 07-15]
     * 只包含第二组的 3 条 → 81.0；若按「最近 7 条记录」滑窗，会把第一组也平均进来
     * （76.0），那就不是「7 日均值」了。
     */
    @Test
    void getTrendShouldUseNaturalDayWindowNotRecordCount() {
        List<BodyMetric> metrics = List.of(
                metric(1L, "2026-07-01", "70.0"),
                metric(2L, "2026-07-02", "71.0"),
                metric(3L, "2026-07-03", "72.0"),
                metric(4L, "2026-07-13", "80.0"),
                metric(5L, "2026-07-14", "81.0"),
                metric(6L, "2026-07-15", "82.0")
        );
        when(repository.findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(anyLong(), any(), any()))
                .thenReturn(metrics);

        BodyMetricTrendResponse resp = service.getTrend(1001L,
                LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-15"));

        assertEquals(6, resp.getList().size());
        assertEquals(0, resp.getList().get(5).getWeightAvg7d().compareTo(new BigDecimal("81.0")),
                "07-15 只看自己所在窗口的 3 条 → (80+81+82)/3 = 81.0，"
                        + "按记录条数滑窗会错算成 76.0（12 天前的数据被平均进来）");
    }

    // ==================== 最新 7 日均值（Dashboard 卡片） ====================

    @Test
    @DisplayName("getLatestAvg7d：样本 <3 条返回 null，且不写缓存")
    void getLatestAvg7dShouldReturnNullWhenTooFewSamples() {
        when(redisCacheService.get(any())).thenReturn(null);
        when(repository.findTopByUserIdOrderByRecordDateDesc(anyLong()))
                .thenReturn(Optional.of(metric(3L, "2026-07-30", "70.0")));
        when(repository.findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(anyLong(), any(), any()))
                .thenReturn(List.of(
                        metric(1L, "2026-07-29", "70.5"),
                        metric(2L, "2026-07-30", "70.0")));

        assertNull(service.getLatestAvg7d(1001L), "窗口内只有 2 条 → 样本不足，返回 null");
        // 规范 2.5：null 结果不写缓存（避免为 null 额外设计序列化）
        org.mockito.Mockito.verify(redisCacheService, org.mockito.Mockito.never())
                .setWithJitter(any(), any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("getLatestAvg7d：样本 ≥3 条按自然日均值返回并写缓存")
    void getLatestAvg7dShouldComputeAndCacheWhenEnoughSamples() {
        when(redisCacheService.get(any())).thenReturn(null);
        when(repository.findTopByUserIdOrderByRecordDateDesc(anyLong()))
                .thenReturn(Optional.of(metric(3L, "2026-07-30", "71.0")));
        when(repository.findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(anyLong(), any(), any()))
                .thenReturn(List.of(
                        metric(1L, "2026-07-28", "70.0"),
                        metric(2L, "2026-07-29", "70.5"),
                        metric(3L, "2026-07-30", "71.0")));

        BigDecimal avg = service.getLatestAvg7d(1001L);

        // (70.0+70.5+71.0)/3 = 70.5
        assertEquals(0, avg.compareTo(new BigDecimal("70.5")), "分母为 3 条实际记录");
        org.mockito.Mockito.verify(redisCacheService)
                .setWithJitter(any(), any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void getTrendShouldComputeWeightChangeAgainst7DaysAgo() {
        List<BodyMetric> metrics = List.of(
                metric(2L, "2026-07-20", "70.0"),
                metric(3L, "2026-07-30", "69.0")
        );
        when(repository.findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(anyLong(), any(), any()))
                .thenReturn(metrics);
        // 基准：不晚于 (07-30 - 7天) = 07-23 的最近一条 → 07-20 的 70.0
        when(repository.findFirstByUserIdAndRecordDateLessThanEqualOrderByRecordDateDesc(
                anyLong(), any()))
                .thenReturn(Optional.of(metric(2L, "2026-07-20", "70.0")));

        BodyMetricTrendResponse resp = service.getTrend(1001L,
                LocalDate.parse("2026-07-20"), LocalDate.parse("2026-07-30"));

        assertEquals(0, resp.getWeightChange().compareTo(new BigDecimal("-1.0")),
                "7日体重变化 = 69.0 - 70.0 = -1.0");
    }

    @Test
    void getTrendShouldReturnEmptyWhenNoData() {
        when(repository.findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(anyLong(), any(), any()))
                .thenReturn(List.of());

        BodyMetricTrendResponse resp = service.getTrend(1001L,
                LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-05"));

        assertEquals(0, resp.getList().size());
        assertEquals(0, resp.getTotalRecords());
        assertNull(resp.getLatestWeight());
        assertNull(resp.getWeightChange());
    }

    @Test
    void getTrendShouldRejectInvalidDateRange() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.getTrend(1001L,
                        LocalDate.parse("2026-07-05"), LocalDate.parse("2026-07-01")));
        assertEquals(ErrorCode.DATE_RANGE_INVALID.getCode(), ex.getCode());
    }

    // ==================== 修改 ====================

    @Test
    void updateMetricShouldUpdateTodayRecord() {
        BodyMetric metric = BodyMetric.builder()
                .id(1L).userId(1001L).recordDate(LocalDate.now())
                .weightKg(new BigDecimal("70.0")).build();
        when(repository.findById(1L)).thenReturn(Optional.of(metric));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BodyMetricUpdateRequest req = new BodyMetricUpdateRequest();
        req.setWeightKg(new BigDecimal("70.5"));

        BodyMetricResponse resp = service.updateMetric(1001L, 1L, req);

        assertEquals(0, resp.getWeightKg().compareTo(new BigDecimal("70.5")));
    }

    @Test
    void updateMetricShouldRejectHistoricalRecord() {
        BodyMetric metric = BodyMetric.builder()
                .id(1L).userId(1001L).recordDate(LocalDate.now().minusDays(1))
                .weightKg(new BigDecimal("70.0")).build();
        when(repository.findById(1L)).thenReturn(Optional.of(metric));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateMetric(1001L, 1L, new BodyMetricUpdateRequest()));
        assertEquals(ErrorCode.BODY_METRIC_NOT_FOUND.getCode(), ex.getCode(),
                "历史记录不允许修改，应返回 3002");
    }

    @Test
    void updateMetricShouldRejectOtherUsersRecord() {
        BodyMetric metric = BodyMetric.builder()
                .id(1L).userId(999L).recordDate(LocalDate.now())
                .weightKg(new BigDecimal("70.0")).build();
        when(repository.findById(1L)).thenReturn(Optional.of(metric));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateMetric(1001L, 1L, new BodyMetricUpdateRequest()));
        assertEquals(ErrorCode.BODY_METRIC_NOT_FOUND.getCode(), ex.getCode(),
                "不能修改他人记录");
    }

    // ==================== 最新数据：空值标记防穿透 ====================

    @Test
    void getLatestShouldReturnNullAndWriteNullMarkerWhenNoRecord() {
        when(redisCacheService.hGetAll(any())).thenReturn(java.util.Map.of());
        when(repository.findTopByUserIdOrderByRecordDateDesc(anyLong()))
                .thenReturn(Optional.empty());

        assertNull(service.getLatest(1001L), "无记录应返回 null");
        // 关键：必须写入空值标记，否则同一用户的重复请求会持续穿透到 MySQL
        org.mockito.Mockito.verify(redisCacheService)
                .hSetNullMarker(any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void getLatestShouldHitNullMarkerWithoutTouchingDatabase() {
        java.util.Map<Object, Object> marker = new java.util.HashMap<>();
        marker.put("__NULL__", "__NULL__");
        when(redisCacheService.hGetAll(any())).thenReturn(marker);
        when(redisCacheService.hIsNullMarker(any())).thenReturn(true);

        assertNull(service.getLatest(1001L));
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never())
                .findTopByUserIdOrderByRecordDateDesc(anyLong());
    }

    // ==================== 工具方法 ====================

    private BodyMetric metric(Long id, String date, String weight) {
        return BodyMetric.builder()
                .id(id)
                .userId(1001L)
                .recordDate(LocalDate.parse(date))
                .weightKg(new BigDecimal(weight))
                .build();
    }
}

package com.fitness.service;

import com.fitness.cache.CacheKeys;
import com.fitness.cache.RedisCacheService;
import com.fitness.common.PageResult;
import com.fitness.dto.TrainingBatchRequest;
import com.fitness.dto.TrainingRecordRequest;
import com.fitness.dto.TrainingRecordUpdateRequest;
import com.fitness.entity.TrainingRecord;
import com.fitness.exception.BusinessException;
import com.fitness.exception.ErrorCode;
import com.fitness.repository.TrainingRecordRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TrainingRecordService 单元测试（提示词测试策略：Service 核心方法 —— 容量计算 + 部分更新语义）
 * <p>
 * 纯 Mockito，不启动 Spring 上下文。
 * <p>
 * 为什么用 {@link ReflectionTestUtils} 触发 {@code onCreate/onUpdate}：
 * 容量（volume = sets × reps × weightKg）是在实体的 {@code @PrePersist/@PreUpdate} 回调里算的，
 * 单元测试里没有真实 EntityManager，因此让 mock 的 {@code save} 模拟 JPA 生命周期回调，
 * 才能断言「Service 返回的正是落库时会写入的容量」。
 * 实体回调本身的正确性由 {@code entity.TrainingRecordVolumeTest} 覆盖，两者互补。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Service 单元测试：TrainingRecordService（容量计算 / 部分更新 / 缓存维护）")
class TrainingRecordServiceTest {

    private static final Long USER = 1001L;
    private static final Long OTHER_USER = 2002L;

    @Mock
    private TrainingRecordRepository trainingRecordRepository;

    @Mock
    private RedisCacheService redisCacheService;

    @InjectMocks
    private TrainingRecordService service;

    // ==================== 2.1 新增：容量自动计算 ====================

    @Test
    @DisplayName("新增记录：容量 = 组数 × 次数 × 重量，由 @PrePersist 计算并返回")
    void addRecordShouldComputeVolume() {
        // 用 mock 模拟 JPA @PrePersist 回调（见类注释）
        when(trainingRecordRepository.save(any(TrainingRecord.class))).thenAnswer(inv -> {
            TrainingRecord record = inv.getArgument(0);
            ReflectionTestUtils.invokeMethod(record, "onCreate");
            return record;
        });
        when(trainingRecordRepository.findByUserIdAndTrainingDate(anyLong(), any()))
                .thenReturn(List.of());

        TrainingRecordRequest req = new TrainingRecordRequest();
        req.setActionName("杠铃卧推");
        req.setSets(4);
        req.setReps(10);
        req.setWeightKg(new BigDecimal("60.0"));

        TrainingRecord saved = service.addRecord(USER, req);

        assertEquals(0, saved.getVolume().compareTo(new BigDecimal("2400.0")),
                "4 组 × 10 次 × 60kg = 2400.0");
        assertEquals(USER, saved.getUserId(), "userId 必须来自鉴权上下文，不能由请求体指定");
        assertEquals(LocalDate.now(), saved.getTrainingDate(), "未传日期时默认当天");
    }

    @Test
    @DisplayName("新增当天的记录：重建「今日训练」缓存（delete 后 rPushAll，避免追加导致重复）")
    void addRecordShouldRebuildTodayCacheForToday() {
        when(trainingRecordRepository.save(any(TrainingRecord.class))).thenAnswer(inv -> {
            TrainingRecord record = inv.getArgument(0);
            ReflectionTestUtils.invokeMethod(record, "onCreate");
            return record;
        });
        LocalDate today = LocalDate.now();
        TrainingRecord persisted = record(1L, USER, today.toString(), "深蹲", 5, 5, "100.0");
        when(trainingRecordRepository.findByUserIdAndTrainingDate(USER, today))
                .thenReturn(List.of(persisted));

        service.addRecord(USER, request(today.toString(), "深蹲", 5, 5, "100.0"));

        String todayKey = CacheKeys.trainingToday(USER, today);
        // 先 delete 再写入：增量追加会让「修改/删除」后的缓存出现脏数据
        verify(redisCacheService).delete(todayKey);
        verify(redisCacheService).rPushAll(eq(todayKey), anyCollection());
        // 本周统计缓存必须失效，否则 2.10 的周统计会返回旧数据
        verify(redisCacheService).delete(CacheKeys.statsWeekly(USER, mondayOf(today)));
    }

    @Test
    @DisplayName("补录历史日期的记录：不写「今日训练」缓存")
    void addRecordForHistoryDateShouldNotTouchTodayCache() {
        when(trainingRecordRepository.save(any(TrainingRecord.class))).thenAnswer(inv -> {
            TrainingRecord record = inv.getArgument(0);
            ReflectionTestUtils.invokeMethod(record, "onCreate");
            return record;
        });
        LocalDate history = LocalDate.now().minusDays(3);

        service.addRecord(USER, request(history.toString(), "深蹲", 5, 5, "100.0"));

        verify(redisCacheService, never()).rPushAll(anyString(), anyCollection());
        verify(redisCacheService, never()).delete(CacheKeys.trainingToday(USER, LocalDate.now()));
        // 但该日期所在周（可能是上一周）的统计缓存仍要失效
        verify(redisCacheService).delete(CacheKeys.statsWeekly(USER, mondayOf(history)));
    }

    // ==================== 2.2 批量新增 ====================

    @Test
    @DisplayName("批量新增：返回条数与总容量（前端直接展示「成功录入N条」）")
    void batchAddShouldAggregateCountAndTotalVolume() {
        when(trainingRecordRepository.saveAll(any())).thenAnswer(inv -> {
            List<TrainingRecord> records = inv.getArgument(0);
            records.forEach(r -> ReflectionTestUtils.invokeMethod(r, "onCreate"));
            return records;
        });
        when(trainingRecordRepository.findByUserIdAndTrainingDate(anyLong(), any()))
                .thenReturn(List.of());

        TrainingBatchRequest req = new TrainingBatchRequest();
        req.setDurationMin(60);
        req.setRecords(List.of(item("杠铃卧推", 4, 10, "60.0"), item("上斜卧推", 3, 12, "50.0")));

        Map<String, Object> result = service.batchAddRecords(USER, req);

        // 注意：用 ((Number) x).intValue() 而非 assertEquals(2, Object) —— 后者在 JUnit 5 里会因
        // (int,int) 与 (Object,Object) 重载同时可用而编译歧义
        assertEquals(2, ((Number) result.get("count")).intValue());
        assertEquals(0, ((BigDecimal) result.get("totalVolume")).compareTo(new BigDecimal("4200.0")),
                "总容量 = 4×10×60(=2400) + 3×12×50(=1800) = 4200.0");
    }

    // ==================== 2.3 查询：分页参数钳制 ====================

    @Test
    @DisplayName("分页查询：page<1 钳制为 1、size>200 钳制为 200（防止拖垮数据库）")
    void queryByDateRangeShouldClampPaging() {
        when(trainingRecordRepository.findByUserIdAndTrainingDateBetween(
                eq(USER), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 200), 0));

        PageResult<TrainingRecord> result = service.queryByDateRange(
                USER, LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-31"), 0, 9999);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(trainingRecordRepository).findByUserIdAndTrainingDateBetween(
                eq(USER), any(), any(), captor.capture());
        assertEquals(0, captor.getValue().getPageNumber(), "page=0 应钳制为第一页（PageRequest 内部从 0 开始）");
        assertEquals(200, captor.getValue().getPageSize(), "size=9999 应被钳制为最大 200");
        // 返回体里的 page 仍是「从1开始」的对外约定
        assertEquals(1, result.getPage());
        assertEquals(200, result.getSize());
    }

    @Test
    @DisplayName("分页查询：起止日期倒置必须抛 2002，且不查库")
    void queryByDateRangeShouldRejectInvertedRange() {
        BusinessException ex = assertThrows(BusinessException.class, () -> service.queryByDateRange(
                USER, LocalDate.parse("2026-07-31"), LocalDate.parse("2026-07-01"), 1, 50));

        assertEquals(ErrorCode.DATE_RANGE_INVALID.getCode(), ex.getCode(), "应为 2002 日期范围无效");
        verify(trainingRecordRepository, never())
                .findByUserIdAndTrainingDateBetween(anyLong(), any(), any(), any(Pageable.class));
    }

    @Test
    @DisplayName("按动作查询：附带历史最大重量/容量，无记录时回退为 0 而不是 null")
    void queryByActionShouldReturnHistoricalMax() {
        TrainingRecord r = record(1L, USER, "2026-07-01", "杠铃卧推", 4, 10, "60.0");
        when(trainingRecordRepository.findByUserIdAndActionNameContaining(
                eq(USER), eq("杠铃卧推"), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(r)));
        when(trainingRecordRepository.findMaxWeightByUserIdAndActionName(USER, "杠铃卧推"))
                .thenReturn(Optional.of(new BigDecimal("67.5")));
        when(trainingRecordRepository.findMaxVolumeByUserIdAndActionName(USER, "杠铃卧推"))
                .thenReturn(Optional.of(new BigDecimal("2475.0")));

        Map<String, Object> result = service.queryByAction(USER, "杠铃卧推",
                LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-31"), 1, 20);

        assertEquals(1, ((List<?>) result.get("list")).size());
        assertEquals(1L, ((Number) result.get("total")).longValue());
        assertEquals(0, ((BigDecimal) result.get("maxWeight")).compareTo(new BigDecimal("67.5")));
        assertEquals(0, ((BigDecimal) result.get("maxVolume")).compareTo(new BigDecimal("2475.0")));
    }

    @Test
    @DisplayName("按动作查询：无历史记录时 maxWeight/maxVolume 返回 0（避免前端 NPE）")
    void queryByActionShouldFallbackToZeroWhenNoHistory() {
        when(trainingRecordRepository.findByUserIdAndActionNameContaining(
                eq(USER), anyString(), any(), any(), any(Pageable.class)))
                .thenReturn(Page.empty());
        when(trainingRecordRepository.findMaxWeightByUserIdAndActionName(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(trainingRecordRepository.findMaxVolumeByUserIdAndActionName(anyLong(), anyString()))
                .thenReturn(Optional.empty());

        Map<String, Object> result = service.queryByAction(USER, "新动作",
                LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-31"), 1, 20);

        assertEquals(0, ((BigDecimal) result.get("maxWeight")).compareTo(BigDecimal.ZERO));
        assertEquals(0, ((BigDecimal) result.get("maxVolume")).compareTo(BigDecimal.ZERO));
    }

    // ==================== 2.5 修改：部分更新 + 容量重算 ====================

    @Test
    @DisplayName("修改记录：只传 sets 时其余字段保持不变，容量按新组数重算")
    void updateRecordShouldOnlyApplyProvidedFields() {
        TrainingRecord existing = record(7L, USER, LocalDate.now().toString(), "杠铃卧推", 4, 10, "60.0");
        existing.setVolume(new BigDecimal("2400.0"));
        when(trainingRecordRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(trainingRecordRepository.save(any(TrainingRecord.class))).thenAnswer(inv -> {
            TrainingRecord record = inv.getArgument(0);
            ReflectionTestUtils.invokeMethod(record, "onUpdate");   // 模拟 @PreUpdate 重算容量
            return record;
        });
        when(trainingRecordRepository.findByUserIdAndTrainingDate(anyLong(), any()))
                .thenReturn(List.of(existing));

        TrainingRecordUpdateRequest req = new TrainingRecordUpdateRequest();
        req.setSets(5);

        Map<String, Object> result = service.updateRecord(USER, 7L, req);

        assertEquals(7L, ((Number) result.get("id")).longValue());
        assertEquals(5, existing.getSets().intValue(), "sets 应更新为 5");
        assertEquals(10, existing.getReps().intValue(), "未传的 reps 必须保持原值 10");
        assertEquals(0, existing.getWeightKg().compareTo(new BigDecimal("60.0")),
                "未传的 weightKg 必须保持原值 60.0");
        assertEquals(0, ((BigDecimal) result.get("volume")).compareTo(new BigDecimal("3000.0")),
                "新容量 = 5 组 × 10 次 × 60kg = 3000.0");
    }

    @Test
    @DisplayName("修改记录：记录不存在 → 2001")
    void updateRecordShouldThrowWhenRecordMissing() {
        when(trainingRecordRepository.findById(999L)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateRecord(USER, 999L, new TrainingRecordUpdateRequest()));

        assertEquals(ErrorCode.TRAINING_RECORD_NOT_FOUND.getCode(), ex.getCode());
        verify(trainingRecordRepository, never()).save(any(TrainingRecord.class));
    }

    @Test
    @DisplayName("修改记录：不能改他人记录（越权返回 2001，不暴露「记录存在」）")
    void updateRecordShouldRejectOtherUsersRecord() {
        TrainingRecord others = record(8L, OTHER_USER, LocalDate.now().toString(), "深蹲", 5, 5, "100.0");
        when(trainingRecordRepository.findById(8L)).thenReturn(Optional.of(others));

        TrainingRecordUpdateRequest req = new TrainingRecordUpdateRequest();
        req.setSets(1);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateRecord(USER, 8L, req));

        assertEquals(ErrorCode.TRAINING_RECORD_NOT_FOUND.getCode(), ex.getCode());
        assertEquals(5, others.getSets().intValue(), "越权请求不得修改任何字段");
        verify(trainingRecordRepository, never()).save(any(TrainingRecord.class));
    }

    // ==================== 2.6 删除 ====================

    @Test
    @DisplayName("删除记录：本人记录删除成功并重建今日缓存")
    void deleteRecordShouldDeleteOwnedRecord() {
        TrainingRecord mine = record(9L, USER, LocalDate.now().toString(), "硬拉", 3, 8, "100.0");
        when(trainingRecordRepository.findById(9L)).thenReturn(Optional.of(mine));
        when(trainingRecordRepository.findByUserIdAndTrainingDate(anyLong(), any()))
                .thenReturn(List.of());

        service.deleteRecord(USER, 9L);

        verify(trainingRecordRepository).delete(mine);
        verify(redisCacheService).delete(CacheKeys.trainingToday(USER, LocalDate.now()));
    }

    @Test
    @DisplayName("删除记录：不能删他人记录（越权 → 2001 且不执行删除）")
    void deleteRecordShouldRejectOtherUsersRecord() {
        TrainingRecord others = record(10L, OTHER_USER, LocalDate.now().toString(), "硬拉", 3, 8, "100.0");
        when(trainingRecordRepository.findById(10L)).thenReturn(Optional.of(others));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.deleteRecord(USER, 10L));

        assertEquals(ErrorCode.TRAINING_RECORD_NOT_FOUND.getCode(), ex.getCode());
        verify(trainingRecordRepository, never()).delete(any(TrainingRecord.class));
    }

    // ==================== 今日训练：缓存命中/回源 ====================

    @Test
    @DisplayName("今日训练：缓存命中时不再查库")
    void getTodayRecordsShouldHitCache() {
        LocalDate today = LocalDate.now();
        TrainingRecord cached = record(1L, USER, today.toString(), "卧推", 4, 10, "60.0");
        when(redisCacheService.lRange(CacheKeys.trainingToday(USER, today), 0, -1))
                .thenReturn(List.of(cached));

        List<TrainingRecord> result = service.getTodayRecords(USER);

        assertEquals(1, result.size());
        assertEquals("卧推", result.get(0).getActionName());
        verify(trainingRecordRepository, never()).findByUserIdAndTrainingDate(anyLong(), any());
    }

    @Test
    @DisplayName("今日训练：缓存未命中时回源 MySQL")
    void getTodayRecordsShouldFallbackToDatabase() {
        LocalDate today = LocalDate.now();
        when(redisCacheService.lRange(CacheKeys.trainingToday(USER, today), 0, -1)).thenReturn(List.of());
        when(trainingRecordRepository.findByUserIdAndTrainingDate(USER, today))
                .thenReturn(List.of(record(2L, USER, today.toString(), "深蹲", 5, 5, "100.0")));

        List<TrainingRecord> result = service.getTodayRecords(USER);

        assertEquals(1, result.size());
        assertEquals("深蹲", result.get(0).getActionName());
    }

    @Test
    @DisplayName("今日训练：缓存里全是脏类型时回源，而不是返回空列表")
    void getTodayRecordsShouldIgnoreCacheWithUnexpectedType() {
        LocalDate today = LocalDate.now();
        when(redisCacheService.lRange(CacheKeys.trainingToday(USER, today), 0, -1))
                .thenReturn(List.of("unexpected-string"));
        when(trainingRecordRepository.findByUserIdAndTrainingDate(USER, today))
                .thenReturn(List.of(record(3L, USER, today.toString(), "引体向上", 4, 8, "0.0")));

        List<TrainingRecord> result = service.getTodayRecords(USER);

        assertEquals(1, result.size(), "脏缓存不能被当成「今天没有训练」，否则 Dashboard 会空一块");
        assertEquals("引体向上", result.get(0).getActionName());
    }

    // ==================== 工具方法 ====================

    private TrainingRecordRequest request(String date, String action, int sets, int reps, String weight) {
        TrainingRecordRequest req = new TrainingRecordRequest();
        req.setTrainingDate(date);
        req.setActionName(action);
        req.setSets(sets);
        req.setReps(reps);
        req.setWeightKg(new BigDecimal(weight));
        return req;
    }

    private TrainingBatchRequest.TrainingItem item(String action, int sets, int reps, String weight) {
        TrainingBatchRequest.TrainingItem item = new TrainingBatchRequest.TrainingItem();
        item.setActionName(action);
        item.setSets(sets);
        item.setReps(reps);
        item.setWeightKg(new BigDecimal(weight));
        return item;
    }

    private TrainingRecord record(Long id, Long userId, String date, String action,
                                  int sets, int reps, String weight) {
        TrainingRecord record = TrainingRecord.builder()
                .id(id).userId(userId)
                .trainingDate(LocalDate.parse(date))
                .actionName(action).sets(sets).reps(reps)
                .weightKg(new BigDecimal(weight))
                .build();
        record.setVolume(new BigDecimal(weight).multiply(BigDecimal.valueOf(sets))
                .multiply(BigDecimal.valueOf(reps)));
        return record;
    }

    private LocalDate mondayOf(LocalDate date) {
        return date.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
    }
}

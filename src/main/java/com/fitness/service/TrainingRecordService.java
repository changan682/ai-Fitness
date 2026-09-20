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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 训练记录服务 — 核心业务：CRUD + 批量录入 + 容量自动计算 + 今日训练缓存
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrainingRecordService {

    /** 单页最大条数 — 防止前端传超大 size 拖垮数据库 */
    private static final int MAX_PAGE_SIZE = 200;

    private final TrainingRecordRepository trainingRecordRepository;
    private final RedisCacheService redisCacheService;
    private final AiSummaryService aiSummaryService;

    // ==================== 新增 ====================

    /**
     * 新增单条训练记录
     * 容量 = sets × reps × weightKg，由 Entity @PrePersist 自动计算
     */
    @Transactional
    public TrainingRecord addRecord(Long userId, TrainingRecordRequest req) {
        TrainingRecord record = TrainingRecord.builder()
                .userId(userId)
                .trainingDate(req.getTrainingDate() != null && !req.getTrainingDate().isBlank()
                        ? LocalDate.parse(req.getTrainingDate()) : LocalDate.now())
                .actionName(req.getActionName())
                .sets(req.getSets())
                .reps(req.getReps())
                .weightKg(req.getWeightKg())
                .durationMin(req.getDurationMin())
                .rpe(req.getRpe())
                .remark(req.getRemark())
                .build();

        record = trainingRecordRepository.save(record);
        // 主动写入今日训练缓存（提示词 2.3）
        refreshTodayCache(userId, record.getTrainingDate());
        // 本周统计缓存失效，下次查询重新计算（提示词 2.10）
        invalidateDerivedCaches(userId, record.getTrainingDate());

        log.info("新增训练记录: userId={}, action={}, volume={}",
                userId, record.getActionName(), record.getVolume());
        return record;
    }

    // ==================== 批量新增 ====================

    /**
     * 批量新增训练记录
     * 返回：录入条数 + 总容量 + 完整记录列表
     */
    @Transactional
    public Map<String, Object> batchAddRecords(Long userId, TrainingBatchRequest req) {
        LocalDate trainingDate = req.getTrainingDate() != null && !req.getTrainingDate().isBlank()
                ? LocalDate.parse(req.getTrainingDate()) : LocalDate.now();

        List<TrainingRecord> records = new ArrayList<>();
        for (TrainingBatchRequest.TrainingItem item : req.getRecords()) {
            records.add(TrainingRecord.builder()
                    .userId(userId)
                    .trainingDate(trainingDate)
                    .actionName(item.getActionName())
                    .sets(item.getSets())
                    .reps(item.getReps())
                    .weightKg(item.getWeightKg())
                    .rpe(item.getRpe())
                    .remark(item.getRemark())
                    .durationMin(req.getDurationMin())      // 应用全局训练时长
                    .build());
        }

        List<TrainingRecord> savedRecords = trainingRecordRepository.saveAll(records);

        refreshTodayCache(userId, trainingDate);
        invalidateDerivedCaches(userId, trainingDate);

        BigDecimal totalVolume = savedRecords.stream()
                .map(TrainingRecord::getVolume)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> result = new HashMap<>();
        result.put("count", savedRecords.size());
        result.put("totalVolume", totalVolume);
        result.put("records", savedRecords);

        log.info("批量新增训练记录: userId={}, 共{}条, 总容量={}", userId, savedRecords.size(), totalVolume);
        return result;
    }

    // ==================== 查询 ====================

    /** 按日期范围查询（分页） */
    public PageResult<TrainingRecord> queryByDateRange(Long userId, LocalDate startDate,
                                                       LocalDate endDate, int page, int size) {
        if (startDate.isAfter(endDate)) {
            throw new BusinessException(ErrorCode.DATE_RANGE_INVALID);
        }
        int safePage = normalizePage(page);
        int safeSize = normalizeSize(size);

        Page<TrainingRecord> result = trainingRecordRepository
                .findByUserIdAndTrainingDateBetween(userId, startDate, endDate,
                        PageRequest.of(safePage - 1, safeSize));

        return new PageResult<>(result.getContent(), result.getTotalElements(), safePage, safeSize);
    }

    /** 按动作名称模糊查询 */
    public Map<String, Object> queryByAction(Long userId, String actionName,
                                             LocalDate startDate, LocalDate endDate,
                                             int page, int size) {
        if (startDate.isAfter(endDate)) {
            throw new BusinessException(ErrorCode.DATE_RANGE_INVALID);
        }
        int safePage = normalizePage(page);
        int safeSize = normalizeSize(size);

        Page<TrainingRecord> result = trainingRecordRepository
                .findByUserIdAndActionNameContaining(userId, actionName, startDate, endDate,
                        PageRequest.of(safePage - 1, safeSize));

        BigDecimal maxWeight = trainingRecordRepository
                .findMaxWeightByUserIdAndActionName(userId, actionName).orElse(BigDecimal.ZERO);
        BigDecimal maxVolume = trainingRecordRepository
                .findMaxVolumeByUserIdAndActionName(userId, actionName).orElse(BigDecimal.ZERO);

        Map<String, Object> response = new HashMap<>();
        response.put("list", result.getContent());
        response.put("total", result.getTotalElements());
        response.put("actionName", actionName);
        response.put("maxWeight", maxWeight);
        response.put("maxVolume", maxVolume);
        return response;
    }

    /**
     * 查询当日训练记录 — 优先从 Redis List 读取（Dashboard 高频访问），未命中回源 MySQL
     * <p>
     * 提示词 2.3：新增时主动写入，删除/修改时重建，TTL 到次日凌晨。
     */
    public List<TrainingRecord> getTodayRecords(Long userId) {
        LocalDate today = LocalDate.now();
        String cacheKey = CacheKeys.trainingToday(userId, today);

        List<Object> cached = redisCacheService.lRange(cacheKey, 0, -1);
        if (cached != null && !cached.isEmpty()) {
            List<TrainingRecord> records = cached.stream()
                    .filter(TrainingRecord.class::isInstance)
                    .map(TrainingRecord.class::cast)
                    .toList();
            if (!records.isEmpty()) {
                log.debug("今日训练缓存命中: userId={}, size={}", userId, records.size());
                return records;
            }
        }
        return trainingRecordRepository.findByUserIdAndTrainingDate(userId, today);
    }

    // ==================== 修改 ====================

    /** 修改训练记录 — 传了才更新，修改后自动重新计算容量 */
    @Transactional
    public Map<String, Object> updateRecord(Long userId, Long recordId, TrainingRecordUpdateRequest req) {
        TrainingRecord record = trainingRecordRepository.findById(recordId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TRAINING_RECORD_NOT_FOUND));

        if (!record.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.TRAINING_RECORD_NOT_FOUND);
        }

        if (req.getSets() != null) record.setSets(req.getSets());
        if (req.getReps() != null) record.setReps(req.getReps());
        if (req.getWeightKg() != null) record.setWeightKg(req.getWeightKg());
        if (req.getDurationMin() != null) record.setDurationMin(req.getDurationMin());
        if (req.getRpe() != null) record.setRpe(req.getRpe());
        if (req.getRemark() != null) record.setRemark(req.getRemark());

        // @PreUpdate 会自动重算 volume
        record = trainingRecordRepository.save(record);

        refreshTodayCache(userId, record.getTrainingDate());
        invalidateDerivedCaches(userId, record.getTrainingDate());

        Map<String, Object> response = new HashMap<>();
        response.put("id", record.getId());
        response.put("volume", record.getVolume());
        log.info("训练记录已更新: id={}, newVolume={}", recordId, record.getVolume());
        return response;
    }

    // ==================== 删除 ====================

    /** 删除训练记录 */
    @Transactional
    public void deleteRecord(Long userId, Long recordId) {
        TrainingRecord record = trainingRecordRepository.findById(recordId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TRAINING_RECORD_NOT_FOUND));

        if (!record.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.TRAINING_RECORD_NOT_FOUND);
        }

        LocalDate trainingDate = record.getTrainingDate();
        trainingRecordRepository.delete(record);

        refreshTodayCache(userId, trainingDate);
        invalidateDerivedCaches(userId, trainingDate);
        log.info("训练记录已删除: id={}", recordId);
    }

    // ==================== 缓存维护 ====================

    /**
     * 重建某日的训练记录缓存（新增/批量/修改/删除后调用）
     * <p>
     * 缓存只服务「今日」这个高频场景（Dashboard），补录历史日期时不做无意义的写入。
     * 从 MySQL 重新加载当日全量数据写入 Redis List，TTL 到次日凌晨。
     */
    private void refreshTodayCache(Long userId, LocalDate date) {
        if (date == null || !date.equals(LocalDate.now())) {
            return;
        }
        String cacheKey = CacheKeys.trainingToday(userId, date);

        List<TrainingRecord> records =
                trainingRecordRepository.findByUserIdAndTrainingDate(userId, date);

        // 重建：删除旧 List 后重新写入，避免「追加」导致数据重复
        redisCacheService.delete(cacheKey);
        if (!records.isEmpty()) {
            redisCacheService.rPushAll(cacheKey, records);
            // 这里刻意不加 ±300s 随机扰动：TTL 是「到次日凌晨」的精确语义，
            // 加了扰动会导致跨零点后仍返回前一天的「今日训练」，比缓存雪崩更严重。
            // 雪崩风险由「每个用户一个独立 key + 到期时间天然分散」化解。
            redisCacheService.expire(cacheKey, RedisCacheService.secondsUntilMidnight(),
                    java.util.concurrent.TimeUnit.SECONDS);
        }
        log.debug("今日训练缓存已重建: userId={}, date={}, size={}", userId, date, records.size());
    }

    /**
     * 使「由训练记录派生出来」的缓存失效（周统计 + AI 总结）
     * <p>
     * 把两处规范要求合并在一个入口，避免将来有人只改一处：
     * <ul>
     *   <li>规范 Redis 2.10：有新训练记录时删除本周统计缓存，下次查询重新计算</li>
     *   <li>规范 Redis 2.8：当天有新训练记录录入时主动失效 AI 总结缓存，
     *       允许用户手动触发重新生成</li>
     * </ul>
     * 训练日期可能落在上一周（补录），故周统计缓存同时失效该日期所在周与当前周两个 key。
     */
    private void invalidateDerivedCaches(Long userId, LocalDate trainingDate) {
        if (trainingDate == null) {
            return;
        }

        // --- 周统计缓存 ---
        LocalDate weekStart = mondayOf(trainingDate);
        LocalDate currentWeekStart = mondayOf(LocalDate.now());
        redisCacheService.delete(CacheKeys.statsWeekly(userId, weekStart));
        if (!weekStart.equals(currentWeekStart)) {
            redisCacheService.delete(CacheKeys.statsWeekly(userId, currentWeekStart));
        }

        // --- AI 总结缓存（Redis + DB 两层都清，否则 DB 层会把旧总结捞回来）---
        try {
            aiSummaryService.invalidateSummaryCache(userId, trainingDate);
        } catch (Exception e) {
            // 总结缓存没清掉，最多导致下次多走一次生成，不应让训练记录的写入失败
            log.warn("失效AI总结缓存失败（不影响训练记录写入）: userId={}, date={}, err={}",
                    userId, trainingDate, e.getMessage());
        }
    }

    private LocalDate mondayOf(LocalDate date) {
        return date.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
    }

    /** page 下限钳制为 1，避免 PageRequest.of(-1,…) 抛 IllegalArgumentException */
    private int normalizePage(int page) {
        return Math.max(page, 1);
    }

    /** size 钳制到 [1, 200] */
    private int normalizeSize(int size) {
        if (size < 1) return 1;
        return Math.min(size, MAX_PAGE_SIZE);
    }
}

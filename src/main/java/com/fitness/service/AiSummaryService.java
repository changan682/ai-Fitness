package com.fitness.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitness.cache.CacheKeys;
import com.fitness.cache.DistributedLockUtil;
import com.fitness.cache.RedisCacheService;
import com.fitness.client.AiPythonClient;
import com.fitness.dto.AiSummaryResponse;
import com.fitness.dto.ai.AiSummaryCacheValue;
import com.fitness.dto.ai.PySummaryData;
import com.fitness.dto.ai.PySummaryRequest;
import com.fitness.entity.AiSummaryCache;
import com.fitness.entity.TrainingRecord;
import com.fitness.exception.BusinessException;
import com.fitness.exception.ErrorCode;
import com.fitness.repository.AiSummaryCacheRepository;
import com.fitness.repository.TrainingRecordRepository;
import com.fitness.util.ActionMuscleMapper;
import com.fitness.util.AiTimeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * AI 训练总结服务 — 规范 7.1（MVP 核心功能）
 * <p>
 * <b>双层缓存</b>（规范 Redis 2.8 强制要求「数据库 + Redis 双层缓存」）：
 * <pre>
 * 1. 先查 Redis      → 命中且快照一致 → 直接返回（cached=true，最快路径）
 * 2. 未命中查 MySQL  → 命中且快照一致 → 回写 Redis 后返回（cached=true，兜住重启/跨零点）
 * 3. 都未命中        → 抢互斥锁 → 组装数据调 Python → 写 MySQL + Redis（cached=false）
 * </pre>
 * 「快照一致」指 {@code input_snapshot} 与当前训练记录一致。规范在
 * {@code t_ai_summary_cache.input_snapshot} 的注释里写明它的用途就是「判断是否需要重新生成」，
 * 因此训练记录被增删改后，旧总结必须重新生成 —— 这是主动失效之外的第二道保险。
 * <p>
 * <b>与上次同部位对比</b>：规范 7.1 第 2 步要求对比「上次<b>同部位</b>」。
 * 训练表只存自由填写的动作名，因此用 {@link ActionMuscleMapper} 把动作归一到 6 大肌群，
 * 命中同一肌群即可比（不要求动作名相同）；肌群判断不出来时退化为「同名动作对比」，
 * 并在 {@code comparison.scope} 里标明实际口径，避免大模型把两种口径混为一谈。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiSummaryService {

    /** 历史日期总结的缓存 TTL（秒）— 当天总结用「到次日凌晨」 */
    private static final long SUMMARY_TTL_SECONDS = 24 * 3600L;

    /** 回源互斥锁持有时间（秒）— 需大于一次大模型调用耗时 */
    private static final long SUMMARY_LOCK_SECONDS = 120L;

    /** 未抢到锁时的等待重试时长 */
    private static final long LOCK_RETRY_WAIT_MS = 500L;

    private final TrainingRecordRepository trainingRecordRepository;
    private final AiSummaryCacheRepository aiSummaryCacheRepository;
    private final AiPythonClient aiPythonClient;
    private final RedisCacheService redisCacheService;
    private final DistributedLockUtil distributedLockUtil;
    private final ObjectMapper objectMapper;

    /**
     * 生成（或读取缓存的）当日训练总结
     *
     * @param date 目标日期，null 表示今天
     */
    public AiSummaryResponse generateSummary(Long userId, LocalDate date) {
        LocalDate targetDate = date != null ? date : LocalDate.now();

        // Step 1：当日训练记录（同时也是「有没有东西可总结」的判断依据）
        List<TrainingRecord> records =
                trainingRecordRepository.findByUserIdAndTrainingDate(userId, targetDate);
        if (records.isEmpty()) {
            // 规范 7.1 错误示例：{ "code": 2003, "msg": "今日暂无训练记录，无法生成总结" }
            throw new BusinessException(ErrorCode.NO_TRAINING_RECORD, "今日暂无训练记录，无法生成总结");
        }

        String snapshot = buildSnapshot(records);
        String cacheKey = CacheKeys.AI_SUMMARY + userId + ":" + targetDate;

        // Step 2：Redis 层
        AiSummaryCacheValue redisHit = readFromRedis(cacheKey);
        if (redisHit != null && snapshot.equals(redisHit.getInputSnapshot())) {
            log.info("训练总结命中Redis缓存: userId={}, date={}", userId, targetDate);
            return toResponse(redisHit.getSummary(), redisHit.getGeneratedAt(), true);
        }
        if (redisHit != null) {
            log.info("训练总结缓存快照已过期，将重新生成: userId={}, date={}", userId, targetDate);
            redisCacheService.delete(cacheKey);
        }

        // Step 3：MySQL 层（兜住 Redis 重启 / 跨零点失效）
        AiSummaryCache dbHit = aiSummaryCacheRepository
                .findByUserIdAndSummaryDate(userId, targetDate)
                .orElse(null);
        if (dbHit != null && snapshot.equals(dbHit.getInputSnapshot())) {
            log.info("训练总结命中数据库缓存，回写Redis: userId={}, date={}", userId, targetDate);
            writeToRedis(cacheKey, dbHit.getSummaryText(), null, snapshot, targetDate);
            return toResponse(dbHit.getSummaryText(), null, true);
        }

        // Step 4：回源 Python —— 互斥锁防击穿（规范第五章 5）
        String lockKey = CacheKeys.lockAiSummary(userId, targetDate);
        String lockValue = distributedLockUtil.tryLock(lockKey, SUMMARY_LOCK_SECONDS);

        if (lockValue == null) {
            log.info("未获得总结生成锁，等待重试: userId={}, date={}", userId, targetDate);
            sleepQuietly(LOCK_RETRY_WAIT_MS);
            AiSummaryCacheValue retry = readFromRedis(cacheKey);
            if (retry != null && snapshot.equals(retry.getInputSnapshot())) {
                return toResponse(retry.getSummary(), retry.getGeneratedAt(), true);
            }
            // 对方较慢时直接自己生成：可用性优先于「只回源一次」
        }

        try {
            PySummaryData generated = aiPythonClient.generateSummary(
                    buildRequest(userId, targetDate, records));

            persist(userId, targetDate, generated.getSummary(), snapshot);
            writeToRedis(cacheKey, generated.getSummary(), generated.getGeneratedAt(),
                    snapshot, targetDate);

            log.info("训练总结生成完成: userId={}, date={}, 字数={}",
                    userId, targetDate,
                    generated.getSummary() == null ? 0 : generated.getSummary().length());

            return toResponse(generated.getSummary(), generated.getGeneratedAt(), false);
        } finally {
            if (lockValue != null) {
                distributedLockUtil.unlock(lockKey, lockValue);
            }
        }
    }

    /**
     * 失效某用户某日的总结缓存（训练记录被增删改时调用）
     * <p>
     * 规范 2.8：「如果当天有新训练记录录入 → 主动失效 Redis key（允许用户手动触发重新生成）」。
     * 这里<b>连 MySQL 那层一起删</b>——只删 Redis 的话，第 3 步会立刻从 DB 把旧总结捞回来，
     * 等于没失效。
     */
    public void invalidateSummaryCache(Long userId, LocalDate date) {
        if (userId == null || date == null) {
            return;
        }
        redisCacheService.delete(CacheKeys.AI_SUMMARY + userId + ":" + date);
        try {
            aiSummaryCacheRepository.deleteByUserIdAndSummaryDate(userId, date);
        } catch (Exception e) {
            // Redis 已失效，DB 删除失败最多导致下次多走一次 Python，不影响正确性
            log.warn("删除AI总结DB缓存失败（Redis 已失效，不影响功能）: userId={}, date={}, err={}",
                    userId, date, e.getMessage());
        }
        log.debug("AI总结缓存已失效: userId={}, date={}", userId, date);
    }

    // ==================== 落库 ====================

    /**
     * 写入 MySQL（幂等：uk_user_date 唯一索引保证同一天只有一条）
     * <p>
     * 刻意<b>不加</b> {@code @Transactional}：
     * <ul>
     *   <li>这里只有一次 {@code save}，Spring Data 的 {@code SimpleJpaRepository#save}
     *       自带事务，单条 upsert 本身就是原子的；</li>
     *   <li>若给本方法加事务注解，通过 {@code this.} 调用不会被代理生效（Spring AOP 限制），
     *       反而会造成「以为有事务其实没有」的错觉；</li>
     *   <li>更不能把 {@code generateSummary} 整体加上事务 —— 那会在事务里发起
     *       最长 30s 的跨语言 HTTP 调用，长时间占用数据库连接。</li>
     * </ul>
     */
    void persist(Long userId, LocalDate date, String summary, String snapshot) {
        try {
            upsertRow(userId, date, summary, snapshot);
        } catch (DataIntegrityViolationException e) {
            // 并发场景：两个线程都没查到行、都去 INSERT，其中一个必然撞 uk_user_date。
            // 不能让它冒成 9999 —— 重读一次改成 UPDATE 即可（另一个线程写入的也是同一天同快照的
            // 内容，等价；重写一次只是把最新文案落库）。
            log.warn("AI 总结并发写入撞 uk_user_date，改为更新已存在的行: userId={}, date={}", userId, date);
            try {
                upsertRow(userId, date, summary, snapshot);
            } catch (Exception retryError) {
                // 兜底：DB 只是「Redis 失效后的第二层」，这层写失败不影响本次返回，
                // 但要把原因留在日志里（否则历史总结会在重启后消失且无人知晓）。
                log.warn("AI 总结重试写库仍失败（Redis 层已有缓存，不影响本次结果）: userId={}, date={}, err={}",
                        userId, date, retryError.getMessage());
            }
        }
    }

    /**
     * 真正的写库动作（查一行→写字段→flush）
     * <p>
     * 用 {@code saveAndFlush} 而不是 {@code save}：唯一键冲突要在这一层立刻抛出，
     * 才能被 {@link #persist} 捕获并转成「改为更新」；推迟到事务提交时就已经出了 catch 范围。
     */
    private void upsertRow(Long userId, LocalDate date, String summary, String snapshot) {
        AiSummaryCache row = aiSummaryCacheRepository
                .findByUserIdAndSummaryDate(userId, date)
                .orElseGet(() -> AiSummaryCache.builder()
                        .userId(userId)
                        .summaryDate(date)
                        .build());
        row.setSummaryText(summary == null ? "" : summary);
        row.setInputSnapshot(snapshot);
        aiSummaryCacheRepository.saveAndFlush(row);
    }

    // ==================== Redis 读写 ====================

    private AiSummaryCacheValue readFromRedis(String cacheKey) {
        try {
            Object cached = redisCacheService.get(cacheKey);
            if (cached instanceof AiSummaryCacheValue value) {
                return value;
            }
            if (cached != null) {
                // 历史遗留格式或类型不符：丢弃而不是强转，避免 ClassCastException
                log.warn("AI总结缓存值类型异常，已丢弃: type={}", cached.getClass().getSimpleName());
                redisCacheService.delete(cacheKey);
            }
        } catch (Exception e) {
            // 缓存读取失败不影响主流程（还有 MySQL 与 Python 两条路）
            log.warn("读取AI总结缓存失败: {}", e.getMessage());
        }
        return null;
    }

    private void writeToRedis(String cacheKey, String summary, String generatedAt,
                              String snapshot, LocalDate targetDate) {
        try {
            AiSummaryCacheValue value = new AiSummaryCacheValue(summary, generatedAt, snapshot);
            redisCacheService.set(cacheKey, value, ttlSeconds(targetDate), TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("写入AI总结缓存失败（DB 已持久化，不影响功能）: {}", e.getMessage());
        }
    }

    /**
     * 当天总结缓存到次日凌晨（规范 Redis 2.8），历史日期固定 24 小时
     * <p>
     * 这里刻意不加 ±300s 随机扰动：总结内容绑定它自己的 date，
     * 即使多存活几分钟，命中的仍是同一天的正确内容，不存在串数据风险；
     * 而「到次日凌晨」的精确语义更贴合规范原文。
     */
    private long ttlSeconds(LocalDate targetDate) {
        if (targetDate.equals(LocalDate.now())) {
            return RedisCacheService.secondsUntilMidnight();
        }
        return SUMMARY_TTL_SECONDS;
    }

    // ==================== 请求组装 ====================

    /** 组装 Python 请求：记录摘要 + 上次同部位对比 */
    private PySummaryRequest buildRequest(Long userId, LocalDate date, List<TrainingRecord> records) {
        List<PySummaryRequest.RecordBrief> briefs = new ArrayList<>();
        for (TrainingRecord r : records) {
            briefs.add(PySummaryRequest.RecordBrief.builder()
                    .action(r.getActionName())
                    .sets(r.getSets())
                    .reps(r.getReps())
                    .weight(r.getWeightKg())
                    .rpe(r.getRpe())
                    .build());
        }

        return PySummaryRequest.builder()
                .userId(userId)
                .date(date.toString())
                .records(briefs)
                .comparison(buildComparison(userId, date, records))
                .build();
    }

    /** 输入快照：把当日记录序列化成稳定字符串，用于判断缓存是否过期 */
    private String buildSnapshot(List<TrainingRecord> records) {
        List<Map<String, Object>> snapshot = new ArrayList<>();
        for (TrainingRecord r : records) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", r.getId());
            item.put("action", r.getActionName());
            item.put("sets", r.getSets());
            item.put("reps", r.getReps());
            item.put("weight", r.getWeightKg());
            item.put("rpe", r.getRpe());
            item.put("volume", r.getVolume());
            snapshot.add(item);
        }
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            // 序列化失败时返回唯一值，等价于「本次不命中缓存」——
            // 宁可多算一次，也不要误用可能过期的缓存
            log.warn("生成AI总结输入快照失败，本次将不使用缓存: {}", e.getMessage());
            return "snapshot-error-" + System.nanoTime();
        }
    }

    /**
     * 组装「与上次对比」数据
     * <p>
     * 优先级：<b>同部位对比</b>（规范 7.1 要求）→ 同名动作对比（肌群判断不出时的退化）→ null。
     * <p>
     * comparison 结构与 Python 侧 {@code _format_comparison_for_prompt} 对齐：
     * <pre>
     * {
     *   "previousDate":    "2026-07-23",
     *   "scope":           "同部位" | "同名动作",
     *   "sharedMuscles":   ["胸"],              // scope=同部位 时才有
     *   "sharedActions":   ["杠铃卧推"],
     *   "records":         [上次的记录...],
     *   "volumeChangePct": 5.3                  // 与 scope 同口径的变化幅度
     * }
     * </pre>
     */
    private Map<String, Object> buildComparison(Long userId, LocalDate date,
                                                List<TrainingRecord> todayRecords) {
        TrainingRecord previousAnchor = trainingRecordRepository
                .findFirstByUserIdAndTrainingDateLessThanOrderByTrainingDateDesc(userId, date)
                .orElse(null);
        if (previousAnchor == null) {
            return null;
        }

        LocalDate previousDate = previousAnchor.getTrainingDate();
        List<TrainingRecord> previousRecords =
                trainingRecordRepository.findByUserIdAndTrainingDate(userId, previousDate);

        List<String> todayActions = new ArrayList<>();
        for (TrainingRecord r : todayRecords) {
            todayActions.add(r.getActionName());
        }
        List<String> previousActions = new ArrayList<>();
        for (TrainingRecord r : previousRecords) {
            previousActions.add(r.getActionName());
        }

        Map<String, Object> comparison = new LinkedHashMap<>();
        comparison.put("previousDate", previousDate.toString());
        comparison.put("records", toBriefList(previousRecords));

        // 同名动作（两种口径都作为补充信息给出，便于大模型点名具体动作）
        Set<String> sharedActions = new LinkedHashSet<>(todayActions);
        sharedActions.retainAll(new LinkedHashSet<>(previousActions));
        comparison.put("sharedActions", new ArrayList<>(sharedActions));

        // 先试「同部位」：这是规范要求的口径
        Set<String> sharedMuscles = ActionMuscleMapper.sharedMuscles(todayActions, previousActions);
        if (!sharedMuscles.isEmpty()) {
            comparison.put("scope", "同部位");
            comparison.put("sharedMuscles", new ArrayList<>(sharedMuscles));

            BigDecimal todayVolume = volumeOfMuscles(todayRecords, sharedMuscles);
            BigDecimal previousVolume = volumeOfMuscles(previousRecords, sharedMuscles);
            putVolumeChange(comparison, todayVolume, previousVolume);

            log.debug("同部位对比: userId={}, 共同肌群={}, 今日容量={}, 上次容量={}",
                    userId, sharedMuscles, todayVolume, previousVolume);
            return comparison;
        }

        // ---- 退化路径：按同名动作比 ----
        List<String> unresolvedToday = ActionMuscleMapper.unresolved(todayActions);
        List<String> unresolvedPrevious = ActionMuscleMapper.unresolved(previousActions);
        log.info("无法判定共同肌群，退化为同名动作对比: userId={}, 今日未识别动作={}, 上次未识别动作={}",
                userId, unresolvedToday, unresolvedPrevious);

        comparison.put("scope", "同名动作");
        if (!sharedActions.isEmpty()) {
            BigDecimal todayVolume = volumeOfActions(todayRecords, sharedActions);
            BigDecimal previousVolume = volumeOfActions(previousRecords, sharedActions);
            putVolumeChange(comparison, todayVolume, previousVolume);
        }
        return comparison;
    }

    private void putVolumeChange(Map<String, Object> comparison,
                                 BigDecimal todayVolume, BigDecimal previousVolume) {
        if (previousVolume != null && previousVolume.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal pct = todayVolume.subtract(previousVolume)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(previousVolume, 1, RoundingMode.HALF_UP);
            comparison.put("volumeChangePct", pct);
        }
    }

    /** 属于指定肌群的动作的容量总和 */
    private BigDecimal volumeOfMuscles(List<TrainingRecord> records, Set<String> muscles) {
        BigDecimal sum = BigDecimal.ZERO;
        for (TrainingRecord r : records) {
            String muscle = ActionMuscleMapper.resolve(r.getActionName());
            if (muscle != null && muscles.contains(muscle)) {
                sum = sum.add(r.getVolume() == null ? BigDecimal.ZERO : r.getVolume());
            }
        }
        return sum;
    }

    /** 指定动作名的容量总和 */
    private BigDecimal volumeOfActions(List<TrainingRecord> records, Set<String> actions) {
        BigDecimal sum = BigDecimal.ZERO;
        for (TrainingRecord r : records) {
            if (actions.contains(r.getActionName())) {
                sum = sum.add(r.getVolume() == null ? BigDecimal.ZERO : r.getVolume());
            }
        }
        return sum;
    }

    /** 上次记录的摘要（给 Python 侧 Prompt 用，字段名与 RecordBrief 对齐） */
    private List<Map<String, Object>> toBriefList(List<TrainingRecord> records) {
        List<Map<String, Object>> briefs = new ArrayList<>();
        for (TrainingRecord r : records) {
            Map<String, Object> brief = new LinkedHashMap<>();
            brief.put("action", r.getActionName());
            brief.put("sets", r.getSets());
            brief.put("reps", r.getReps());
            brief.put("weight", r.getWeightKg());
            brief.put("rpe", r.getRpe());
            briefs.add(brief);
        }
        return briefs;
    }

    private AiSummaryResponse toResponse(String summary, String generatedAt, boolean cached) {
        return AiSummaryResponse.builder()
                .summary(summary)
                .cached(cached)
                .generatedAt(AiTimeUtil.parseIsoOrNow(generatedAt))
                .build();
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

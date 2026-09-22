package com.fitness.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitness.cache.CacheKeys;
import com.fitness.cache.RedisCacheService;
import com.fitness.client.AiPythonClient;
import com.fitness.config.AiProperties;
import com.fitness.dto.AiBodyConsultResponse;
import com.fitness.dto.ai.PyBodyConsultData;
import com.fitness.dto.ai.PyBodyConsultRequest;
import com.fitness.entity.BodyMetric;
import com.fitness.entity.TrainingRecord;
import com.fitness.entity.User;
import com.fitness.exception.BusinessException;
import com.fitness.repository.BodyMetricRepository;
import com.fitness.repository.DietRecordRepository;
import com.fitness.repository.TrainingRecordRepository;
import com.fitness.repository.UserRepository;
import com.fitness.util.AiTimeUtil;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 身体状态主动问询（体验优化批次 D）
 *
 * <pre>
 *   用户点按钮 → Java 组装"身体状态快照" → Python（大模型 / 规则兜底）→ 追问 + 建议
 * </pre>
 *
 * <h3>职责边界</h3>
 * <ul>
 *   <li><b>Java</b>：取数、口径、缓存、降级文案。快照里的每个数字都能追溯到某张表，
 *       这份口径只写在 Java 一处；</li>
 *   <li><b>Python</b>：只做"看图说话"—— 大模型生成或阈值规则兜底，并自报
 *       {@code data_source}（{@code llm} / {@code rule_based}）。</li>
 * </ul>
 *
 * <h3>为什么要点按钮才生成（而不是保存体测后自动跑）</h3>
 * 每次生成都要调一次大模型。自动跑的话，用户每记一次体测就烧一次 token，而且
 * 大概率在他还没想看的时侯弹出来打扰他。点击生成既省成本也更符合"教练在我需要时出现"。
 *
 * <h3>缓存为什么必须带"输入指纹"</h3>
 * 直接按 userId 缓存 12 小时会让用户新记了一条体测后仍看到旧结论（"你 7 天没训练"
 * —— 可他今天刚练完）。因此缓存里同时存快照指纹，指纹变了就重新生成。
 * 这套做法与 {@code AiSummaryService} 一致（那里用的是 input_snapshot）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BodyConsultService {

    /** 近 7 天窗口（含今天往前推 6 天） */
    private static final int WINDOW_DAYS = 7;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final UserRepository userRepository;
    private final BodyMetricRepository bodyMetricRepository;
    private final TrainingRecordRepository trainingRecordRepository;
    private final DietRecordRepository dietRecordRepository;
    private final RedisCacheService redisCacheService;
    private final AiPythonClient aiPythonClient;
    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;

    /** 生成（或读缓存返回）身体状态问询 */
    public AiBodyConsultResponse consult(Long userId) {
        PyBodyConsultRequest snapshot = buildSnapshot(userId);
        String fingerprint = fingerprint(snapshot);
        String cacheKey = CacheKeys.aiBodyConsult(userId);

        ConsultCache cached = readCache(cacheKey);
        if (cached != null && fingerprint.equals(cached.getFingerprint()) && cached.getResponse() != null) {
            log.debug("身体状态问询命中缓存: userId={}", userId);
            AiBodyConsultResponse hit = cached.getResponse();
            return hit.toBuilder().cached(true).build();
        }

        AiBodyConsultResponse response = withFallback(snapshot);
        writeCache(cacheKey, response, fingerprint);
        return response.toBuilder().cached(false).build();
    }

    /**
     * Python 不可用时的兜底
     * <p>
     * 注意这里的语义与其它 AI 接口不同：问询是"主动关心"，Python 挂了不该让用户看到
     * 一屏技术错误，但也不能编内容。因此返回一句**诚实的空结果**（数据都在页面上，
     * 用户本来就看得到），并带上降级标记。
     */
    private AiBodyConsultResponse withFallback(PyBodyConsultRequest snapshot) {
        try {
            PyBodyConsultData data = aiPythonClient.bodyConsult(snapshot);
            return toResponse(data);
        } catch (BusinessException e) {
            log.warn("身体状态问询降级: code={}, msg={}", e.getCode(), e.getMessage());
            String fallback = aiProperties.getFallback().getConsult();
            return AiBodyConsultResponse.builder()
                    .assessment(fallback)
                    .trendSummary(describeSnapshotLocally(snapshot))
                    .questions(new ArrayList<>())
                    .suggestions(new ArrayList<>())
                    .riskFlags(new ArrayList<>())
                    .dataSource("none")
                    .degraded(true)
                    .degradationReason("AI 服务不可用（" + e.getMessage() + "），以下仅为本地数据摘要")
                    .generatedAt(java.time.LocalDateTime.now())
                    .cached(false)
                    .build();
        }
    }

    private AiBodyConsultResponse toResponse(PyBodyConsultData data) {
        List<AiBodyConsultResponse.Question> questions = new ArrayList<>();
        if (data.getQuestions() != null) {
            for (PyBodyConsultData.Question q : data.getQuestions()) {
                questions.add(AiBodyConsultResponse.Question.builder()
                        .id(q.getId()).text(q.getText()).why(q.getWhy()).build());
            }
        }
        List<AiBodyConsultResponse.Suggestion> suggestions = new ArrayList<>();
        if (data.getSuggestions() != null) {
            for (PyBodyConsultData.Suggestion s : data.getSuggestions()) {
                suggestions.add(AiBodyConsultResponse.Suggestion.builder()
                        .title(s.getTitle()).detail(s.getDetail()).build());
            }
        }
        List<AiBodyConsultResponse.RiskFlag> flags = new ArrayList<>();
        if (data.getRiskFlags() != null) {
            for (PyBodyConsultData.RiskFlag f : data.getRiskFlags()) {
                flags.add(AiBodyConsultResponse.RiskFlag.builder()
                        .level(f.getLevel()).text(f.getText()).build());
            }
        }
        return AiBodyConsultResponse.builder()
                .assessment(data.getAssessment())
                .trendSummary(data.getTrendSummary())
                .questions(questions)
                .suggestions(suggestions)
                .riskFlags(flags)
                // 标记原样透传：rule_based 时前端会显示「规则生成（未使用大模型）」
                .dataSource(data.getDataSource())
                .degraded(data.getDegraded())
                .degradationReason(data.getDegradationReason())
                .generatedAt(AiTimeUtil.parseIsoOrNow(data.getGeneratedAt()))
                .cached(false)
                .build();
    }

    // ==================== 快照组装 ====================

    /** 组装身体状态快照（全部来自真实表，不做任何推断） */
    PyBodyConsultRequest buildSnapshot(Long userId) {
        User user = userRepository.findById(userId).orElse(null);
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(WINDOW_DAYS - 1L);

        PyBodyConsultRequest req = new PyBodyConsultRequest();
        req.setUserId(userId);

        if (user != null) {
            PyBodyConsultRequest.Profile profile = new PyBodyConsultRequest.Profile();
            profile.setGender(user.getGender());
            profile.setHeight(toDouble(user.getHeight()));
            profile.setWeight(toDouble(user.getWeight()));
            profile.setTrainingGoal(user.getTrainingGoal());
            profile.setTrainingLevel(user.getTrainingLevel());
            profile.setInjuryRecord(parseInjuries(user.getInjuryRecord()));
            req.setProfile(profile);
        }

        // 最新一条 + 上一条体测
        BodyMetric latest = bodyMetricRepository.findTopByUserIdOrderByRecordDateDesc(userId).orElse(null);
        if (latest != null) {
            req.setLatestMetric(toMetric(latest));
            bodyMetricRepository
                    .findFirstByUserIdAndRecordDateLessThanEqualOrderByRecordDateDesc(
                            userId, latest.getRecordDate().minusDays(1))
                    .ifPresent(prev -> req.setPrevMetric(toMetric(prev)));
        }

        // 近 7 天体测趋势
        List<BodyMetric> window = bodyMetricRepository
                .findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(userId, from, today);
        req.setTrend7d(buildTrend(window, latest));

        // 近 7 天训练
        List<TrainingRecord> records = trainingRecordRepository
                .findByUserIdAndTrainingDateBetween(userId, from, today);
        req.setTraining7d(buildTraining(records));

        // 近 7 天饮食记录天数
        req.setDietDaysRecorded(dietRecordRepository
                .findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(userId, from, today)
                .stream().map(d -> d.getRecordDate()).collect(LinkedHashSet::new, Set::add, Set::addAll)
                .size());
        return req;
    }

    private PyBodyConsultRequest.Metric toMetric(BodyMetric metric) {
        PyBodyConsultRequest.Metric item = new PyBodyConsultRequest.Metric();
        item.setRecordDate(metric.getRecordDate() == null ? null : metric.getRecordDate().format(DATE));
        item.setWeightKg(toDouble(metric.getWeightKg()));
        item.setWaistCm(toDouble(metric.getWaistCm()));
        item.setArmCm(toDouble(metric.getArmCm()));
        item.setLegCm(toDouble(metric.getLegCm()));
        item.setBodyFatPct(toDouble(metric.getBodyFatPct()));
        return item;
    }

    private PyBodyConsultRequest.Trend7d buildTrend(List<BodyMetric> window, BodyMetric latest) {
        PyBodyConsultRequest.Trend7d trend = new PyBodyConsultRequest.Trend7d();
        trend.setSamples(window.size());

        List<BodyMetric> withWeight = window.stream()
                .filter(m -> m.getWeightKg() != null).toList();
        if (withWeight.size() >= 2) {
            BigDecimal first = withWeight.get(0).getWeightKg();
            BigDecimal last = withWeight.get(withWeight.size() - 1).getWeightKg();
            trend.setWeightDelta(round1(last.subtract(first)));
        } else if (latest != null && latest.getWeightKg() != null) {
            // 只有一条时给 0 而不是 null：Python 侧据此区分"没有变化数据"与"字段缺失"
            trend.setWeightDelta(0.0);
        }

        if (withWeight.size() >= 2) {
            BigDecimal sum = withWeight.stream().map(BodyMetric::getWeightKg)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            trend.setWeightAvg7d(round1(sum.divide(
                    BigDecimal.valueOf(withWeight.size()), 2, RoundingMode.HALF_UP)));
        }

        List<BodyMetric> withWaist = window.stream()
                .filter(m -> m.getWaistCm() != null).toList();
        if (withWaist.size() >= 2) {
            trend.setWaistDelta(round1(withWaist.get(withWaist.size() - 1).getWaistCm()
                    .subtract(withWaist.get(0).getWaistCm())));
        }
        return trend;
    }

    private PyBodyConsultRequest.Training7d buildTraining(List<TrainingRecord> records) {
        PyBodyConsultRequest.Training7d training = new PyBodyConsultRequest.Training7d();
        // 「训练次数」按**不同的训练日期**数，而不是记录条数：
        // 一次训练常见录 4-6 条动作记录，按条数算会把"练了 3 天"说成"练了 18 次"
        Set<LocalDate> days = new LinkedHashSet<>();
        BigDecimal volume = BigDecimal.ZERO;
        double rpeSum = 0;
        int rpeCount = 0;
        Set<String> muscles = new LinkedHashSet<>();
        for (TrainingRecord record : records) {
            days.add(record.getTrainingDate());
            if (record.getVolume() != null) {
                volume = volume.add(record.getVolume());
            }
            if (record.getRpe() != null) {
                rpeSum += record.getRpe();
                rpeCount++;
            }
            if (record.getActionName() != null && !record.getActionName().isBlank()) {
                muscles.add(_inferMuscle(record.getActionName()));
            }
        }
        training.setSessions(days.size());
        training.setTotalVolume(round1(volume));
        training.setAvgRpe(rpeCount == 0 ? null : Math.round(rpeSum / rpeCount * 10) / 10.0);
        training.setMuscles(muscles.stream().filter(m -> !m.isBlank()).toList());
        return training;
    }

    /**
     * 由动作名粗略归到肌群
     * <p>
     * 只做关键词映射，不查表：这里的用途是让大模型知道"这周练了哪些部位"，
     * 精度要求不高；真正的肌群口径（{@code _infer_muscles}）在 Python 侧用于训练总结，
     * 两处用途不同，不必强行统一。
     */
    private String _inferMuscle(String actionName) {
        String name = actionName.toLowerCase();
        if (name.contains("卧推") || name.contains("飞鸟") || name.contains("俯卧撑")) return "胸";
        if (name.contains("划船") || name.contains("下拉") || name.contains("引体")) return "背";
        if (name.contains("深蹲") || name.contains("腿") || name.contains("硬拉")) return "腿";
        if (name.contains("推举") || name.contains("平举") || name.contains("耸肩")) return "肩";
        if (name.contains("弯举") || name.contains("下压") || name.contains("臂")) return "手臂";
        if (name.contains("卷腹") || name.contains("举腿") || name.contains("转体")
                || name.contains("挺身") || name.contains("平板")) return "核心";
        return "";
    }

    /** 本地数据摘要（Python 不可用时给用户看的，不编内容、只复述快照） */
    private String describeSnapshotLocally(PyBodyConsultRequest snapshot) {
        PyBodyConsultRequest.Metric latest = snapshot.getLatestMetric();
        Integer sessions = snapshot.getTraining7d() == null ? null : snapshot.getTraining7d().getSessions();
        if (latest == null) {
            return "还没有体测记录，先记一条体重/腰围，之后才能看出趋势。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("最新体测（").append(latest.getRecordDate()).append("）：体重 ")
                .append(latest.getWeightKg()).append("kg");
        if (latest.getWaistCm() != null) {
            sb.append("，腰围 ").append(latest.getWaistCm()).append("cm");
        }
        sb.append("；近 7 天训练 ").append(sessions == null ? 0 : sessions).append(" 次。");
        return sb.toString();
    }

    // ==================== 缓存 ====================

    private ConsultCache readCache(String key) {
        try {
            Object cached = redisCacheService.get(key);
            if (cached instanceof ConsultCache value) {
                return value;
            }
            if (cached != null) {
                return objectMapper.readValue(String.valueOf(cached), ConsultCache.class);
            }
        } catch (Exception e) {  // noqa: BLE001 - 缓存坏了就当没有缓存
            log.warn("读取身体状态问询缓存失败（按未命中处理）: key={}, err={}", key, e.getMessage());
        }
        return null;
    }

    private void writeCache(String key, AiBodyConsultResponse response, String fingerprint) {
        try {
            ConsultCache value = new ConsultCache();
            value.setFingerprint(fingerprint);
            value.setResponse(response);
            redisCacheService.set(key, value, CacheKeys.AI_BODY_CONSULT_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {  // noqa: BLE001
            log.warn("写入身体状态问询缓存失败（忽略）: key={}, err={}", key, e.getMessage());
        }
    }

    /**
     * 快照指纹：快照任何一处变化都会改变它
     * <p>
     * 用 JSON 而不是 hashCode：后者可能碰撞，碰撞的后果是"用户新记了体测却看到旧结论"，
     * 这类错误很难被发现（页面看起来一切正常）。序列化失败时退化成时间戳 ——
     * 相当于放弃缓存，也比给出过期结论安全。
     */
    private String fingerprint(PyBodyConsultRequest snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            log.warn("快照序列化失败（本次不使用缓存）: {}", e.getMessage());
            return "no-cache-" + System.nanoTime();
        }
    }

    private static Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private static Double round1(BigDecimal value) {
        return value == null ? null : value.setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    private List<String> parseInjuries(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(raw, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.debug("伤病记录解析失败，按空处理: {}", e.getMessage());
            return List.of();
        }
    }

    /** 缓存值：响应 + 生成它时的快照指纹 */
    @Data
    @NoArgsConstructor
    public static class ConsultCache {
        private String fingerprint;
        private AiBodyConsultResponse response;
    }
}

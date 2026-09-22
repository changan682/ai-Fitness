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
import com.fitness.entity.DietRecord;
import com.fitness.entity.TrainingRecord;
import com.fitness.entity.User;
import com.fitness.exception.BusinessException;
import com.fitness.exception.ErrorCode;
import com.fitness.repository.BodyMetricRepository;
import com.fitness.repository.DietRecordRepository;
import com.fitness.repository.TrainingRecordRepository;
import com.fitness.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 身体状态问询的服务层测试（Mockito，不启动 Spring）。
 *
 * <h3>这里守的三件事</h3>
 * ① <b>取数口径</b>：「近 7 天训练几次」必须按**不同的训练日期**数 —— 一次训练常录 4-6 条
 *    动作记录，按条数算会把"练了 3 天"说成"练了 18 次"，进而让 AI 给出完全错误的建议；
 * ② <b>缓存必须带指纹</b>：用户新记了一条体测后不能还看到旧结论；
 * ③ <b>Python 不可用时的兜底</b>：返回本地数据摘要 + 明确的降级标记，绝不编内容。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BodyConsultServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private BodyMetricRepository bodyMetricRepository;
    @Mock private TrainingRecordRepository trainingRecordRepository;
    @Mock private DietRecordRepository dietRecordRepository;
    @Mock private RedisCacheService redisCacheService;
    @Mock private AiPythonClient aiPythonClient;

    private BodyConsultService service;

    private static final Long USER = 9L;

    @BeforeEach
    void setUp() {
        service = new BodyConsultService(userRepository, bodyMetricRepository, trainingRecordRepository,
                dietRecordRepository, redisCacheService, aiPythonClient, new AiProperties(),
                new ObjectMapper());
    }

    // ==================== 素材 ====================

    private BodyMetric metric(LocalDate date, double weight, Double waist) {
        BodyMetric m = new BodyMetric();
        m.setUserId(USER);
        m.setRecordDate(date);
        m.setWeightKg(BigDecimal.valueOf(weight));
        m.setWaistCm(waist == null ? null : BigDecimal.valueOf(waist));
        return m;
    }

    private TrainingRecord record(LocalDate date, String action, int rpe) {
        TrainingRecord r = new TrainingRecord();
        r.setUserId(USER);
        r.setTrainingDate(date);
        r.setActionName(action);
        r.setVolume(BigDecimal.valueOf(1000));
        r.setRpe(rpe);
        return r;
    }

    private void stubBasics() {
        when(userRepository.findById(USER)).thenReturn(Optional.of(User.builder()
                .id(USER).gender(1).height(BigDecimal.valueOf(175)).weight(BigDecimal.valueOf(70))
                .trainingGoal("增肌").trainingLevel("新手").build()));
        when(bodyMetricRepository.findTopByUserIdOrderByRecordDateDesc(USER))
                .thenReturn(Optional.of(metric(LocalDate.now(), 70.5, 80.0)));
        when(bodyMetricRepository.findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(anyLong(), any(), any()))
                .thenReturn(List.of(metric(LocalDate.now().minusDays(6), 70.0, 79.0),
                        metric(LocalDate.now(), 70.5, 80.0)));
        when(trainingRecordRepository.findByUserIdAndTrainingDateBetween(anyLong(), any(), any()))
                .thenReturn(List.of());
        when(dietRecordRepository.findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(anyLong(), any(), any()))
                .thenReturn(List.of());
    }

    private PyBodyConsultData pythonData() {
        PyBodyConsultData data = new PyBodyConsultData();
        data.setAssessment("近期体重上升，腰围同步增加。");
        data.setTrendSummary("体重 +0.5kg，腰围 +1.0cm");
        data.setQuestions(List.of());
        data.setSuggestions(List.of());
        data.setRiskFlags(List.of());
        data.setDataSource("llm");
        data.setDegraded(false);
        data.setGeneratedAt("2026-09-22T19:00:00");
        return data;
    }

    // ==================== 取数口径 ====================

    @Test
    @DisplayName("近 7 天训练次数按「不同训练日期」数，而不是记录条数")
    void trainingSessionsCountedByDistinctDates() {
        stubBasics();
        LocalDate today = LocalDate.now();
        // 两天各练了 3 个动作 → 6 条记录，但只算 2 次训练
        when(trainingRecordRepository.findByUserIdAndTrainingDateBetween(anyLong(), any(), any()))
                .thenReturn(List.of(
                        record(today, "深蹲", 8), record(today, "卧推", 8), record(today, "划船", 7),
                        record(today.minusDays(2), "硬拉", 9), record(today.minusDays(2), "推举", 8),
                        record(today.minusDays(2), "弯举", 7)));

        PyBodyConsultRequest snapshot = service.buildSnapshot(USER);

        assertEquals(2, snapshot.getTraining7d().getSessions(),
                "按记录条数会算成 6 次，那样 AI 会误判训练频率");
        // (8+8+7+9+8+7)/6 = 7.833… → 保留 1 位 = 7.8
        assertEquals(7.8, snapshot.getTraining7d().getAvgRpe(), 0.01);
        assertTrue(snapshot.getTraining7d().getMuscles().contains("腿"));
        assertTrue(snapshot.getTraining7d().getMuscles().contains("胸"));
    }

    @Test
    @DisplayName("趋势：体重与腰围的变化按窗口内首尾两条算；只有一条时体重变化记 0")
    void trendUsesFirstAndLastInWindow() {
        stubBasics();

        PyBodyConsultRequest snapshot = service.buildSnapshot(USER);

        assertEquals(0.5, snapshot.getTrend7d().getWeightDelta(), 0.001);
        assertEquals(1.0, snapshot.getTrend7d().getWaistDelta(), 0.001);
        assertEquals(2, snapshot.getTrend7d().getSamples());
    }

    @Test
    @DisplayName("一次体测都没有时快照为空，交给 Python 走规则（不硬编数据）")
    void emptySnapshotWhenNoMetric() {
        when(userRepository.findById(USER)).thenReturn(Optional.empty());
        when(bodyMetricRepository.findTopByUserIdOrderByRecordDateDesc(USER)).thenReturn(Optional.empty());
        when(bodyMetricRepository.findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(anyLong(), any(), any()))
                .thenReturn(List.of());
        when(trainingRecordRepository.findByUserIdAndTrainingDateBetween(anyLong(), any(), any()))
                .thenReturn(List.of());
        when(dietRecordRepository.findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(anyLong(), any(), any()))
                .thenReturn(List.of());

        PyBodyConsultRequest snapshot = service.buildSnapshot(USER);

        assertNull(snapshot.getLatestMetric());
        assertNull(snapshot.getProfile());
        assertEquals(0, snapshot.getTrend7d().getSamples());
        assertEquals(0, snapshot.getTraining7d().getSessions());
    }

    @Test
    @DisplayName("上一份体测取「严格早于最新那条」的记录（同一天不该被当成上一次）")
    void prevMetricIsStrictlyBeforeLatest() {
        stubBasics();
        LocalDate latestDate = LocalDate.now();
        when(bodyMetricRepository.findFirstByUserIdAndRecordDateLessThanEqualOrderByRecordDateDesc(
                eq(USER), eq(latestDate.minusDays(1))))
                .thenReturn(Optional.of(metric(latestDate.minusDays(3), 69.5, 78.5)));

        PyBodyConsultRequest snapshot = service.buildSnapshot(USER);

        assertEquals("69.5", String.valueOf(snapshot.getPrevMetric().getWeightKg()));
    }

    // ==================== 缓存 ====================

    @Test
    @DisplayName("指纹一致时命中缓存：不再调用 Python")
    void cacheHitSkipsPython() throws Exception {
        stubBasics();
        when(aiPythonClient.bodyConsult(any())).thenReturn(pythonData());

        // 第一次：未命中 → 调 Python 并写缓存
        AiBodyConsultResponse first = service.consult(USER);
        assertFalse(first.getCached());
        verify(aiPythonClient).bodyConsult(any());

        // 取出写进缓存的值，原样喂回去模拟命中（指纹与当前快照一致）
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(redisCacheService).set(eq(CacheKeys.aiBodyConsult(USER)), captor.capture(),
                eq(CacheKeys.AI_BODY_CONSULT_TTL_SECONDS), eq(TimeUnit.SECONDS));
        when(redisCacheService.get(CacheKeys.aiBodyConsult(USER))).thenReturn(captor.getValue());

        AiBodyConsultResponse second = service.consult(USER);

        assertTrue(second.getCached(), "指纹没变就应该走缓存");
        verify(aiPythonClient, org.mockito.Mockito.times(1)).bodyConsult(any());
    }

    @Test
    @DisplayName("快照变了（新记了体测）就不再吃旧缓存 —— 否则用户会看到过期结论")
    void fingerprintChangeInvalidatesCache() throws Exception {
        stubBasics();
        when(aiPythonClient.bodyConsult(any())).thenReturn(pythonData());
        service.consult(USER);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(redisCacheService).set(eq(CacheKeys.aiBodyConsult(USER)), captor.capture(),
                any(Long.class), any(TimeUnit.class));
        when(redisCacheService.get(CacheKeys.aiBodyConsult(USER))).thenReturn(captor.getValue());

        // 用户新记了一条体测（体重变了）→ 快照指纹随之变化
        when(bodyMetricRepository.findTopByUserIdOrderByRecordDateDesc(USER))
                .thenReturn(Optional.of(metric(LocalDate.now(), 72.3, 81.0)));

        AiBodyConsultResponse again = service.consult(USER);

        assertFalse(again.getCached(), "数据变了就必须重新生成");
        verify(aiPythonClient, org.mockito.Mockito.times(2)).bodyConsult(any());
    }

    @Test
    @DisplayName("Redis 读失败只当没缓存，功能仍然可用")
    void redisFailureFallsBackToFreshCall() {
        stubBasics();
        when(redisCacheService.get(anyString())).thenThrow(new RuntimeException("Redis 不可用"));
        when(aiPythonClient.bodyConsult(any())).thenReturn(pythonData());

        AiBodyConsultResponse response = service.consult(USER);

        assertEquals("近期体重上升，腰围同步增加。", response.getAssessment());
        assertFalse(response.getCached());
    }

    // ==================== 降级 ====================

    @Test
    @DisplayName("Python 不可用：返回本地数据摘要 + 明确降级标记，不编内容")
    void pythonFailureReturnsLocalSummary() {
        stubBasics();
        when(aiPythonClient.bodyConsult(any()))
                .thenThrow(new BusinessException(ErrorCode.AI_TIMEOUT));

        AiBodyConsultResponse response = service.consult(USER);

        assertTrue(response.getDegraded());
        assertEquals("none", response.getDataSource());
        assertNotNull(response.getDegradationReason());
        // 摘要必须来自快照本身（体重 70.5 是桩里给的最新值）
        assertTrue(response.getTrendSummary().contains("70.5"),
                "兜底摘要要复述真实数据，实际: " + response.getTrendSummary());
        assertTrue(response.getQuestions().isEmpty());
        assertTrue(response.getSuggestions().isEmpty());
    }

    @Test
    @DisplayName("Python 返回的 data_source=rule_based 原样透传（前端据此标注未用大模型）")
    void ruleBasedMarkerIsPassedThrough() {
        stubBasics();
        PyBodyConsultData data = pythonData();
        data.setDataSource("rule_based");
        data.setDegraded(true);
        data.setDegradationReason("未配置 DEEPSEEK_API_KEY，已改用规则引擎生成");
        when(aiPythonClient.bodyConsult(any())).thenReturn(data);

        AiBodyConsultResponse response = service.consult(USER);

        assertEquals("rule_based", response.getDataSource());
        assertTrue(response.getDegraded());
        assertEquals("未配置 DEEPSEEK_API_KEY，已改用规则引擎生成", response.getDegradationReason());
    }

    @Test
    @DisplayName("饮食记录天数按「不同日期」去重")
    void dietDaysCountedByDistinctDates() {
        stubBasics();
        LocalDate today = LocalDate.now();
        when(dietRecordRepository.findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(anyLong(), any(), any()))
                .thenReturn(List.of(
                        diet(today, "早餐"), diet(today, "午餐"),
                        diet(today.minusDays(1), "晚餐")));

        PyBodyConsultRequest snapshot = service.buildSnapshot(USER);

        assertEquals(2, snapshot.getDietDaysRecorded());
    }

    private DietRecord diet(LocalDate date, String meal) {
        DietRecord d = new DietRecord();
        d.setUserId(USER);
        d.setRecordDate(date);
        d.setMealType(meal);
        d.setFoodName("鸡胸肉");
        return d;
    }
}

package com.fitness.service;

import com.fitness.dto.mq.WeeklyPlanMessage;
import com.fitness.repository.DietRecordRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * 周计划消息生产测试 —— 规范「MQ 消息体 JSON Schema」
 *
 * <h3>这条链路最容易错在哪</h3>
 * MQ 消息体是**跨语言契约**：Python 侧按固定字段名取值拼 Prompt。
 * Java 这边把字段名拼错、或把嵌套对象写成扁平结构，不会有任何编译错误、也不会抛异常 ——
 * Python 只会安安静静地收到一堆 null，最后生成一份「没有任何数据」的周计划。
 * 因此这里逐字段断言消息体结构，而不是只断言「消息发出去了」。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WeeklyPlanProducer：消息体字段映射")
class WeeklyPlanProducerTest {

    private static final Long USER_ID = 1001L;
    private static final LocalDate WEEK_START = LocalDate.parse("2026-08-03");   // 周一

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private StatsService statsService;

    @Mock
    private DietRecordRepository dietRecordRepository;

    @InjectMocks
    private WeeklyPlanProducer producer;

    // ==================== 字段映射 ====================

    @Test
    @DisplayName("统计结果 → 消息体：字段名、嵌套结构、taskId 全部按规范")
    void shouldMapStatsIntoSpecMessage() {
        given(dietRecordRepository
                .findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(anyLong(), any(), any()))
                .willReturn(List.of());

        WeeklyPlanMessage message = producer.buildMessage(USER_ID, WEEK_START, fullStats());

        // taskId / 周期：Python 侧用 taskId 做幂等与重试计数
        assertEquals("weekly-plan-1001-2026-08-03", message.getTaskId());
        assertEquals(USER_ID, message.getUserId());
        assertEquals("2026-08-03", message.getWeekStart());
        assertEquals("2026-08-09", message.getWeekEnd(), "weekEnd 必须是周日（+6 天）");
        assertNotNull(message.getTimestamp());
        assertTrue(message.getTimestamp().contains("T"), "时间戳按规范用 ISO8601：2026-08-03T21:00:00");

        // trainingSummary
        WeeklyPlanMessage.TrainingSummary training = message.getTrainingSummary();
        assertEquals(5, training.getTrainingDays());
        assertEquals(18, training.getTotalActions());
        assertEquals(0, new BigDecimal("18500.5").compareTo(training.getTotalVolume()));
        assertEquals(0, new BigDecimal("7.5").compareTo(training.getAvgRpe()));
        assertEquals(2, training.getTopActions().size());
        assertEquals("杠铃卧推", training.getTopActions().get(0).getActionName());
        assertEquals(2, training.getTopActions().get(0).getCount());
        assertEquals(0, new BigDecimal("5200.0")
                .compareTo(training.getTopActions().get(0).getTotalVolume()));

        // bodyMetrics 必须是**嵌套对象**（规范如此，Python 侧按 msg["bodyMetrics"]["weightChange"] 取）
        WeeklyPlanMessage.BodyMetrics body = message.getBodyMetrics();
        assertEquals(0, new BigDecimal("70.8").compareTo(body.getStartWeight()));
        assertEquals(0, new BigDecimal("70.5").compareTo(body.getEndWeight()));
        assertEquals(0, new BigDecimal("-0.3").compareTo(body.getWeightChange()));

        // dietSummary
        assertEquals(0, new BigDecimal("2100.5").compareTo(message.getDietSummary().getAvgDailyCalories()));
    }

    @Test
    @DisplayName("totalMeals 来自本周饮食记录条数")
    void totalMealsShouldComeFromDietRecords() {
        given(dietRecordRepository
                .findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(USER_ID, WEEK_START,
                        WEEK_START.plusDays(6)))
                .willReturn(List.of(
                        org.mockito.Mockito.mock(com.fitness.entity.DietRecord.class),
                        org.mockito.Mockito.mock(com.fitness.entity.DietRecord.class),
                        org.mockito.Mockito.mock(com.fitness.entity.DietRecord.class)));

        WeeklyPlanMessage message = producer.buildMessage(USER_ID, WEEK_START, fullStats());

        assertEquals(3, message.getDietSummary().getTotalMeals());
    }

    @Test
    @DisplayName("统计查询异常时 totalMeals 置空而不是让整条消息发不出去")
    void mealCountFailureShouldNotBreakMessage() {
        given(dietRecordRepository
                .findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(anyLong(), any(), any()))
                .willThrow(new RuntimeException("DB 连接抖动"));

        WeeklyPlanMessage message = producer.buildMessage(USER_ID, WEEK_START, fullStats());

        assertNull(message.getDietSummary().getTotalMeals());
        assertEquals(5, message.getTrainingSummary().getTrainingDays(), "其余字段不受影响");
    }

    @Test
    @DisplayName("Redis 缓存往返后的类型差异（Integer/Double/String）也要能正确转换")
    void shouldTolerateRedisRoundTripTypes() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("trainingDays", 5);                 // Integer
        stats.put("totalActions", "18");              // String（某些序列化器会这样返回）
        stats.put("totalVolume", 18500.5d);           // Double
        stats.put("avgRpe", 7.5d);
        stats.put("avgCalories", "2100.5");
        stats.put("topActions", List.of(Map.of(
                "actionName", "深蹲", "count", 3, "totalVolume", 6000.0d)));
        stats.put("bodyMetrics", Map.of(
                "startWeight", 70.8d, "endWeight", 70.5d, "weightChange", -0.3d));

        WeeklyPlanMessage message = producer.buildMessage(USER_ID, WEEK_START, stats);

        assertEquals(5, message.getTrainingSummary().getTrainingDays());
        assertEquals(18, message.getTrainingSummary().getTotalActions());
        assertEquals(0, new BigDecimal("18500.5")
                .compareTo(message.getTrainingSummary().getTotalVolume()));
        assertEquals(0, new BigDecimal("2100.5")
                .compareTo(message.getDietSummary().getAvgDailyCalories()));
        assertEquals(0, new BigDecimal("-0.3").compareTo(message.getBodyMetrics().getWeightChange()));
        assertEquals(3, message.getTrainingSummary().getTopActions().get(0).getCount());
    }

    @Test
    @DisplayName("统计数据缺失/为 null 时不抛异常（消息照发，Python 侧 Prompt 会说明无数据）")
    void shouldBeNullSafeOnMissingStats() {
        WeeklyPlanMessage message = producer.buildMessage(USER_ID, WEEK_START, Map.of());

        assertNotNull(message.getTaskId());
        assertNull(message.getTrainingSummary().getTrainingDays());
        assertNull(message.getTrainingSummary().getTotalVolume());
        assertNull(message.getBodyMetrics().getWeightChange());
        assertTrue(message.getTrainingSummary().getTopActions().isEmpty());
    }

    // ==================== 发送 ====================

    @Test
    @DisplayName("发送到正确的交换机与路由键，并带上 taskId 作为 correlationId")
    void shouldSendToExchangeWithRoutingKey() {
        given(statsService.getWeeklyStats(USER_ID, WEEK_START)).willReturn(fullStats());
        given(dietRecordRepository
                .findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(anyLong(), any(), any()))
                .willReturn(List.of());

        assertTrue(producer.sendForUser(USER_ID, WEEK_START));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<org.springframework.amqp.rabbit.connection.CorrelationData> correlation =
                ArgumentCaptor.forClass(org.springframework.amqp.rabbit.connection.CorrelationData.class);

        verify(rabbitTemplate).convertAndSend(
                org.mockito.ArgumentMatchers.eq("ai.fitness.exchange"),
                org.mockito.ArgumentMatchers.eq("ai.weekly.plan.request"),
                payload.capture(), correlation.capture());

        WeeklyPlanMessage sent = (WeeklyPlanMessage) payload.getValue();
        assertEquals("weekly-plan-1001-2026-08-03", sent.getTaskId());
        assertEquals("weekly-plan-1001-2026-08-03", correlation.getValue().getId(),
                "correlationId 用 taskId：Broker 的 confirm/nack 日志才能定位到具体哪条消息");
    }

    // ==================== 测试数据 ====================

    /** 形如 StatsService.computeWeeklyStats 的返回值 */
    private Map<String, Object> fullStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("weekStart", "2026-08-03");
        stats.put("weekEnd", "2026-08-09");
        stats.put("trainingDays", 5);
        stats.put("totalActions", 18);
        stats.put("totalVolume", new BigDecimal("18500.5"));
        stats.put("avgRpe", new BigDecimal("7.5"));
        stats.put("topActions", List.of(
                Map.of("actionName", "杠铃卧推", "count", 2, "totalVolume", new BigDecimal("5200.0")),
                Map.of("actionName", "杠铃深蹲", "count", 2, "totalVolume", new BigDecimal("4800.0"))));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("startWeight", new BigDecimal("70.8"));
        body.put("endWeight", new BigDecimal("70.5"));
        body.put("weightChange", new BigDecimal("-0.3"));
        stats.put("bodyMetrics", body);
        stats.put("avgCalories", new BigDecimal("2100.5"));
        return stats;
    }
}

package com.fitness.service;

import com.fitness.config.RabbitMQConfig;
import com.fitness.dto.mq.WeeklyPlanMessage;
import com.fitness.repository.DietRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 周计划消息生产者 — Java → RabbitMQ（规范「MQ 生产者（Java）」）
 *
 * <h3>职责边界</h3>
 * 本类只做「取本周统计 → 组装规范消息体 → 发到 {@code ai.weekly.plan}」。
 * 真正的 AI 分析由 Python 消费者完成，结果再通过 HTTP 回调写回 Java。
 *
 * <h3>为什么消息体用 DTO 逐字段映射而不是直接发统计 Map</h3>
 * 统计 Map 的字段名是「给前端 Dashboard 看的」，而 MQ 消息体是**跨语言契约**：
 * Python 侧按固定字段名取值拼 Prompt。若直接把 Map 发出去，
 * 以后改一个 Dashboard 字段名就会静默改变 MQ 协议，Python 那边只会收到 null。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeeklyPlanProducer {

    /** 任务 ID 前缀，与 Python 侧 Redis 重试计数 key（mq:retry:{taskId}）共用同一标识 */
    public static final String TASK_ID_PREFIX = "weekly-plan-";

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final RabbitTemplate rabbitTemplate;
    private final StatsService statsService;
    private final DietRecordRepository dietRecordRepository;

    /** 任务 ID：weekly-plan-{userId}-{weekStart}（规范 8.1 示例即此格式） */
    public static String buildTaskId(Long userId, LocalDate weekStart) {
        return TASK_ID_PREFIX + userId + "-" + weekStart;
    }

    /**
     * 查询某用户某周的数据并发送周计划请求
     *
     * @return true 表示消息已投递到 Broker（confirm 是异步的，这里只保证「已发出」）
     */
    public boolean sendForUser(Long userId, LocalDate weekStart) {
        Map<String, Object> stats = statsService.getWeeklyStats(userId, weekStart);
        WeeklyPlanMessage message = buildMessage(userId, weekStart, stats);

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.FITNESS_EXCHANGE,
                RabbitMQConfig.ROUTING_KEY_REQUEST,
                message,
                // 带上 taskId 作为 correlationId：Broker 的 confirm/nack 回调里能直接定位是哪条消息
                new CorrelationData(message.getTaskId()));

        log.info("已发送周计划请求: taskId={}, userId={}, week=[{} ~ {}], 训练天数={}, 总容量={}",
                message.getTaskId(), userId, message.getWeekStart(), message.getWeekEnd(),
                message.getTrainingSummary().getTrainingDays(),
                message.getTrainingSummary().getTotalVolume());
        return true;
    }

    /**
     * 组装 MQ 消息体（不发送）— 独立出来便于单测直接断言字段映射
     *
     * @param stats {@link StatsService#getWeeklyStats} 的返回值；
     *              经过 Redis 缓存往返后数值类型可能是 Integer/Long/Double/BigDecimal/String，
     *              因此这里一律走宽松转换（见 {@link #toDecimal}/{@link #toInt}）
     */
    public WeeklyPlanMessage buildMessage(Long userId, LocalDate weekStart, Map<String, Object> stats) {
        LocalDate weekEnd = weekStart.plusDays(6);
        Map<String, Object> body = bodyMetricsOf(stats);

        return WeeklyPlanMessage.builder()
                .taskId(buildTaskId(userId, weekStart))
                .userId(userId)
                .weekStart(weekStart.toString())
                .weekEnd(weekEnd.toString())
                .trainingSummary(WeeklyPlanMessage.TrainingSummary.builder()
                        .trainingDays(toInt(stats.get("trainingDays")))
                        .totalActions(toInt(stats.get("totalActions")))
                        .totalVolume(toDecimal(stats.get("totalVolume")))
                        .avgRpe(toDecimal(stats.get("avgRpe")))
                        .topActions(toTopActions(stats.get("topActions")))
                        .build())
                .bodyMetrics(WeeklyPlanMessage.BodyMetrics.builder()
                        .startWeight(toDecimal(body.get("startWeight")))
                        .endWeight(toDecimal(body.get("endWeight")))
                        .weightChange(toDecimal(body.get("weightChange")))
                        .build())
                .dietSummary(WeeklyPlanMessage.DietSummary.builder()
                        .avgDailyCalories(toDecimal(stats.get("avgCalories")))
                        .totalMeals(countMeals(userId, weekStart, weekEnd))
                        .build())
                .timestamp(LocalDateTime.now().format(TIMESTAMP_FORMAT))
                .build();
    }

    // ==================== 组装辅助 ====================

    /** 体重区间：规范要求 bodyMetrics 是嵌套对象，而统计结果里它就是一层 Map */
    private Map<String, Object> bodyMetricsOf(Map<String, Object> stats) {
        Object raw = stats.get("bodyMetrics");
        return raw instanceof Map<?, ?> map ? castMap(map) : Map.of();
    }

    private List<WeeklyPlanMessage.TopAction> toTopActions(Object raw) {
        List<WeeklyPlanMessage.TopAction> actions = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> row = castMap(map);
                    actions.add(WeeklyPlanMessage.TopAction.builder()
                            .actionName(row.get("actionName") == null
                                    ? null : String.valueOf(row.get("actionName")))
                            .count(toInt(row.get("count")))
                            .totalVolume(toDecimal(row.get("totalVolume")))
                            .build());
                }
            }
        }
        return actions;
    }

    /** 本周饮食记录条数（规范消息体的 dietSummary.totalMeals） */
    private Integer countMeals(Long userId, LocalDate weekStart, LocalDate weekEnd) {
        try {
            return dietRecordRepository
                    .findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(userId, weekStart, weekEnd)
                    .size();
        } catch (Exception e) {
            // 统计数据缺失不该让整条消息发不出去：置 null，Python 侧 Prompt 会说明「无饮食数据」
            log.warn("统计本周饮食记录数失败，totalMeals 置空: userId={}, weekStart={}", userId, weekStart, e);
            return null;
        }
    }

    private static Map<String, Object> castMap(Map<?, ?> raw) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        raw.forEach((k, v) -> result.put(String.valueOf(k), v));
        return result;
    }

    /** 宽松转 int：null / 非数字一律返回 null（不抛异常，让消息照常发出去） */
    private static Integer toInt(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return new BigDecimal(String.valueOf(value).trim()).intValue();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 宽松转 BigDecimal：缓存往返后可能是 Integer/Double/String */
    private static BigDecimal toDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            // 用 valueOf(double) 而不是 new BigDecimal(double)：后者会把二进制浮点误差带进来
            return BigDecimal.valueOf(number.doubleValue());
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

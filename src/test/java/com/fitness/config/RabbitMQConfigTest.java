package com.fitness.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RabbitMQ 拓扑测试 —— 规范「RabbitMQ 完整设计」
 *
 * <h3>为什么值得单测「配置」</h3>
 * 这些参数写错了不会让程序崩溃，而是以更难发现的方式出问题：
 * <ul>
 *   <li>TTL 写成 7 天 → 消费者宕机后故障被掩盖一周（这正是我们特意从 7 天改成 1 小时的原因）；</li>
 *   <li>忘配死信交换机 → 重试 3 次后的消息被**直接丢弃**，谁也发现不了；</li>
 *   <li>路由键写错 → 消息发出去但没有任何队列接收（静默丢失）。</li>
 * </ul>
 * 这些都属于「跑起来一切正常，出事时才发现」的类型，必须用断言钉死。
 *
 * <p>注意：本测试只检查 Bean 的**声明内容**，不连 Broker
 * （真正的连通性验证在 `scripts/verify_weekly_plan_chain` 与手工 E2E 里做）。
 */
@DisplayName("RabbitMQ 拓扑：TTL / 死信 / 绑定")
class RabbitMQConfigTest {

    private final RabbitMQConfig config = new RabbitMQConfig();

    // ==================== 交换机 ====================

    @Test
    @DisplayName("两个交换机都是持久化的 topic exchange")
    void exchangesShouldBeDurableTopics() {
        TopicExchange main = config.fitnessExchange();
        assertEquals(RabbitMQConfig.FITNESS_EXCHANGE, main.getName());
        assertTrue(main.isDurable(), "Broker 重启后交换机必须还在");
        assertFalse(main.isAutoDelete());

        TopicExchange dlx = config.fitnessDlx();
        assertEquals(RabbitMQConfig.DEAD_LETTER_EXCHANGE, dlx.getName());
        assertTrue(dlx.isDurable());
    }

    // ==================== 主队列 ====================

    @Test
    @DisplayName("主队列：持久化 + TTL 1 小时 + 绑定死信交换机（三条缺一不可）")
    void weeklyPlanQueueShouldCarryTtlAndDeadLetter() {
        Queue queue = config.weeklyPlanQueue();

        assertEquals(RabbitMQConfig.WEEKLY_PLAN_QUEUE, queue.getName());
        assertTrue(queue.isDurable(), "非持久化队列在 Broker 重启后消息全丢");

        Map<String, Object> args = queue.getArguments();
        assertEquals(3_600_000, ((Number) args.get("x-message-ttl")).intValue(),
                "TTL 必须是 1 小时：消费者宕机要尽快暴露，而不是等 7 天进死信");
        assertEquals(RabbitMQConfig.DEAD_LETTER_EXCHANGE, args.get("x-dead-letter-exchange"),
                "没有死信交换机 → 重试耗尽的消息会被静默丢弃");
        assertEquals(RabbitMQConfig.ROUTING_KEY_DEAD, args.get("x-dead-letter-routing-key"));
    }

    @Test
    @DisplayName("死信队列持久化且不设 TTL（留着人工排查）")
    void deadLetterQueueShouldBeDurableWithoutTtl() {
        Queue dlq = config.weeklyPlanDlq();

        assertEquals(RabbitMQConfig.WEEKLY_PLAN_DLQ, dlq.getName());
        assertTrue(dlq.isDurable());
        assertFalse(dlq.getArguments().containsKey("x-message-ttl"),
                "死信队列若也设 TTL，会把待排查的证据一起清掉");
    }

    // ==================== 绑定 ====================

    @Test
    @DisplayName("路由键：request 进主队列、dead 进死信队列、result 走预留队列")
    void bindingsShouldUseExpectedRoutingKeys() {
        Binding main = config.bindWeeklyPlan();
        assertEquals(RabbitMQConfig.WEEKLY_PLAN_QUEUE, main.getDestination());
        assertEquals(RabbitMQConfig.FITNESS_EXCHANGE, main.getExchange());
        assertEquals(RabbitMQConfig.ROUTING_KEY_REQUEST, main.getRoutingKey());

        Binding dead = config.bindWeeklyPlanDlq();
        assertEquals(RabbitMQConfig.WEEKLY_PLAN_DLQ, dead.getDestination());
        assertEquals(RabbitMQConfig.DEAD_LETTER_EXCHANGE, dead.getExchange());
        assertEquals(RabbitMQConfig.ROUTING_KEY_DEAD, dead.getRoutingKey());

        Binding result = config.bindWeeklyPlanResult();
        assertEquals(RabbitMQConfig.WEEKLY_PLAN_RESULT_QUEUE, result.getDestination());
        assertEquals(RabbitMQConfig.ROUTING_KEY_RESULT, result.getRoutingKey());
    }

    @Test
    @DisplayName("消息序列化统一用 Jackson JSON（跨语言契约：Python 侧 json.loads 直接可读）")
    void messageConverterShouldBeJacksonJson() {
        assertInstanceOf(Jackson2JsonMessageConverter.class, config.jacksonMessageConverter());
    }
}

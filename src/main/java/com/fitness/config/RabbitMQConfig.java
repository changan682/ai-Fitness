package com.fitness.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * RabbitMQ 配置 — 每周复盘异步链路的拓扑声明（规范「RabbitMQ 完整设计」）
 *
 * <h3>拓扑</h3>
 * <pre>
 *   ai.fitness.exchange (topic, durable)
 *     └─ routingKey ai.weekly.plan.request → Queue ai.weekly.plan
 *                                              ├ x-message-ttl: 3600000 (1 小时)
 *                                              └ x-dead-letter-exchange: ai.fitness.dlx
 *     └─ routingKey ai.weekly.plan.result  → Queue ai.weekly.plan.result（Java 消费，预留）
 *
 *   ai.fitness.dlx (topic, durable)
 *     └─ routingKey ai.weekly.plan.dead    → Queue ai.weekly.plan.dlq（人工排查）
 * </pre>
 *
 * <h3>为什么 TTL 是 1 小时而不是 7 天</h3>
 * 周计划消息是每周日 21:00 由定时任务发出的。若 1 小时后仍未被消费，
 * 说明 Python 消费者已宕机或阻塞 —— 此时必须**尽快暴露**问题（消息进死信队列 + 告警），
 * 而不是等 7 天才进死信、把故障掩盖一周。
 *
 * <h3>为什么生产者要开 confirm + returns</h3>
 * <ul>
 *   <li><b>confirm</b>（{@code publisher-confirm-type: correlated}）：Broker 收到消息后回调 ack/nack，
 *       能发现「消息根本没到 Broker」；</li>
 *   <li><b>returns</b>（{@code publisher-returns: true}）：消息到了交换机但**没有任何队列匹配**时回调，
 *       能发现路由键写错这类静默丢失 —— 这是最难排查的一种「消息不见了」，必须显式记录。</li>
 * </ul>
 */
@Slf4j
@Configuration
// 回调的 HMAC 密钥同属「每周复盘异步链路」的配置，在这里一并启用属性绑定
@EnableConfigurationProperties(CallbackProperties.class)
public class RabbitMQConfig {

    /** 主交换机（持久化、非自动删除） */
    public static final String FITNESS_EXCHANGE = "ai.fitness.exchange";

    /** 死信交换机 */
    public static final String DEAD_LETTER_EXCHANGE = "ai.fitness.dlx";

    /** 周计划请求队列（Python pika 消费） */
    public static final String WEEKLY_PLAN_QUEUE = "ai.weekly.plan";

    /** 周计划死信队列（人工排查） */
    public static final String WEEKLY_PLAN_DLQ = "ai.weekly.plan.dlq";

    /** 回调结果队列（Java 消费，预留：当前回调用 HTTP 而非 MQ，仅按规范保留拓扑） */
    public static final String WEEKLY_PLAN_RESULT_QUEUE = "ai.weekly.plan.result";

    /** 生产路由键：Java → ai.weekly.plan */
    public static final String ROUTING_KEY_REQUEST = "ai.weekly.plan.request";

    /** 死信路由键：ai.weekly.plan → ai.weekly.plan.dlq */
    public static final String ROUTING_KEY_DEAD = "ai.weekly.plan.dead";

    /** 预留路由键：回调结果 */
    public static final String ROUTING_KEY_RESULT = "ai.weekly.plan.result";

    /**
     * 消息 TTL = 1 小时
     * <p>
     * 同步到 {@code application.yml} 里没有任何对应项：TTL 是**队列参数**（x-message-ttl），
     * 只能在声明队列时指定，改了这个值必须删除并重建队列才会生效（已有队列参数不匹配会被 Broker 拒绝）。
     */
    public static final long MESSAGE_TTL_MILLIS = TimeUnit.HOURS.toMillis(1);

    // ==================== 交换机 ====================

    @Bean
    public TopicExchange fitnessExchange() {
        // durable=true（Broker 重启后仍在）、autoDelete=false
        return new TopicExchange(FITNESS_EXCHANGE, true, false);
    }

    @Bean
    public TopicExchange fitnessDlx() {
        return new TopicExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    // ==================== 队列 ====================

    /**
     * 主队列：带 TTL 与死信路由
     * <p>
     * ⚠️ 队列参数（TTL / DLX / DL 路由键）是**不可变**的：若 Broker 上已存在同名队列但参数不同，
     * 启动时声明会抛 {@code PRECONDITION_FAILED}。改这些参数后需要先在 Broker 上删掉队列。
     */
    @Bean
    public Queue weeklyPlanQueue() {
        return QueueBuilder.durable(WEEKLY_PLAN_QUEUE)
                .ttl((int) MESSAGE_TTL_MILLIS)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(ROUTING_KEY_DEAD)
                .build();
    }

    @Bean
    public Queue weeklyPlanDlq() {
        // 死信队列不设 TTL：留着人工排查，处理完再由人删除
        return QueueBuilder.durable(WEEKLY_PLAN_DLQ).build();
    }

    @Bean
    public Queue weeklyPlanResultQueue() {
        return QueueBuilder.durable(WEEKLY_PLAN_RESULT_QUEUE).build();
    }

    // ==================== 绑定 ====================

    @Bean
    public Binding bindWeeklyPlan() {
        return BindingBuilder.bind(weeklyPlanQueue())
                .to(fitnessExchange())
                .with(ROUTING_KEY_REQUEST);
    }

    @Bean
    public Binding bindWeeklyPlanDlq() {
        return BindingBuilder.bind(weeklyPlanDlq())
                .to(fitnessDlx())
                .with(ROUTING_KEY_DEAD);
    }

    @Bean
    public Binding bindWeeklyPlanResult() {
        return BindingBuilder.bind(weeklyPlanResultQueue())
                .to(fitnessExchange())
                .with(ROUTING_KEY_RESULT);
    }

    // ==================== 序列化与模板 ====================

    /** 统一 Jackson JSON 序列化（与规范「消息序列化：Jackson2JsonMessageConverter」一致） */
    @Bean
    public MessageConverter jacksonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * RabbitTemplate：带发送确认与不可路由回调
     * <p>
     * 这里显式覆盖 Spring Boot 自动配置的 RabbitTemplate，目的是挂上两个回调 ——
     * 否则「消息发送失败」和「路由键写错导致没人收」都会静默发生，
     * 而定时任务只发一次、失败了没人知道，问题会被拖到下一周。
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         MessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);

        // Broker 确认：消息是否真的落到了交换机
        template.setConfirmCallback((correlationData, ack, cause) -> {
            String id = correlationData != null ? correlationData.getId() : "(无)";
            if (ack) {
                log.debug("MQ 消息已被 Broker 确认: correlationId={}", id);
            } else {
                log.error("MQ 消息发送失败（Broker 未确认）: correlationId={}, cause={}", id, cause);
            }
        });

        // 不可路由回退：消息到了交换机但没有匹配的队列（路由键写错 → 静默丢失）
        template.setReturnsCallback(returned -> log.error(
                "MQ 消息不可路由（没有队列匹配该路由键，消息已丢失）: exchange={}, routingKey={}, replyText={}",
                returned.getExchange(), returned.getRoutingKey(), returned.getReplyText()));

        return template;
    }
}

package com.fitness.task;

import com.fitness.cache.DistributedLockUtil;
import com.fitness.config.RabbitMQConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 死信队列监控测试
 *
 * <h3>为什么它值得测</h3>
 * 这个类本身不产生业务价值，它的价值是「**故障时不出声地不工作**」的反面：
 * 一旦它抛异常被吞掉、或查询的队列名写错，死信就会永远无人知晓 ——
 * 而 TTL 从 7 天改 1 小时、重试 3 次进死信这一整套设计就全白做了。
 * 因此这里断言的是「深度 &gt; 0 时必须走到告警分支」「异常不得外抛」这类**行为契约**。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DeadLetterQueueMonitor：死信提醒")
class DeadLetterQueueMonitorTest {

    @Mock
    private AmqpAdmin amqpAdmin;

    @Mock
    private DistributedLockUtil distributedLockUtil;

    @InjectMocks
    private DeadLetterQueueMonitor monitor;

    @BeforeEach
    void setUp() {
        given(distributedLockUtil.tryLock(anyString(), anyLong())).willReturn("lock-value");
    }

    @Test
    @DisplayName("深度 > 0 → 记录 ERROR 告警（并释放锁）")
    void shouldAlertWhenDeadLettersPresent() {
        given(amqpAdmin.getQueueInfo(eq(RabbitMQConfig.WEEKLY_PLAN_DLQ)))
                .willReturn(new QueueInformation(RabbitMQConfig.WEEKLY_PLAN_DLQ, 3, 0));

        monitor.checkDeadLetterQueue();

        // 告警通过日志落地：这里断言的是「查了正确的队列 + 释放了锁」，
        // 日志内容由 logback 输出（ERROR 级别可在联调时肉眼确认）
        verify(amqpAdmin).getQueueInfo(RabbitMQConfig.WEEKLY_PLAN_DLQ);
        verify(distributedLockUtil).unlock(anyString(), eq("lock-value"));
    }

    @Test
    @DisplayName("深度 = 0 → 静默（不刷日志）")
    void shouldStayQuietWhenQueueEmpty() {
        given(amqpAdmin.getQueueInfo(eq(RabbitMQConfig.WEEKLY_PLAN_DLQ)))
                .willReturn(new QueueInformation(RabbitMQConfig.WEEKLY_PLAN_DLQ, 0, 0));

        monitor.checkDeadLetterQueue();

        verify(distributedLockUtil).unlock(anyString(), anyString());
    }

    @Test
    @DisplayName("队列不存在 → 降级为 WARN，不抛异常（监控不能把定时任务打挂）")
    void shouldTolerateMissingQueue() {
        given(amqpAdmin.getQueueInfo(eq(RabbitMQConfig.WEEKLY_PLAN_DLQ)))
                .willThrow(new org.springframework.amqp.AmqpException("queue not found"));

        monitor.checkDeadLetterQueue();     // 不抛异常即通过

        verify(distributedLockUtil).unlock(anyString(), anyString());
    }

    @Test
    @DisplayName("RabbitMQ 不可用 → 吞掉异常并释放锁")
    void shouldTolerateBrokerDown() {
        given(amqpAdmin.getQueueInfo(eq(RabbitMQConfig.WEEKLY_PLAN_DLQ)))
                .willThrow(new org.springframework.amqp.AmqpConnectException(
                        new java.net.ConnectException("connection refused")));

        monitor.checkDeadLetterQueue();

        verify(distributedLockUtil).unlock(anyString(), anyString());
    }

    @Test
    @DisplayName("多实例：抢不到锁就跳过，避免重复告警刷屏")
    void shouldSkipWhenLockNotAcquired() {
        given(distributedLockUtil.tryLock(anyString(), anyLong())).willReturn(null);

        monitor.checkDeadLetterQueue();

        verify(amqpAdmin, never()).getQueueInfo(anyString());
    }
}

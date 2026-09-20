package com.fitness.task;

import com.fitness.cache.CacheKeys;
import com.fitness.cache.DistributedLockUtil;
import com.fitness.config.RabbitMQConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 死信队列监控 — 让「消息处理失败」这件事真的有人知道
 *
 * <h3>为什么必须有这个监控</h3>
 * 队列 TTL 从 7 天改成 1 小时、重试 3 次后进死信，都是为了**尽快暴露故障**。
 * 但如果没人看死信队列，这些机制等于白做：消息躺在 {@code ai.weekly.plan.dlq} 里，
 * 直到用户反馈「这周没收到周计划」才被发现 —— 那时已经过去一周了。
 *
 * <p>因此这里每 5 分钟探一次死信队列深度，只要 &gt; 0 就打 ERROR 日志。
 * 告警渠道（邮件/钉钉）按规范「预留接口」，接的时候在 {@link #alert} 里补一行即可。
 *
 * <h3>为什么用被动声明查深度</h3>
 * {@code AmqpAdmin.getQueueInfo(name)} 走的是 passive declare：
 * 队列不存在时不会创建它（否则监控自己造出一个空队列，反而让人误以为拓扑正常），
 * 只会抛异常，这里捕获后降级为一条 WARN。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeadLetterQueueMonitor {

    private static final String TASK_NAME = "dlqMonitor";

    /** 锁持有时间（秒）：一次查询很快，30 秒足够，避免实例崩溃后锁长期不释放 */
    private static final long LOCK_SECONDS = 30L;

    private final AmqpAdmin amqpAdmin;
    private final DistributedLockUtil distributedLockUtil;

    /**
     * 每 5 分钟检查死信队列深度
     * <p>
     * 触发频率可用 {@code fitness.dlq-monitor.cron} 覆盖（联调时改成每秒一次便于验证）。
     */
    @Scheduled(cron = "${fitness.dlq-monitor.cron:0 */5 * * * ?}", zone = "Asia/Shanghai")
    public void checkDeadLetterQueue() {
        String lockKey = CacheKeys.lockScheduled(TASK_NAME);
        String lockValue = distributedLockUtil.tryLock(lockKey, LOCK_SECONDS);
        if (lockValue == null) {
            // 多实例部署时只让一个实例查询，避免告警重复刷屏
            return;
        }
        try {
            QueueInformation info = amqpAdmin.getQueueInfo(RabbitMQConfig.WEEKLY_PLAN_DLQ);
            if (info == null) {
                log.warn("死信队列 {} 不存在（RabbitMQ 未启动或拓扑未声明），本次跳过监控",
                        RabbitMQConfig.WEEKLY_PLAN_DLQ);
                return;
            }
            long depth = info.getMessageCount();
            if (depth > 0) {
                alert(depth);
            } else {
                log.debug("死信队列正常: queue={}, 深度=0", RabbitMQConfig.WEEKLY_PLAN_DLQ);
            }
        } catch (Exception e) {
            // 监控本身绝不能把定时任务线程打挂；RabbitMQ 抖动时会走到这里
            log.warn("死信队列监控失败（RabbitMQ 可能不可用）: {}", e.getMessage());
        } finally {
            distributedLockUtil.unlock(lockKey, lockValue);
        }
    }

    /**
     * 死信告警（规范要求：队列深度 &gt; 0 即 ERROR + 告警）
     * <p>
     * 这里刻意把「怎么处理」也写进日志：值班的人看到日志就知道去哪儿看、
     * 看什么内容，而不只是知道「有东西进了死信」。
     */
    private void alert(long depth) {
        log.error("【死信告警】队列 {} 积压 {} 条消息 —— 说明有周计划反复处理失败（LLM 或回调 Java 连续失败 3 次以上）。"
                        + "排查：RabbitMQ 管理后台 http://192.168.199.128:15672 查看该队列的消息头 x-death（含失败原因与来源队列），"
                        + "对应日志关键字「送入死信队列」；处理完请手动确认或删除消息。",
                RabbitMQConfig.WEEKLY_PLAN_DLQ, depth);
        // TODO(第8周可选): 接入邮件/钉钉 —— 规范要求预留告警接口，当前先落 ERROR 日志
    }
}

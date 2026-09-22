package com.fitness.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitness.cache.CacheKeys;
import com.fitness.cache.DistributedLockUtil;
import com.fitness.entity.User;
import com.fitness.entity.WeeklyPlan;
import com.fitness.repository.AiChatHistoryRepository;
import com.fitness.repository.TrainingRecordRepository;
import com.fitness.repository.UserRepository;
import com.fitness.repository.WeeklyPlanRepository;
import com.fitness.service.StatsService;
import com.fitness.service.WeeklyPlanProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;

/**
 * 定时任务 — 每日提醒 + 每周统计 + 周计划MQ触发
 * <p>
 * 所有任务均使用分布式锁（lock:scheduled:{taskName}）防多实例重复执行，
 * 获取锁失败说明其他实例已执行，直接跳过。
 *
 * <h3>周日晚上这条链路的分工</h3>
 * <pre>
 *   20:00 weeklyStatsReport    → 纯 Java 统计，写 t_weekly_plan.week_summary
 *   21:00 triggerWeeklyPlanMQ  → 发 MQ，Python 消费后调 LLM，再 HTTP 回调写 suggestion_text
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledTasks {

    /** 任务名常量 — 锁 key 统一由 CacheKeys 生成，避免硬编码裸 Key */
    private static final String TASK_DAILY_REMINDER = "dailyReminder";
    private static final String TASK_WEEKLY_STATS = "weeklyStats";
    private static final String TASK_WEEKLY_PLAN_MQ = "weeklyPlanMq";
    private static final String TASK_CHAT_HISTORY_CLEANUP = "chatHistoryCleanup";

    /** 问答历史保留天数（超过即物理删除） */
    private static final int CHAT_HISTORY_RETENTION_DAYS = 90;

    private final DistributedLockUtil distributedLockUtil;
    private final TrainingRecordRepository trainingRecordRepository;
    private final UserRepository userRepository;
    private final WeeklyPlanRepository weeklyPlanRepository;
    private final StatsService statsService;
    private final WeeklyPlanProducer weeklyPlanProducer;
    private final ObjectMapper objectMapper;
    private final AiChatHistoryRepository aiChatHistoryRepository;

    /**
     * 每日20:00 — 检查当天有无训练记录，无则推送提醒
     * <p>
     * 先查出当天有训练记录的用户，剩余用户即为待提醒对象。
     * 当前以日志打印为准，通知渠道（邮件/钉钉）已预留扩展点。
     */
    @Scheduled(cron = "0 0 20 * * ?", zone = "Asia/Shanghai")
    public void nightlyTrainingReminder() {
        String lockKey = CacheKeys.lockScheduled(TASK_DAILY_REMINDER);
        String lockValue = distributedLockUtil.tryLock(lockKey, 60);
        if (lockValue == null) {
            log.info("每日提醒任务已被其他实例执行，跳过");
            return;
        }

        try {
            LocalDate today = LocalDate.now();
            // 当天已有训练记录的用户（无需提醒）
            List<Long> trainedUserIds = trainingRecordRepository
                    .findDistinctUserIdsByTrainingDate(today);
            log.info("=== 每日训练提醒: {}，今日已训练用户 {} 人 ===", today, trainedUserIds.size());

            // 所有用户数 - 已训练用户数 = 待提醒用户数
            long totalUsers = userRepository.count();
            long needRemind = Math.max(totalUsers - trainedUserIds.size(), 0);
            log.info("今日无训练记录、需提醒的用户数: {}，通知渠道预留（TODO: 接入邮件/钉钉）", needRemind);

            // TODO: 遍历未训练用户调用邮件/钉钉推送
            // for (User u : userRepository.findAll()) {
            //     if (!trainedUserIds.contains(u.getId())) { pushReminder(u); }
            // }
        } finally {
            distributedLockUtil.unlock(lockKey, lockValue);
        }
    }

    /**
     * 每周日20:00 — 汇总本周训练次数、总容量、体重变化
     * <p>
     * 复用 StatsService 统计逻辑，生成 JSON 摘要写入 t_weekly_plan.week_summary，
     * 供前端 Dashboard 展示和 AI 周计划复盘使用（suggestion_text 由第7周 AI 回调填充）。
     */
    @Scheduled(cron = "0 0 20 * * SUN", zone = "Asia/Shanghai")
    public void weeklyStatsReport() {
        String lockKey = CacheKeys.lockScheduled(TASK_WEEKLY_STATS);
        String lockValue = distributedLockUtil.tryLock(lockKey, 120);
        if (lockValue == null) {
            log.info("周统计任务已被其他实例执行，跳过");
            return;
        }

        try {
            LocalDate now = LocalDate.now();
            LocalDate weekStart = now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

            List<User> users = userRepository.findAll();
            int savedCount = 0;
            for (User user : users) {
                // getWeeklyStats 内部会顺带把统计结果写入 Redis（规范 2.10：写 MySQL + Redis）
                Map<String, Object> stats = statsService.getWeeklyStats(user.getId(), weekStart);
                String summaryJson = objectMapper.writeValueAsString(stats);

                // 幂等：同一用户同一周仅保留一条，存在则只更新 week_summary
                WeeklyPlan plan = weeklyPlanRepository
                        .findByUserIdAndWeekStart(user.getId(), weekStart)
                        .orElseGet(() -> {
                            WeeklyPlan p = new WeeklyPlan();
                            p.setUserId(user.getId());
                            p.setTaskId("weekly-stats-" + user.getId() + "-" + weekStart);
                            p.setWeekStart(weekStart);
                            return p;
                        });
                plan.setWeekSummary(summaryJson);
                weeklyPlanRepository.save(plan);
                savedCount++;
            }

            log.info("=== 本周({})训练统计完成，已写入 {} 条 week_summary ===", weekStart, savedCount);
        } catch (Exception e) {
            log.error("周统计任务执行失败", e);
        } finally {
            distributedLockUtil.unlock(lockKey, lockValue);
        }
    }

    /**
     * 每周日21:00 — 触发 MQ 发送周计划请求（规范「每周智能复盘」）
     * <p>
     * 与 20:00 的统计任务分工：20:00 先把本周统计写入 {@code t_weekly_plan.week_summary}（纯 Java），
     * 21:00 再把统计结果发给 Python 做 AI 分析，Python 生成建议后通过 HTTP 回调写
     * {@code suggestion_text}。中间隔 1 小时是为了保证统计已落库、且避开周日整点的资源竞争。
     * <p>
     * 逐用户发送而不是一次性批量：一条消息只对应一个用户，
     * Python 侧生成失败时重投/进死信都不会牵连其它用户。
     * <p>
     * <b>触发时间可配置</b>：默认仍是规范要求的「每周日 21:00」，
     * 但允许用 {@code fitness.weekly-plan.cron} 覆盖 —— 演示/联调时不必等到周日
     * （例如启动参数加 {@code --fitness.weekly-plan.cron=0/30 * * * * *} 即可每 30 秒跑一次）。
     */
    @Scheduled(cron = "${fitness.weekly-plan.cron:0 0 21 * * SUN}", zone = "Asia/Shanghai")
    public void triggerWeeklyPlanMQ() {
        String lockKey = CacheKeys.lockScheduled(TASK_WEEKLY_PLAN_MQ);
        String lockValue = distributedLockUtil.tryLock(lockKey, 300);
        if (lockValue == null) {
            log.info("周计划MQ任务已被其他实例执行，跳过");
            return;
        }

        try {
            LocalDate now = LocalDate.now();
            LocalDate weekStart = now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

            List<User> users = userRepository.findAll();
            int sent = 0;
            int failed = 0;
            for (User user : users) {
                try {
                    weeklyPlanProducer.sendForUser(user.getId(), weekStart);
                    sent++;
                } catch (Exception e) {
                    // 单个用户失败不影响其余用户：定时任务每周只跑一次，
                    // 绝不能因为一个用户的统计报错就整批不发
                    failed++;
                    log.error("周计划消息发送失败: userId={}, weekStart={}", user.getId(), weekStart, e);
                }
            }
            log.info("=== 周计划MQ触发完成: weekStart={}, 成功 {} 条, 失败 {} 条（共 {} 个用户）===",
                    weekStart, sent, failed, users.size());
        } catch (Exception e) {
            log.error("周计划MQ任务执行失败", e);
        } finally {
            distributedLockUtil.unlock(lockKey, lockValue);
        }
    }

    /**
     * 每日 03:00 — 清理 90 天前的 AI 问答历史（体验优化批次 C）
     *
     * <h3>为什么必须有个清理任务，而不是"留着也无所谓"</h3>
     * 问答历史是**每次提问都会写两条**的表：活跃用户一天几十条，一年就是上万条。
     * 留着不删，这张表迟早变成全库最大的表，而它的价值只在于"最近几轮上下文"
     * 与"近期回看"—— 90 天前的对话没人会翻。
     *
     * <h3>为什么物理删除而不是软删</h3>
     * 软删只是把行标记一下，磁盘与索引开销照旧，清理的意义就没了。
     * 对话属于低价值可弃数据，直接删更干净；真要长期留存，应当另做归档而非软删。
     *
     * 选 03:00 是为了避开 20:00 的每日提醒与周日 21:00 的周计划链路。
     */
    @Scheduled(cron = "0 0 3 * * ?", zone = "Asia/Shanghai")
    public void cleanupChatHistory() {
        String lockKey = CacheKeys.lockScheduled(TASK_CHAT_HISTORY_CLEANUP);
        String lockValue = distributedLockUtil.tryLock(lockKey, 600);
        if (lockValue == null) {
            log.info("问答历史清理任务已被其他实例执行，跳过");
            return;
        }

        try {
            LocalDateTime deadline = LocalDateTime.now().minusDays(CHAT_HISTORY_RETENTION_DAYS);
            int deleted = aiChatHistoryRepository.deleteByCreatedAtBefore(deadline);
            log.info("=== 问答历史清理完成: 删除 {} 条（{} 之前，保留期 {} 天）===",
                    deleted, deadline, CHAT_HISTORY_RETENTION_DAYS);
        } catch (Exception e) {
            log.error("问答历史清理失败", e);
        } finally {
            distributedLockUtil.unlock(lockKey, lockValue);
        }
    }
}

package com.fitness.repository;

import com.fitness.entity.AiSummaryCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Optional;

/**
 * AI 训练总结缓存 Repository
 */
@Repository
public interface AiSummaryCacheRepository extends JpaRepository<AiSummaryCache, Long> {

    /**
     * 按用户 + 日期查总结
     * <p>
     * 对应唯一索引 uk_user_date，最多一条。
     */
    Optional<AiSummaryCache> findByUserIdAndSummaryDate(Long userId, LocalDate summaryDate);

    /** 按用户 + 日期区间查历史总结（供后续「本周 AI 总结回顾」类功能使用） */
    List<AiSummaryCache> findByUserIdAndSummaryDateBetweenOrderBySummaryDateDesc(
            Long userId, LocalDate startDate, LocalDate endDate);

    /**
     * 删除某用户某日的总结（训练记录变更时主动失效用）
     * <p>
     * 方法名派生的 delete 属于「修改类查询」，必须在事务中执行，
     * 因此这里显式标注 {@code @Transactional} ——
     * 否则在无事务上下文调用会抛 Executing an update/delete query。
     */
    @Transactional
    void deleteByUserIdAndSummaryDate(Long userId, LocalDate summaryDate);

    /**
     * 清理过期历史总结
     * <p>
     * 总结表会随使用无限增长，而超过一定时间的训练总结参考价值很低。
     * 由定时任务按阈值清理，避免这张表变成磁盘黑洞。
     *
     * @param before 早于该日期（不含）的记录将被删除
     * @return 删除条数
     */
    @Transactional
    long deleteBySummaryDateBefore(LocalDate before);

    /** 查询某用户最近 N 天的总结日期（用于排查「为什么没生成」） */
    default List<LocalDate> recentSummaryDates(Long userId, int days) {
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(Math.max(days - 1, 0));
        return findByUserIdAndSummaryDateBetweenOrderBySummaryDateDesc(userId, start, end)
                .stream()
                .map(AiSummaryCache::getSummaryDate)
                .toList();
    }

    /** 本周总结（周一至今） */
    default Optional<AiSummaryCache> findThisWeek(Long userId, LocalDate today) {
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        return findByUserIdAndSummaryDateBetweenOrderBySummaryDateDesc(userId, monday, today)
                .stream()
                .findFirst();
    }
}

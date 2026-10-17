package com.fitness.repository;

import com.fitness.entity.AiAgentRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Agent 运行审计仓储。
 * <p>
 * 刻意<b>只提供两个方法</b>：写入与过期清理。
 * 审计表的价值在排查时查 SQL，不需要（也不该）在业务代码里读它 ——
 * 与其加一堆没人调用的查询方法（项目里 {@code AiSummaryCacheRepository} 已经有过这种问题），
 * 不如保持最小。
 * <p>
 * {@code deleteByCreatedAtBefore} 由 {@code ScheduledTasks#cleanupChatHistory}
 * 与问答历史一同清理，避免这张只写不读的表无限增长。
 */
@Repository
public interface AiAgentRunRepository extends JpaRepository<AiAgentRun, Long> {

    /** 物理删除某个时间点之前的审计记录（保留期与问答历史一致：90 天） */
    @Modifying
    @Transactional
    @Query("DELETE FROM AiAgentRun r WHERE r.createdAt < :before")
    int deleteByCreatedAtBefore(@Param("before") LocalDateTime before);
}

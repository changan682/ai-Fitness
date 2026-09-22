package com.fitness.repository;

import com.fitness.entity.AiChatHistory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 问答历史仓储（体验优化批次 C）
 *
 * <h3>为什么每个查询都带 user_id</h3>
 * sessionId 是 UUID，但"难猜"不等于"可以不当权限用"：只要有一条不带 user_id 的查询，
 * 一旦某个 sessionId 泄漏（日志、分享截图、前端 storage），整段对话就被读走了。
 * 因此这里的方法签名强制要求 userId，让"忘记加过滤"在编译期就不可能发生。
 */
@Repository
public interface AiChatHistoryRepository extends JpaRepository<AiChatHistory, Long> {

    /**
     * 取某个会话最近的若干条消息（按 id 倒序取，再由调用方反转成时间正序）
     * <p>
     * 用 Pageable 限流而不是一次全查：一个长会话可能有几百条消息，
     * 而送给大模型的窗口只有 6 轮，没必要把它们全读进内存。
     */
    List<AiChatHistory> findByUserIdAndSessionIdOrderByIdDesc(
            Long userId, String sessionId, Pageable pageable);

    /** 清理任务：删除某时间点之前的记录 */
    @Modifying
    @Query("DELETE FROM AiChatHistory h WHERE h.createdAt < :before")
    int deleteByCreatedAtBefore(@Param("before") LocalDateTime before);

    /** 会话数统计（排查用） */
    long countByUserIdAndSessionId(Long userId, String sessionId);
}

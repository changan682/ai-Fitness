package com.fitness.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * AI 训练总结缓存实体 — 对应 {@code t_ai_summary_cache}
 * <p>
 * 规范 Redis 2.8 要求的是「<b>数据库 + Redis 双层缓存</b>」：
 * <pre>
 * 生成总结后：写入 t_ai_summary_cache（MySQL 持久化） + Redis（TTL 到次日凌晨）
 * 查询时：先查 Redis → 未命中查 MySQL → 回写 Redis
 * 兜底：MySQL 持久化保证历史总结不丢失
 * </pre>
 * 第 4 周只落了 Redis 那一层，于是进程重启或跨零点后历史总结就没了；
 * 这个实体补上持久化兜底层。
 * <p>
 * {@code uk_user_date} 唯一索引既是幂等写入的依据，也是规范第十一章第 8 条
 * 要求的「MySQL 唯一索引作为缓存与分布式锁的最终兜底」。
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "t_ai_summary_cache",
        uniqueConstraints = @UniqueConstraint(name = "uk_user_date",
                columnNames = {"user_id", "summary_date"}),
        indexes = @Index(name = "idx_summary_date", columnList = "summary_date"))
public class AiSummaryCache {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户ID */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 总结日期 */
    @Column(name = "summary_date", nullable = false)
    private LocalDate summaryDate;

    /** AI 训练总结（Markdown） */
    @Column(name = "summary_text", nullable = false, columnDefinition = "TEXT")
    private String summaryText;

    /**
     * 输入数据快照（JSON：当日训练记录摘要）
     * <p>
     * 规范注释写明用途是「用于判断是否需要重新生成」——因此它不仅是个日志，
     * 还承担「训练记录变了、旧总结就不该再复用」的比对依据。
     */
    @Column(name = "input_snapshot", columnDefinition = "TEXT")
    private String inputSnapshot;

    /** 创建时间 */
    @Column(updatable = false, columnDefinition = "DATETIME DEFAULT CURRENT_TIMESTAMP")
    private LocalDateTime createdAt;

    /** 更新时间 */
    @Column(columnDefinition = "DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

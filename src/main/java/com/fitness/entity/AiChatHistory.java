package com.fitness.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AI 问答历史 — 对应 {@code t_ai_chat_history}（体验优化批次 C）
 *
 * <h3>它在「对话记忆」里的位置</h3>
 * 记忆分两级，本实体是**长期层**：
 * <ul>
 *   <li><b>Redis 热层</b>（{@code fitness:cache:ai:chat:session:{userId}:{sessionId}}，TTL 2 小时）
 *       —— 每轮问答真正送给大模型的上下文窗口；</li>
 *   <li><b>本表</b> —— 热层未命中（过期/Redis 重启）时回填最近几轮，使对话不会突然失忆；
 *       同时支持长期回看。</li>
 * </ul>
 * 两级都带 {@code user_id}，查历史一律"按 userId + sessionId"，绝不允许只按 sessionId 查
 * （否则猜到别人的 sessionId 就能读到别人的对话）。
 *
 * <h3>为什么 assistant 行要存 data_source / degraded</h3>
 * 回看历史时前端仍要能显示"这一轮是降级回答（未使用知识库）"。
 * 若不在历史里冗余这两个标记，回看时就得重新推算一遍，等于把「降级必须可见」
 * 这条原则打了个缺口。
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "t_ai_chat_history")
public class AiChatHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户ID —— 查询必带，防止跨用户读会话 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 会话ID（UUID 字符串）—— 只接受 UUID 格式，见 AiChatSessionService */
    @Column(name = "session_id", nullable = false, length = 36)
    private String sessionId;

    /** 角色：user / assistant（列名刻意不用 role：MySQL 8.0 的保留字） */
    @Column(name = "msg_role", nullable = false, length = 16)
    private String msgRole;

    /** 消息内容 */
    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    /** assistant 行的来源标记：milvus / llm_only / builtin / none（user 行为 null） */
    @Column(name = "data_source", length = 20)
    private String dataSource;

    /** assistant 行是否为降级回答 */
    @Column(name = "degraded")
    private Integer degraded;

    /** 创建时间（清理任务按它删 90 天前的记录） */
    @Column(name = "created_at", updatable = false, columnDefinition = "DATETIME DEFAULT CURRENT_TIMESTAMP")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (degraded == null) {
            degraded = 0;
        }
    }
}

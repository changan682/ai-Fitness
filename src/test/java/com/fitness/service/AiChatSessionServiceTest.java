package com.fitness.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitness.cache.CacheKeys;
import com.fitness.cache.RedisCacheService;
import com.fitness.entity.AiChatHistory;
import com.fitness.repository.AiChatHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对话记忆（两级存储）单元测试 —— 纯 Mockito，不启动 Spring、不依赖真实 Redis/MySQL。
 *
 * <h3>这里守的是什么</h3>
 * 记忆是"增强能力"，它的失败模式恰恰是**把问答本身带崩**：
 * Redis 挂了、表查不动、历史被塞爆 prompt。因此本文件重点断言三件事：
 * ① 热层未命中时会回表回填（read-through）；② 任何一侧故障都只降级为"没有记忆"；
 * ③ 送进模型的窗口永远被裁剪在上限内。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiChatSessionServiceTest {

    @Mock
    private RedisCacheService redisCacheService;

    @Mock
    private AiChatHistoryRepository historyRepository;

    private AiChatSessionService service;

    private static final Long USER = 7L;
    private static final String SESSION = "3f1c8b9e-6a2d-4f5b-9c7e-1d2a3b4c5d6e";

    @BeforeEach
    void setUp() {
        service = new AiChatSessionService(redisCacheService, historyRepository, new ObjectMapper());
    }

    private AiChatHistory row(String role, String content) {
        return AiChatHistory.builder()
                .userId(USER).sessionId(SESSION).msgRole(role)
                .content(content).dataSource("assistant".equals(role) ? "milvus" : null)
                .degraded(0).build();
    }

    // ==================== sessionId ====================

    @Test
    @DisplayName("非法 sessionId 一律按新会话处理（不能拿它去拼 Redis key）")
    void invalidSessionIdIsRejected() {
        assertNull(service.normalizeSessionId(null));
        assertNull(service.normalizeSessionId("  "));
        assertNull(service.normalizeSessionId("not-a-uuid"));
        // 含冒号/换行的值会把 Redis key 结构搅乱，必须挡在入口
        assertNull(service.normalizeSessionId("a:b:c"));
        assertNull(service.normalizeSessionId(SESSION + "\nX"));
        assertEquals(SESSION, service.normalizeSessionId(SESSION.toUpperCase()),
                "合法 UUID 归一化成小写");
        assertNotEquals(service.newSessionId(), service.newSessionId());
    }

    // ==================== 读：热层 / 回填 ====================

    @Test
    @DisplayName("热层命中直接返回，不查库")
    void hotHitSkipsDatabase() {
        List<java.util.Map<String, String>> cached = List.of(
                java.util.Map.of("role", "user", "content", "深蹲膝盖能超过脚尖吗"),
                java.util.Map.of("role", "assistant", "content", "可以适度超过"));
        when(redisCacheService.get(CacheKeys.aiChatSession(USER, SESSION))).thenReturn(cached);

        List<AiChatSessionService.ChatTurn> turns = service.load(USER, SESSION);

        assertEquals(2, turns.size());
        assertEquals("深蹲膝盖能超过脚尖吗", turns.get(0).getContent());
        verify(historyRepository, never()).findByUserIdAndSessionIdOrderByIdDesc(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("热层未命中 → 从历史表回填 Redis（read-through，Redis 过期/重启后不至于失忆）")
    void missFallsBackToDatabaseAndRefillsHot() {
        when(redisCacheService.get(anyString())).thenReturn(null);
        // 仓库按 id 倒序返回，服务必须反转成时间正序
        when(historyRepository.findByUserIdAndSessionIdOrderByIdDesc(eq(USER), eq(SESSION), any(Pageable.class)))
                .thenReturn(List.of(
                        row("assistant", "可以适度超过脚尖"),
                        row("user", "深蹲膝盖能超过脚尖吗")));

        List<AiChatSessionService.ChatTurn> turns = service.load(USER, SESSION);

        assertEquals(List.of("user", "assistant"),
                List.of(turns.get(0).getRole(), turns.get(1).getRole()),
                "回填后必须是时间正序（用户问在前）");
        verify(redisCacheService).set(eq(CacheKeys.aiChatSession(USER, SESSION)), anyList(),
                eq(CacheKeys.AI_CHAT_SESSION_TTL_SECONDS), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Redis 挂了只降级为「没有记忆」，不抛异常")
    void redisFailureDegradesToNoMemory() {
        when(redisCacheService.get(anyString())).thenThrow(new RuntimeException("Redis 不可用"));
        when(historyRepository.findByUserIdAndSessionIdOrderByIdDesc(anyLong(), anyString(), any(Pageable.class)))
                .thenReturn(Collections.emptyList());

        assertTrue(service.load(USER, SESSION).isEmpty());
    }

    @Test
    @DisplayName("历史表查不动同样只降级为「没有记忆」")
    void databaseFailureDegradesToNoMemory() {
        when(redisCacheService.get(anyString())).thenReturn(null);
        when(historyRepository.findByUserIdAndSessionIdOrderByIdDesc(anyLong(), anyString(), any(Pageable.class)))
                .thenThrow(new RuntimeException("MySQL 不可用"));

        assertTrue(service.load(USER, SESSION).isEmpty());
    }

    // ==================== 写 ====================

    @Test
    @DisplayName("追加一轮：热层刷新 + 两条落库（assistant 行带上降级标记）")
    void appendTurnWritesBothLayers() {
        when(redisCacheService.get(anyString())).thenReturn(null);

        service.appendTurn(USER, SESSION, "那做几组？", "建议 3-4 组", "llm_only", true);

        verify(redisCacheService).set(eq(CacheKeys.aiChatSession(USER, SESSION)), anyList(),
                eq(CacheKeys.AI_CHAT_SESSION_TTL_SECONDS), eq(TimeUnit.SECONDS));

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<AiChatHistory>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(historyRepository).saveAll(captor.capture());
        List<AiChatHistory> rows = captor.getValue();
        assertEquals(2, rows.size());
        assertEquals("user", rows.get(0).getMsgRole());
        assertEquals("assistant", rows.get(1).getMsgRole());
        // 回看历史时仍要能显示"这轮是降级回答"，所以标记必须跟着落库
        assertEquals("llm_only", rows.get(1).getDataSource());
        assertEquals(1, rows.get(1).getDegraded());
    }

    @Test
    @DisplayName("落库失败不影响本轮回答（记忆是尽力而为）")
    void databaseWriteFailureIsSwallowed() {
        when(redisCacheService.get(anyString())).thenReturn(null);
        when(historyRepository.saveAll(anyList())).thenThrow(new RuntimeException("DB 挂了"));

        service.appendTurn(USER, SESSION, "问题", "回答", "milvus", false);

        // 热层仍然写成功：这一轮上下文在 Redis 里还在，只是长期层缺一条
        verify(redisCacheService).set(anyString(), anyList(), anyLong(), any(TimeUnit.class));
    }

    @Test
    @DisplayName("「新对话」只清热层，不动长期历史")
    void clearHotKeepsLongTermHistory() {
        service.clearHot(USER, SESSION);

        verify(redisCacheService).delete(CacheKeys.aiChatSession(USER, SESSION));
        verify(historyRepository, never()).deleteByCreatedAtBefore(any());
    }

    // ==================== 窗口裁剪 ====================

    @Test
    @DisplayName("窗口上限：超过 6 轮只保留最近的，且不以 assistant 开头")
    void windowIsTrimmed() {
        List<java.util.Map<String, String>> cached = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            cached.add(java.util.Map.of("role", "user", "content", "问题" + i));
            cached.add(java.util.Map.of("role", "assistant", "content", "回答" + i));
        }
        when(redisCacheService.get(anyString())).thenReturn(cached);

        List<AiChatSessionService.ChatTurn> turns = service.load(USER, SESSION);

        assertTrue(turns.size() <= AiChatSessionService.MAX_TURNS * 2,
                "最多 6 轮 = 12 条，实际 " + turns.size());
        assertEquals("user", turns.get(0).getRole(), "窗口不能以 assistant 开头");
        assertEquals("回答19", turns.get(turns.size() - 1).getContent(), "保留的必须是最新的");
    }

    @Test
    @DisplayName("窗口上限：总字符超过 4000 时从最早的消息开始丢")
    void windowIsTrimmedByChars() {
        String big = "x".repeat(1500);
        when(redisCacheService.get(anyString())).thenReturn(List.of(
                java.util.Map.of("role", "user", "content", big),
                java.util.Map.of("role", "assistant", "content", big),
                java.util.Map.of("role", "user", "content", big),
                java.util.Map.of("role", "assistant", "content", big)));

        List<AiChatSessionService.ChatTurn> turns = service.load(USER, SESSION);

        int total = turns.stream().mapToInt(t -> t.getContent().length()).sum();
        assertTrue(total <= AiChatSessionService.MAX_CHARS, "总长度必须被裁到上限内，实际 " + total);
        assertFalse(turns.isEmpty());
    }

    @Test
    @DisplayName("超长单条消息被截断，避免一条就把窗口占满")
    void singleHugeMessageIsTruncated() {
        service.appendTurn(USER, SESSION, "问".repeat(5000), "答".repeat(5000), "milvus", false);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<AiChatSessionService.ChatTurn>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(redisCacheService).set(anyString(), captor.capture(), anyLong(), any(TimeUnit.class));
        for (AiChatSessionService.ChatTurn turn : captor.getValue()) {
            assertTrue(turn.getContent().length() <= 2000, "单条必须被截断");
        }
    }

    @Test
    @DisplayName("userId/sessionId 为空时直接不处理（防止把历史写到别人的会话上）")
    void nullIdentifiersAreNoOps() {
        assertTrue(service.load(null, SESSION).isEmpty());
        assertTrue(service.load(USER, null).isEmpty());

        service.appendTurn(null, SESSION, "q", "a", "milvus", false);
        service.appendTurn(USER, null, "q", "a", "milvus", false);

        verify(historyRepository, never()).saveAll(anyList());
        verify(redisCacheService, never()).set(anyString(), any(), anyLong(), any(TimeUnit.class));
    }

    @Test
    @DisplayName("清热层失败也不抛（登出/换会话不该因为 Redis 抖动而失败）")
    void clearHotFailureIsSwallowed() {
        doThrow(new RuntimeException("Redis 不可用")).when(redisCacheService).delete(anyString());

        service.clearHot(USER, SESSION);
    }
}

package com.fitness.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitness.cache.CacheKeys;
import com.fitness.cache.RedisCacheService;
import com.fitness.entity.AiChatHistory;
import com.fitness.repository.AiChatHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 对话记忆 — 两级存储（体验优化批次 C）
 *
 * <pre>
 *   读：Redis 热层命中 → 直接用
 *       未命中 → 查 t_ai_chat_history 最近 N 条 → 回填 Redis（read-through）
 *   写：先追加进 Redis（刷新 TTL），再尽力而为落库（write-through）
 * </pre>
 *
 * <h3>为什么不能只留 Redis</h3>
 * Redis 一重启（或 2 小时没人说话）就全忘了：用户上一句问"深蹲膝盖能否超过脚尖"，
 * 下一句"那做几组"就会答非所问 —— 这正是用户反馈的"没有记忆功能"。
 * 有了长期层，热层丢了也能从表里把最近几轮捞回来续上。
 *
 * <h3>为什么不能只留数据库</h3>
 * 每轮问答都要读一次上下文，走 MySQL 会把它变成热路径；而且历史会无限增长，
 * 让"最近 6 轮"这种小窗口查询反复全表扫。Redis 才是合适的热层。
 *
 * <h3>队列长度护栏</h3>
 * 单次送模型的窗口上限 {@link #MAX_TURNS} 轮 / {@link #MAX_CHARS} 字符：
 * ① 控制 token 成本；② 防止把整段历史塞进 prompt 挤爆上下文窗口；
 * ③ 防止用户在问题里塞超长文本把 prompt 撑坏。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatSessionService {

    /** 上下文窗口：最多保留 6 轮（12 条消息） */
    public static final int MAX_TURNS = 6;

    /** 上下文窗口：最多 4000 字符（超出丢最早的） */
    public static final int MAX_CHARS = 4000;

    /** 单条消息的长度上限（超长直接截断，避免一条把整个窗口占满） */
    private static final int MAX_MESSAGE_CHARS = 2000;

    /**
     * 合法的 sessionId：UUID
     * <p>
     * 为什么要校验格式：非法（或别人猜的）id 会被当成新会话，等于"猜也猜不出别人的上下文"；
     * 但更要紧的是它会被拼进 Redis key —— 含 {@code :} 或换行的值能把 key 结构搅乱，
     * 因此必须在入口就按格式挡住。
     */
    private static final Pattern SESSION_ID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final RedisCacheService redisCacheService;
    private final AiChatHistoryRepository historyRepository;
    private final ObjectMapper objectMapper;

    // ==================== 会话标识 ====================

    /** 校验并归一化 sessionId：非法/为空时返回 null（调用方据此新建会话） */
    public String normalizeSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        String trimmed = sessionId.trim();
        if (!SESSION_ID.matcher(trimmed).matches()) {
            log.warn("sessionId 格式非法（按新会话处理）: {}", trimmed.length() > 60 ? trimmed.substring(0, 60) : trimmed);
            return null;
        }
        return trimmed.toLowerCase();
    }

    /** 生成新的会话 id */
    public String newSessionId() {
        return UUID.randomUUID().toString();
    }

    // ==================== 读 ====================

    /**
     * 取该会话的上下文窗口（时间正序）
     * <p>
     * 先读 Redis；未命中则回表回填。Redis 或 DB 任何一侧出问题都只降级为"没有记忆"，
     * 绝不抛异常 —— 记忆是增强能力，不该让问答本身失败。
     */
    public List<ChatTurn> load(Long userId, String sessionId) {
        if (userId == null || sessionId == null) {
            return List.of();
        }
        String key = CacheKeys.aiChatSession(userId, sessionId);

        List<ChatTurn> hot = readHot(key);
        if (!hot.isEmpty()) {
            return hot;
        }

        List<ChatTurn> fromDb = readFromDb(userId, sessionId);
        if (!fromDb.isEmpty()) {
            log.info("会话热层未命中，已从历史表回填: userId={}, sessionId={}, 轮次={}",
                    userId, sessionId, fromDb.size() / 2);
            writeHot(key, fromDb);
        }
        return fromDb;
    }

    // ==================== 写 ====================

    /**
     * 追加一轮问答（用户问 + 助手答）
     * <p>
     * 落库是"尽力而为"：失败只记日志。用户已经拿到回答了，不能因为历史写不进去就让请求报错。
     */
    public void appendTurn(Long userId, String sessionId, String question, String answer,
                           String dataSource, boolean degraded) {
        if (userId == null || sessionId == null) {
            return;
        }
        String key = CacheKeys.aiChatSession(userId, sessionId);

        List<ChatTurn> turns = new ArrayList<>(readHot(key));
        turns.add(new ChatTurn("user", truncate(question)));
        turns.add(new ChatTurn("assistant", truncate(answer)));
        turns = trim(turns);
        writeHot(key, turns);

        // 落库（长期层）：两条一行，assistant 行带上降级标记以便回看时仍然可见
        try {
            List<AiChatHistory> rows = new ArrayList<>(2);
            rows.add(AiChatHistory.builder()
                    .userId(userId).sessionId(sessionId).msgRole("user")
                    .content(truncate(question)).build());
            rows.add(AiChatHistory.builder()
                    .userId(userId).sessionId(sessionId).msgRole("assistant")
                    .content(truncate(answer))
                    .dataSource(dataSource)
                    .degraded(degraded ? 1 : 0)
                    .build());
            historyRepository.saveAll(rows);
        } catch (Exception e) {  // noqa: BLE001 - 记忆写失败不影响本次回答
            log.warn("问答历史落库失败（已忽略，不影响回答）: userId={}, sessionId={}, err={}",
                    userId, sessionId, e.getMessage());
        }
    }

    /** 只清热层（「新对话」用）：DB 历史保留，将来做"历史会话"列表时还要用 */
    public void clearHot(Long userId, String sessionId) {
        if (userId == null || sessionId == null) {
            return;
        }
        try {
            redisCacheService.delete(CacheKeys.aiChatSession(userId, sessionId));
        } catch (Exception e) {  // noqa: BLE001
            log.warn("清理会话热层失败（已忽略）: userId={}, sessionId={}, err={}",
                    userId, sessionId, e.getMessage());
        }
    }

    // ==================== 内部 ====================

    @SuppressWarnings("unchecked")
    private List<ChatTurn> readHot(String key) {
        try {
            Object cached = redisCacheService.get(key);
            if (cached == null) {
                return List.of();
            }
            if (cached instanceof List<?> list) {
                return toTurns(list);
            }
            // 兼容被序列化成 JSON 字符串的情况
            List<ChatTurn> parsed = objectMapper.readValue(
                    String.valueOf(cached), new TypeReference<List<ChatTurn>>() {});
            return parsed == null ? List.of() : trim(parsed);
        } catch (Exception e) {  // noqa: BLE001 - Redis 挂了就当没有记忆
            log.warn("读取会话热层失败（按无记忆处理）: key={}, err={}", key, e.getMessage());
            return List.of();
        }
    }

    private void writeHot(String key, List<ChatTurn> turns) {
        try {
            redisCacheService.set(key, turns, CacheKeys.AI_CHAT_SESSION_TTL_SECONDS,
                    java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {  // noqa: BLE001
            log.warn("写入会话热层失败（本次对话将没有记忆）: key={}, err={}", key, e.getMessage());
        }
    }

    private List<ChatTurn> readFromDb(Long userId, String sessionId) {
        try {
            // 倒序取 MAX_TURNS*2 条，再反转成时间正序
            List<AiChatHistory> rows = historyRepository.findByUserIdAndSessionIdOrderByIdDesc(
                    userId, sessionId, PageRequest.of(0, MAX_TURNS * 2));
            if (rows.isEmpty()) {
                return List.of();
            }
            List<ChatTurn> turns = new ArrayList<>(rows.size());
            for (AiChatHistory row : rows) {
                turns.add(new ChatTurn(row.getMsgRole(), row.getContent()));
            }
            Collections.reverse(turns);
            return trim(turns);
        } catch (Exception e) {  // noqa: BLE001 - 查库失败同样按无记忆处理
            log.warn("读取问答历史失败（按无记忆处理）: userId={}, err={}", userId, e.getMessage());
            return List.of();
        }
    }

    /** 把 Redis 里取出的 List 转成 ChatTurn（元素可能是 Map 或 ChatTurn） */
    private List<ChatTurn> toTurns(List<?> raw) {
        List<ChatTurn> turns = new ArrayList<>(raw.size());
        for (Object item : raw) {
            if (item instanceof ChatTurn turn) {
                turns.add(turn);
            } else if (item instanceof java.util.Map<?, ?> map) {
                Object role = map.get("role");
                Object content = map.get("content");
                if (role != null && content != null) {
                    turns.add(new ChatTurn(String.valueOf(role), String.valueOf(content)));
                }
            }
        }
        return trim(turns);
    }

    /**
     * 裁剪上下文窗口：先按条数（6 轮 = 12 条），再按总字符数
     * <p>
     * 顺序是从后往前保留（丢最早的）—— 刚刚说过的内容比很早以前的重要。
     * 按字符裁剪时可能把一轮问答截成"只有问没有答"，因此再保证不以 assistant 开头。
     */
    private List<ChatTurn> trim(List<ChatTurn> turns) {
        List<ChatTurn> result = new ArrayList<>(turns);
        // 1) 条数
        int maxMessages = MAX_TURNS * 2;
        if (result.size() > maxMessages) {
            result = new ArrayList<>(result.subList(result.size() - maxMessages, result.size()));
        }
        // 2) 字符数：从后往前累加
        int total = 0;
        int start = 0;
        for (int i = result.size() - 1; i >= 0; i--) {
            total += result.get(i).getContent() == null ? 0 : result.get(i).getContent().length();
            if (total > MAX_CHARS) {
                start = i + 1;
                break;
            }
        }
        if (start > 0) {
            result = new ArrayList<>(result.subList(start, result.size()));
        }
        // 3) 别让窗口以 assistant 开头（那样模型会看到一句没头没尾的回答）
        if (!result.isEmpty() && "assistant".equals(result.get(0).getRole())) {
            result.remove(0);
        }
        return result;
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_MESSAGE_CHARS ? text : text.substring(0, MAX_MESSAGE_CHARS);
    }

    /** 一轮对话里的一条消息（与 Python 侧 ChatTurn 字段一致：role / content） */
    public record ChatTurn(String role, String content) {
        public String getRole() {
            return role;
        }

        public String getContent() {
            return content;
        }
    }
}

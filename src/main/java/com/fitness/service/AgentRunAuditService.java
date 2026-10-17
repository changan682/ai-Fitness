package com.fitness.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitness.dto.AiChatResponse;
import com.fitness.entity.AiAgentRun;
import com.fitness.repository.AiAgentRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent 运行审计 —— 把「系统为这个问题做了什么」落库。
 *
 * <h3>为什么单独一个 Service</h3>
 * 审计是横切关注点，且有一个铁律：<b>审计写失败绝不能影响用户拿到回答</b>。
 * 把它收在一个类里，才能把「全部 try/catch + 只记 warn」这条纪律写在一处，
 * 而不是散落在 {@code AiProxyService} 的各个分支里。
 *
 * <h3>为什么失败也要记</h3>
 * 只记成功的审计是有偏的：出问题时你看到的全是"正常完成"的记录，
 * 真正需要排查的失败轮次反而不在表里。所以 Python 超时、验签失败、
 * 参数错误这类"根本没跑起来"的情况也要留一行（{@code stopped_by=error}）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRunAuditService {

    private static final int MAX_TOOLS_USED_CHARS = 255;
    private static final int MAX_QUESTION_CHARS = 2000;

    private final AiAgentRunRepository repository;
    private final ObjectMapper objectMapper;

    /** 成功路径：从响应里取步骤与元信息 */
    public void record(Long userId, String sessionId, String question,
                       AiChatResponse response, long latencyMs) {
        if (userId == null) {
            return;
        }
        try {
            Map<String, Object> agent = response == null ? null : response.getAgent();
            List<Map<String, Object>> steps = response == null ? null : response.getSteps();

            AiAgentRun row = AiAgentRun.builder()
                    .userId(userId)
                    .sessionId(sessionId)
                    .question(truncate(question))
                    .stepsJson(writeJson(steps))
                    .toolsUsed(collectTools(steps))
                    .stepsUsed(intOf(agent, "stepsUsed", steps == null ? 0 : steps.size()))
                    .tokens(intOf(agent, "tokens", 0))
                    .latencyMs(agent == null ? latencyMs : longOf(agent, "latencyMs", latencyMs))
                    .stoppedBy(stringOf(agent, "stoppedBy", "completed"))
                    .dataSource(response == null ? null : response.getDataSource())
                    .degraded(Boolean.TRUE.equals(response == null ? null : response.getDegraded()) ? 1 : 0)
                    .build();
            repository.save(row);
        } catch (Exception e) {  // noqa: BLE001 - 审计失败不影响回答
            log.warn("Agent 审计落库失败（已忽略，不影响回答）: userId={}, err={}",
                    userId, e.getMessage());
        }
    }

    /** 失败路径：Python 不可达 / 超时 / 参数错误 / 兜底文案 */
    public void recordFailure(Long userId, String sessionId, String question,
                              long latencyMs, int errorCode, String errorMessage) {
        if (userId == null) {
            return;
        }
        try {
            AiAgentRun row = AiAgentRun.builder()
                    .userId(userId)
                    .sessionId(sessionId)
                    .question(truncate(question))
                    .stepsJson("[]")
                    .toolsUsed("")
                    .stepsUsed(0)
                    .tokens(0)
                    .latencyMs(latencyMs)
                    .stoppedBy("error")
                    .dataSource("none")
                    .degraded(1)
                    .build();
            repository.save(row);
            log.info("Agent 运行失败已留审计: userId={}, code={}, msg={}", userId, errorCode, errorMessage);
        } catch (Exception e) {  // noqa: BLE001
            log.warn("Agent 失败审计落库失败（已忽略）: userId={}, err={}", userId, e.getMessage());
        }
    }

    // ==================== 内部 ====================

    private String writeJson(List<Map<String, Object>> steps) {
        if (steps == null) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(steps);
        } catch (Exception e) {
            // 审计要的是原貌，序列化失败就退化为字符串
            return String.valueOf(steps);
        }
    }

    /** 把步骤里出现过的工具名去重拼起来（留空表示没用工具），便于按工具聚合查询 */
    private String collectTools(List<Map<String, Object>> steps) {
        if (steps == null || steps.isEmpty()) {
            return "";
        }
        Set<String> names = new LinkedHashSet<>();
        for (Map<String, Object> step : steps) {
            Object tool = step == null ? null : step.get("tool");
            if (tool != null && !String.valueOf(tool).isBlank()) {
                names.add(String.valueOf(tool).trim());
            }
        }
        String joined = String.join(",", names);
        return joined.length() <= MAX_TOOLS_USED_CHARS
                ? joined : joined.substring(0, MAX_TOOLS_USED_CHARS);
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_QUESTION_CHARS ? text : text.substring(0, MAX_QUESTION_CHARS);
    }

    private static int intOf(Map<String, Object> map, String key, int fallback) {
        Object value = map == null ? null : map.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long longOf(Map<String, Object> map, String key, long fallback) {
        Object value = map == null ? null : map.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? fallback : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String stringOf(Map<String, Object> map, String key, String fallback) {
        Object value = map == null ? null : map.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        return String.valueOf(value);
    }
}

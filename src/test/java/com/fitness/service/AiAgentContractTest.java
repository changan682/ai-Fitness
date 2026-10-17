package com.fitness.service;

import com.fitness.client.AiPythonClient;
import com.fitness.config.AiProperties;
import com.fitness.dto.AiChatRequest;
import com.fitness.dto.AiChatResponse;
import com.fitness.dto.ai.PyChatData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Agent 响应的**跨语言键名契约**测试。
 *
 * <h3>为什么必须单独测这一层</h3>
 * {@code steps} / {@code agent} / {@code tool_calls} 在 Python 与 Java 两侧都是
 * <b>未类型化的 Map/List</b>，Jackson 的 {@code @JsonProperty} 对它们不起作用 ——
 * 键名会原样透传。于是极易出现：Python 发 {@code elapsed_ms}、前端读 {@code elapsedMs}
 * 拿到 {@code undefined}，<b>链路全通、界面空白、日志无错</b>。
 * <p>
 * 阶段二真实联调时就踩了这个：{@code stoppedBy} 读出来是 {@code undefined}，
 * 「停止原因」整块不显示。因此用断言把它钉死。
 */
class AiAgentContractTest {

    private AiPythonClient client;
    private AiChatSessionService chatSession;
    private AiProxyService proxy;

    @BeforeEach
    void setUp() {
        client = mock(AiPythonClient.class);
        chatSession = mock(AiChatSessionService.class);
        proxy = new AiProxyService(client, new AiProperties(), chatSession);

        when(chatSession.normalizeSessionId(any())).thenReturn("3f1c8b9e-0000-4000-8000-000000000000");
        when(chatSession.load(any(), anyString())).thenReturn(List.of());
    }

    /** 构造一份「Python 视角」的数据：键名全是 snake_case */
    private static PyChatData snakeCaseData() {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("step", 1);
        step.put("tool", "get_weekly_stats");
        step.put("arguments", Map.of("weekStart", "2026-09-14"));
        step.put("ok", true);
        step.put("summary", "训练 4 天 / 容量 18650kg");
        step.put("elapsed_ms", 12);
        step.put("error", null);

        Map<String, Object> toolCall = new LinkedHashMap<>();
        toolCall.put("name", "search_knowledge");
        toolCall.put("ok", true);
        toolCall.put("knowledge_used", true);
        toolCall.put("best_score", 0.7512);
        toolCall.put("error", null);

        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("steps_used", 2);
        agent.put("tokens", 4210);
        agent.put("latency_ms", 6240);
        agent.put("stopped_by", "completed");
        agent.put("max_steps", 5);

        PyChatData data = new PyChatData();
        data.setQuestion("我上周练得怎么样");
        data.setAnswer("练了 4 天，总容量 18650kg");
        data.setSources(List.of());
        data.setGeneratedAt("2026-09-20T21:00:00");
        data.setDataSource("agent");
        data.setDegraded(false);
        data.setKnowledgeUsed(false);
        data.setSteps(new ArrayList<>(List.of(step)));
        data.setToolCalls(new ArrayList<>(List.of(toolCall)));
        data.setAgent(agent);
        return data;
    }

    @Test
    @DisplayName("agent 元信息：snake_case 必须被翻译成前端约定的 camelCase")
    void agentMetaIsCamelCased() {
        when(client.agentChat(any())).thenReturn(snakeCaseData());

        AiChatRequest request = new AiChatRequest();
        request.setQuestion("我上周练得怎么样");
        AiChatResponse response = proxy.agentChat(36L, request);

        Map<String, Object> agent = response.getAgent();
        assertThat(agent).isNotNull();
        // 这四个键就是前端 AgentMeta 类型读的字段；缺一个界面就少一块信息
        assertThat(agent).containsEntry("stepsUsed", 2);
        assertThat(((Number) agent.get("latencyMs")).longValue()).isEqualTo(6240L);
        assertThat(agent).containsEntry("stoppedBy", "completed");
        assertThat(agent).containsEntry("maxSteps", 5);
        // 原始 snake_case 键不应残留（否则前端两种写法都能读到，反而更乱）
        assertThat(agent).doesNotContainKeys("steps_used", "latency_ms", "stopped_by", "max_steps");
    }

    @Test
    @DisplayName("steps：elapsed_ms → elapsedMs，其余字段原样保留")
    void stepKeysAreCamelCased() {
        when(client.agentChat(any())).thenReturn(snakeCaseData());

        AiChatRequest request = new AiChatRequest();
        request.setQuestion("我上周练得怎么样");
        AiChatResponse response = proxy.agentChat(36L, request);

        List<Map<String, Object>> steps = response.getSteps();
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0)).containsEntry("elapsedMs", 12);
        assertThat(steps.get(0)).containsEntry("tool", "get_weekly_stats");
        assertThat(steps.get(0)).containsEntry("summary", "训练 4 天 / 容量 18650kg");
        // 未登记的键不能被吞掉（未来 Python 加字段时前端仍能拿到）
        assertThat(steps.get(0)).containsKeys("step", "arguments", "ok", "error");
        assertThat(steps.get(0)).doesNotContainKey("elapsed_ms");
    }

    @Test
    @DisplayName("tool_calls：knowledge_used / best_score 也要翻译")
    void toolCallKeysAreCamelCased() {
        when(client.agentChat(any())).thenReturn(snakeCaseData());

        AiChatRequest request = new AiChatRequest();
        request.setQuestion("深蹲怎么做");
        AiChatResponse response = proxy.agentChat(36L, request);

        List<Map<String, Object>> toolCalls = response.getToolCalls();
        assertThat(toolCalls).hasSize(1);
        assertThat(toolCalls.get(0)).containsEntry("knowledgeUsed", true);
        assertThat(toolCalls.get(0)).containsEntry("bestScore", 0.7512);
        assertThat(toolCalls.get(0)).doesNotContainKeys("knowledge_used", "best_score");
    }

    @Test
    @DisplayName("Python 没给 knowledgeUsed 时按 steps 推断（避免前端拿不到判断依据）")
    void infersKnowledgeUsedWhenMissing() {
        PyChatData data = snakeCaseData();
        data.setKnowledgeUsed(null);
        // 让那一步看起来像知识库命中
        data.getSteps().get(0).put("knowledgeUsed", true);
        when(client.agentChat(any())).thenReturn(data);

        AiChatRequest request = new AiChatRequest();
        request.setQuestion("深蹲怎么做");
        AiChatResponse response = proxy.agentChat(36L, request);

        assertThat(response.getKnowledgeUsed()).isTrue();
    }

    @Test
    @DisplayName("对话记忆仍然生效：Agent 回答也要写回会话")
    void appendsTurnToMemory() {
        when(client.agentChat(any())).thenReturn(snakeCaseData());

        AiChatRequest request = new AiChatRequest();
        request.setQuestion("我上周练得怎么样");
        proxy.agentChat(36L, request);

        // 不写入的话，用户"看到回答却下一句就失忆"
        org.mockito.Mockito.verify(chatSession).appendTurn(
                eq(36L), anyString(), eq("我上周练得怎么样"),
                anyString(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("响应里带上 sessionId（前端要靠它接下一轮）")
    void returnsSessionId() {
        when(client.agentChat(any())).thenReturn(snakeCaseData());

        AiChatRequest request = new AiChatRequest();
        request.setQuestion("我上周练得怎么样");
        AiChatResponse response = proxy.agentChat(36L, request);

        assertThat(response.getSessionId()).isEqualTo("3f1c8b9e-0000-4000-8000-000000000000");
        assertThat(response.getGeneratedAt()).isNotNull();
    }

    @Test
    @DisplayName("sources 的分数口径必须透传（cosine / heuristic 不可混淆）")
    void passesThroughScoreType() {
        PyChatData data = snakeCaseData();
        PyChatData.Source source = new PyChatData.Source();
        source.setCategory("动作要领");
        source.setTitle("深蹲要领");
        source.setContent("…");
        source.setScore(new BigDecimal("0.7512"));
        source.setScoreType("cosine");
        data.setSources(List.of(source));
        when(client.agentChat(any())).thenReturn(data);

        AiChatRequest request = new AiChatRequest();
        request.setQuestion("深蹲怎么做");
        AiChatResponse response = proxy.agentChat(36L, request);

        assertThat(response.getSources()).hasSize(1);
        assertThat(response.getSources().get(0).getScoreType()).isEqualTo("cosine");
    }
}

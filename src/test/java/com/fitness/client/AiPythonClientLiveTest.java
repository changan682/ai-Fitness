package com.fitness.client;

import com.fitness.config.RestClientConfig;
import com.fitness.dto.ai.PyAgentHealthData;
import com.fitness.dto.ai.PyChatData;
import com.fitness.dto.ai.PyChatRequest;
import com.fitness.dto.ai.PyRecommendData;
import com.fitness.dto.ai.PyRecommendRequest;
import com.fitness.dto.ai.PySummaryData;
import com.fitness.dto.ai.PySummaryRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨语言链路实测 — Java → Python FastAPI（第 4 周验证项）
 * <p>
 * <b>为什么默认不跑</b>：本测试需要真实的 Python 服务在 127.0.0.1:8000 运行，
 * 若纳入默认构建，CI 或同事本地没起 Python 时会红，反而掩盖真实问题。
 * 因此用环境变量开关控制，联调时显式开启：
 *
 * <pre>
 *   # 1. 先启动 Python
 *   cd python-agent
 *   E:\Anaconde\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000
 *
 *   # 2. 再开启开关跑本测试
 *   $env:AI_LIVE_TEST="true"; mvn -o test -Dtest=AiPythonClientLiveTest
 * </pre>
 *
 * 这个测试是「第4周交付物」的机器可验证形式：它证明 RestClient 的
 * baseUrl / 超时 / 信封拆解 / traceId 注入在真实 HTTP 上都能跑通。
 */
@EnabledIfEnvironmentVariable(named = "AI_LIVE_TEST", matches = "true",
        disabledReason = "需要 Python FastAPI 服务运行在 127.0.0.1:8000，设置 AI_LIVE_TEST=true 后启用")
@SpringBootTest(classes = {RestClientConfig.class, AiPythonClient.class})
@DisplayName("跨语言实测：Java RestClient → Python FastAPI")
class AiPythonClientLiveTest {

    @Autowired
    private AiPythonClient client;

    @Test
    @DisplayName("Python 健康检查应返回 UP")
    void healthShouldBeUp() {
        PyAgentHealthData health = client.health();
        assertNotNull(health, "Python 服务不可达，请先启动 python-agent");
        assertEquals("UP", health.getStatus());
    }

    @Test
    @DisplayName("训练总结应真实贯通：返回的容量与传入记录一致")
    void summaryShouldComputeVolumeFromPayload() {
        PySummaryRequest request = PySummaryRequest.builder()
                .userId(1001L)
                .date("2026-07-30")
                .records(List.of(
                        record("杠铃卧推", 4, 10, "60.0", 8),
                        record("上斜哑铃卧推", 3, 12, "25.0", 7),
                        record("绳索夹胸", 3, 15, "15.0", 6)))
                .comparison(Map.of("previousDate", "2026-07-23", "volumeChangePct", 5.3))
                .build();

        PySummaryData data = client.generateSummary(request);

        assertNotNull(data, "Python 未返回 data");
        assertNotNull(data.getSummary(), "summary 为空");
        assertFalse(data.getSummary().isBlank(), "summary 为空白");
        // 4×10×60 + 3×12×25 + 3×15×15 = 2400 + 900 + 675 = 3975
        // Python 侧 Mock 按入参真实计算（与 Java TrainingRecord.volume 同公式），
        // 因此断言这个数字能反证「业务数据确实跨语言传过去了」，而不是返回一段写死的文案。
        assertTrue(data.getSummary().contains("3975"),
                "总结中应包含按入参算出的总容量 3975，实际返回：\n" + data.getSummary());
        // 中文断言：确认整条 HTTP 链路（Python UTF-8 → RestClient → Jackson）没有把中文解成乱码。
        // 只断言数字是查不出编码问题的 —— 乱码时数字依然正确。
        assertTrue(data.getSummary().contains("训练"),
                "总结中文内容疑似乱码，实际返回：\n" + data.getSummary());
        assertNotNull(data.getGeneratedAt(), "generated_at 不应为空（Java 侧按 ISO8601 解析）");
    }

    @Test
    @DisplayName("动作推荐应按肌群返回结构化结果")
    void recommendShouldReturnStructuredActions() {
        PyRecommendRequest request = new PyRecommendRequest();
        request.setTargetMuscle("胸");
        request.setEquipment(List.of("哑铃", "杠铃"));
        request.setCount(5);

        PyRecommendData data = client.recommend(request);

        assertNotNull(data.getRecommendations());
        assertFalse(data.getRecommendations().isEmpty(), "推荐列表不应为空");
        assertTrue(data.getRecommendations().size() <= 5, "不应超过请求的 count");
        for (PyRecommendData.Recommendation item : data.getRecommendations()) {
            assertNotNull(item.getActionName(), "动作名不应为空");
            assertTrue(item.getActionName().chars().anyMatch(c -> c > 127),
                    "动作名应为中文（乱码时会退化成 ASCII 或问号）: " + item.getActionName());
            assertNotNull(item.getNotes(), "动作要点不应为空");
        }
    }

    @Test
    @DisplayName("RAG 问答应返回 Markdown 答案与引用来源")
    void chatShouldReturnAnswerWithSources() {
        PyChatRequest request = new PyChatRequest();
        request.setQuestion("深蹲时膝盖可以超过脚尖吗？");
        request.setUserId(1001L);

        PyChatData data = client.chat(request);

        assertNotNull(data.getAnswer());
        assertFalse(data.getAnswer().isBlank());
        assertNotNull(data.getSources());
        assertFalse(data.getSources().isEmpty(), "RAG 应答应带回引用来源");
        assertNotNull(data.getSources().get(0).getTitle());
    }

    private PySummaryRequest.RecordBrief record(String action, int sets, int reps, String weight, int rpe) {
        return PySummaryRequest.RecordBrief.builder()
                .action(action)
                .sets(sets)
                .reps(reps)
                .weight(new BigDecimal(weight))
                .rpe(rpe)
                .build();
    }
}

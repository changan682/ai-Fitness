package com.fitness.service;

import com.fitness.client.AiPythonClient;
import com.fitness.config.RestClientConfig;
import com.fitness.dto.AiChatRequest;
import com.fitness.dto.AiChatResponse;
import com.fitness.dto.AiRecommendRequest;
import com.fitness.dto.AiRecommendResponse;
import com.fitness.dto.ai.PySummaryData;
import com.fitness.dto.ai.PySummaryRequest;
import com.fitness.exception.BusinessException;
import com.fitness.exception.ErrorCode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Python 协议契约测试 — 用 JDK 自带 HttpServer 做桩，不依赖真实 Python 进程
 * <p>
 * <b>为什么值得单独做一个桩测试</b>：跨语言对接最危险的失败模式不是「连不上」，
 * 而是<b>连上了、HTTP 200、字段却是 null</b> —— 比如 Python 返回 {@code action_name}
 * 而 Java 写成 {@code actionName}，接口一片绿色，前端却全是空值。
 * 只断言「请求成功」的测试完全抓不到这类问题，因此这里对<b>每个字段</b>都做断言。
 * <p>
 * 覆盖三条 Java 独有的分支（真实 Python 跑起来也未必能稳定复现）：
 * <ol>
 *   <li>正常 200 + 完整信封 → 验证 snake_case → camelCase 映射</li>
 *   <li>HTTP 5xx → 6002（AI_RESPONSE_ERROR）</li>
 *   <li>HTTP 200 但 {@code success=false} → 6002 且带上 Python 的 message</li>
 * </ol>
 * 桩服务在静态初始化块里启动：必须早于 {@code @DynamicPropertySource} 求值，
 * 否则拿不到端口号。
 */
@SpringBootTest(classes = {RestClientConfig.class, AiPythonClient.class, AiProxyService.class},
        properties = {
                "ai.python.connect-timeout=2s",
                "ai.python.read-timeout=2s"
        })
@DisplayName("Python 协议契约：字段映射 + 5xx/业务失败分支")
class AiProtocolContractTest {

    /** 每个用例通过它切换桩的响应，避免为每种场景各起一个服务 */
    private static final AtomicReference<String> SCENARIO = new AtomicReference<>("ok");

    private static final HttpServer STUB = startStub();
    private static final int PORT = STUB.getAddress().getPort();

    @DynamicPropertySource
    static void aiBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("ai.python.base-url", () -> "http://127.0.0.1:" + PORT);
    }

    @Autowired
    private AiPythonClient client;

    @Autowired
    private AiProxyService proxyService;

    // ==================== 1. 正常路径：字段映射必须逐字段正确 ====================

    @Test
    @DisplayName("推荐接口：snake_case 响应必须完整映射为前端 camelCase 契约")
    void recommendFieldsShouldMapExactly() {
        SCENARIO.set("ok");

        AiRecommendRequest request = new AiRecommendRequest();
        request.setTargetMuscle("胸");
        request.setEquipment(List.of("哑铃"));
        request.setCount(1);

        AiRecommendResponse response = proxyService.recommend(request);

        assertNotNull(response.getGeneratedAt(), "generated_at 应被解析为 generatedAt");
        assertEquals(1, response.getRecommendations().size());

        AiRecommendResponse.Recommendation item = response.getRecommendations().get(0);
        // 逐字段断言：任何一处 @JsonProperty 写错都会让某个字段变成 null
        assertEquals("上斜哑铃卧推", item.getActionName());
        assertEquals("胸", item.getTargetMuscle());
        assertEquals("上胸", item.getFocusArea());
        assertEquals("3-4", item.getRecommendedSets());
        assertEquals("8-12", item.getRecommendedReps());
        assertEquals("进阶", item.getDifficulty());
        assertNotNull(item.getNotes());
        assertEquals(List.of("哑铃", "可调节凳"), item.getEquipment());
    }

    @Test
    @DisplayName("问答接口：answer 与 sources 应完整映射")
    void chatFieldsShouldMapExactly() {
        SCENARIO.set("ok");

        AiChatRequest request = new AiChatRequest();
        request.setQuestion("深蹲膝盖能超过脚尖吗？");

        AiChatResponse response = proxyService.chat(1001L, request);

        assertNotNull(response.getAnswer());
        assertTrue(response.getAnswer().contains("可以"), "answer 内容应原样透传");
        assertEquals(1, response.getSources().size());
        AiChatResponse.Source source = response.getSources().get(0);
        assertEquals("动作要领", source.getCategory());
        assertEquals("深蹲时膝盖与脚尖的位置关系", source.getTitle());
        assertNotNull(source.getContent());
        assertEquals(0, source.getScore().compareTo(new java.math.BigDecimal("0.94")));
        assertNotNull(response.getGeneratedAt());
    }

    @Test
    @DisplayName("总结接口：summary 与 ISO8601 时间应正确解析")
    void summaryFieldsShouldMapExactly() {
        SCENARIO.set("ok");

        PySummaryData data = client.generateSummary(PySummaryRequest.builder()
                .userId(1001L).date("2026-07-30").records(List.of()).build());

        assertTrue(data.getSummary().startsWith("## 🏋️"));
        // Python 返回 ISO8601（带 T），Java 侧必须能解析而不是退化成 now()
        assertNotNull(data.getGeneratedAt());
        assertTrue(data.getGeneratedAt().contains("T"), "协议约定为 ISO8601");
    }

    // ==================== 2. HTTP 5xx 分支 ====================

    @Test
    @DisplayName("Python 返回 5xx → 6002 且降级为兜底文案")
    void pythonServerErrorShouldMapTo6002WithFallback() {
        SCENARIO.set("500");

        AiRecommendRequest request = new AiRecommendRequest();
        request.setTargetMuscle("胸");
        request.setEquipment(List.of("哑铃"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> proxyService.recommend(request));
        assertEquals(ErrorCode.AI_RESPONSE_ERROR.getCode(), ex.getCode(),
                "5xx 应映射为 6002 而非 6001");
        assertFalse(ex.getMessage().contains("500"), "不应把 HTTP 状态码透给前端");
    }

    // ==================== 3. 业务失败分支（success=false） ====================

    @Test
    @DisplayName("HTTP 200 但 success=false → 6002，并保留 Python 的 message")
    void pythonBusinessFailureShouldMapTo6002() {
        SCENARIO.set("fail");

        // 客户端层应带上 Python 给的 message，便于服务端日志定位
        BusinessException ex = assertThrows(BusinessException.class,
                () -> client.generateSummary(PySummaryRequest.builder()
                        .userId(1001L).date("2026-07-30").records(List.of()).build()));
        assertEquals(ErrorCode.AI_RESPONSE_ERROR.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("知识库尚未初始化"),
                "客户端层应保留 Python 的业务 message，实际：" + ex.getMessage());
    }

    // ==================== 4. 入参错误分支（HTTP 4xx） ====================

    @Test
    @DisplayName("Python 返回 4xx → 9003 参数错误，且把 Python 的具体原因带给用户")
    void pythonClientErrorShouldMapToParamInvalidWithReason() {
        SCENARIO.set("400");

        AiRecommendRequest request = new AiRecommendRequest();
        request.setTargetMuscle("胸");
        request.setEquipment(List.of("哑铃"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> proxyService.recommend(request));

        // 4xx 是「入参有问题」，不是「AI 服务挂了」：
        // 若映射成 6002，withFallback 会把 msg 换成「AI 教练暂时走神了」，
        // 用户就永远看不到「照片看不清，请换一张」这种可执行的提示。
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode(),
                "4xx 应映射为 9003 而不是 6002");
        assertTrue(ex.getMessage().contains("无法判断"),
                "必须保留 Python 给的具体原因，实际：" + ex.getMessage());
        assertFalse(ex.getMessage().contains("走神"),
                "入参错误不得被替换成 AI 兜底文案");
    }

    @Test
    @DisplayName("data 为空的成功响应也应视为异常，避免前端拿到 null 白屏")
    void emptyDataShouldBeRejected() {
        SCENARIO.set("empty");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> client.generateSummary(PySummaryRequest.builder()
                        .userId(1001L).date("2026-07-30").records(List.of()).build()));
        assertEquals(ErrorCode.AI_RESPONSE_ERROR.getCode(), ex.getCode());
    }

    // ==================== 桩服务 ====================

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            // 三个接口共用同一个「场景分发」：否则某个接口漏接开关时，
            // 用例会拿到 200 而误判为「降级没生效」，属于典型的测试自伤。
            server.createContext("/agent/v1/summary", exchange -> handle(exchange, SUMMARY_OK));
            server.createContext("/agent/v1/recommend", exchange -> handle(exchange, RECOMMEND_OK));
            server.createContext("/agent/v1/chat", exchange -> handle(exchange, CHAT_OK));
            server.setExecutor(null);
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("启动桩服务失败", e);
        }
    }

    /** 按当前场景返回响应；默认返回该接口的正常载荷 */
    private static void handle(HttpExchange exchange, String okBody) throws IOException {
        switch (SCENARIO.get()) {
            case "500" -> respond(exchange, 500, "{\"detail\":\"internal error\"}");
            case "400" -> respond(exchange, 400, PARAM_ERROR_ENVELOPE);
            case "fail" -> respond(exchange, 200, FAIL_ENVELOPE);
            case "empty" -> respond(exchange, 200, EMPTY_ENVELOPE);
            default -> respond(exchange, 200, okBody);
        }
    }

    private static final String SUMMARY_OK = """
            {"success":true,"message":"ok","data":{
              "summary":"## 🏋️ 今日训练总结\\n\\n总容量 **3975kg**。",
              "generated_at":"2026-07-30T15:35:00"}}
            """;

    private static final String RECOMMEND_OK = """
            {"success":true,"message":"ok","data":{
              "recommendations":[{
                "action_name":"上斜哑铃卧推",
                "target_muscle":"胸",
                "focus_area":"上胸",
                "recommended_sets":"3-4",
                "recommended_reps":"8-12",
                "difficulty":"进阶",
                "notes":"凳角调至30-45度",
                "equipment":["哑铃","可调节凳"]
              }],
              "generated_at":"2026-07-30T15:36:00"}}
            """;

    private static final String CHAT_OK = """
            {"success":true,"message":"ok","data":{
              "question":"深蹲膝盖能超过脚尖吗？",
              "answer":"**简短回答：可以，但不是必须。**",
              "sources":[{"category":"动作要领","title":"深蹲时膝盖与脚尖的位置关系",
                          "content":"膝盖位置取决于身体比例…","score":0.94}],
              "generated_at":"2026-07-30T16:00:00"}}
            """;

    private static final String FAIL_ENVELOPE =
            "{\"success\":false,\"message\":\"知识库尚未初始化，请先执行知识导入脚本\",\"data\":null}";

    /**
     * Python 侧 4xx（入参业务校验失败）的真实形态：HTTP 400 + 统一信封。
     * 与 5xx、success=false 的关键区别是「原因在用户，不在服务」。
     */
    private static final String PARAM_ERROR_ENVELOPE =
            "{\"success\":false,\"message\":\"照片无法判断动作姿态，请上传一张清晰的全身照\",\"data\":null}";

    private static final String EMPTY_ENVELOPE =
            "{\"success\":true,\"message\":\"ok\",\"data\":null}";

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}

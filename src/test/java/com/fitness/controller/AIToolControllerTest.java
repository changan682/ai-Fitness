package com.fitness.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitness.config.CallbackProperties;
import com.fitness.dto.ai.ToolInvokeResult;
import com.fitness.exception.BusinessException;
import com.fitness.exception.ErrorCode;
import com.fitness.service.AgentToolService;
import com.fitness.util.HmacSignatureVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Agent 工具网关的安全测试 —— 这个接口在 JWT 白名单里，
 * <b>验签是它唯一的安全边界</b>，一旦失守就是对内网完全开放的写入口
 * （它带 userId，能读任意用户的训练/体测数据）。
 * <p>
 * 因此这里逐条覆盖：无签名、错签名、篡改 body、过期时间戳、未来时间戳。
 */
class AIToolControllerTest {

    private static final String SECRET = "test-tool-secret";

    private AgentToolService toolService;
    private AIToolController controller;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        CallbackProperties properties = new CallbackProperties();
        properties.setHmacSecret(SECRET);
        HmacSignatureVerifier verifier = new HmacSignatureVerifier(properties);

        toolService = mock(AgentToolService.class);
        controller = new AIToolController(verifier, objectMapper, toolService);
    }

    private static String body(String tool, long userId, Map<String, Object> args) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tool", tool);
            payload.put("user_id", userId);
            payload.put("args", args);
            return new ObjectMapper().writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sign(String timestamp, String rawBody) {
        return HmacSignatureVerifier.sign("POST", AIToolController.INVOKE_PATH, timestamp,
                rawBody.getBytes(StandardCharsets.UTF_8), SECRET);
    }

    private static String now() {
        return String.valueOf(System.currentTimeMillis());
    }

    // ==================== 防线：签名 ====================

    @Test
    @DisplayName("缺少签名头 → 9002，且不会触碰工具服务")
    void rejectsMissingSignature() throws Exception {
        String raw = body("get_weekly_stats", 36L, Map.of());
        assertThatThrownBy(() -> controller.invoke(now(), null, raw))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ErrorCode.SIGNATURE_INVALID.getCode()));
        verifyNoInteractions(toolService);
    }

    @Test
    @DisplayName("签名错误 → 9002")
    void rejectsBadSignature() {
        String raw = body("get_weekly_stats", 36L, Map.of());
        String wrong = HmacSignatureVerifier.sign("POST", AIToolController.INVOKE_PATH,
                now(), raw.getBytes(StandardCharsets.UTF_8), "wrong-secret");

        assertThatThrownBy(() -> controller.invoke(now(), wrong, raw))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(toolService);
    }

    @Test
    @DisplayName("**篡改 body 的 userId** → 验签失败（越权读别人数据的核心防线）")
    void rejectsTamperedUserId() {
        String original = body("get_weekly_stats", 36L, Map.of());
        String timestamp = now();
        String signature = sign(timestamp, original);

        // 攻击者把 userId 改成别人的，签名不变
        String tampered = body("get_weekly_stats", 1L, Map.of());

        assertThatThrownBy(() -> controller.invoke(timestamp, signature, tampered))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ErrorCode.SIGNATURE_INVALID.getCode()));
        verifyNoInteractions(toolService);
    }

    @Test
    @DisplayName("时间戳超出 ±5 分钟窗口 → 9002（防重放）")
    void rejectsStaleTimestamp() {
        long stale = System.currentTimeMillis() - 10 * 60 * 1000L;   // 10 分钟前
        String timestamp = String.valueOf(stale);
        String raw = body("get_weekly_stats", 36L, Map.of());
        String signature = sign(timestamp, raw);

        assertThatThrownBy(() -> controller.invoke(timestamp, signature, raw))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ErrorCode.SIGNATURE_INVALID.getCode()));
        verifyNoInteractions(toolService);
    }

    @Test
    @DisplayName("来自未来的时间戳同样拒绝（时钟不同步也不能放宽）")
    void rejectsFutureTimestamp() {
        String timestamp = String.valueOf(System.currentTimeMillis() + 10 * 60 * 1000L);
        String raw = body("get_weekly_stats", 36L, Map.of());
        String signature = sign(timestamp, raw);

        assertThatThrownBy(() -> controller.invoke(timestamp, signature, raw))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(toolService);
    }

    @Test
    @DisplayName("时间戳非数字 → 9002")
    void rejectsNonNumericTimestamp() {
        String raw = body("get_weekly_stats", 36L, Map.of());
        assertThatThrownBy(() -> controller.invoke("not-a-number", sign("x", raw), raw))
                .isInstanceOf(BusinessException.class);
    }

    // ==================== 正常路径 ====================

    @Test
    @DisplayName("签名正确 → 解析 body 并调用工具（userId 取自签名保护的 body）")
    void acceptsValidRequest() throws Exception {
        String timestamp = now();
        String raw = body("get_weekly_stats", 36L, Map.of("weekStart", "2026-09-14"));
        String signature = sign(timestamp, raw);

        when(toolService.invoke(eq("get_weekly_stats"), eq(36L), anyMap()))
                .thenReturn(ToolInvokeResult.of(Map.of("trainingDays", 4),
                        "get_weekly_stats", 12L, null));

        var result = controller.invoke(timestamp, signature, raw);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().getData()).isEqualTo(Map.of("trainingDays", 4));
        verify(toolService).invoke(eq("get_weekly_stats"), eq(36L), anyMap());
    }

    @Test
    @DisplayName("userId 只从 body 取，不接受其它来源")
    void userIdComesFromSignedBody() throws Exception {
        String timestamp = now();
        String raw = body("get_body_trend", 7L, Map.of("startDate", "2026-09-01", "endDate", "2026-09-20"));
        String signature = sign(timestamp, raw);

        when(toolService.invoke(any(), anyLong(), anyMap()))
                .thenReturn(ToolInvokeResult.of(Map.of(), "get_body_trend", 5L, null));

        controller.invoke(timestamp, signature, raw);
        verify(toolService).invoke(eq("get_body_trend"), eq(7L), anyMap());
    }

    @Test
    @DisplayName("路径常量必须与 Python 侧一致（签名原文含 path）")
    void invokePathIsStable() {
        // 改这个值会让所有工具调用验签失败，且只在联调时暴露 —— 用测试钉住
        assertThat(AIToolController.INVOKE_PATH).isEqualTo("/api/ai/tool/invoke");
    }
}

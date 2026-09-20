package com.fitness.controller;

import com.fitness.cache.DistributedLockUtil;
import com.fitness.cache.RedisCacheService;
import com.fitness.cache.TokenBlacklistService;
import com.fitness.config.CallbackProperties;
import com.fitness.entity.WeeklyPlan;
import com.fitness.exception.GlobalExceptionHandler;
import com.fitness.repository.WeeklyPlanRepository;
import com.fitness.util.HmacSignatureVerifier;
import com.fitness.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 周计划回调接口测试 —— 规范 8.1（Python → Java，内网免鉴权 + HMAC 验签）
 *
 * <h3>这个接口为什么值得重点测</h3>
 * 它在 JWT 白名单里（调用方是内网的 Python，没有用户 Token），
 * 也就是说**验签是唯一的安全边界**。一旦验签被绕过或写错，
 * 任何能访问内网的人都可以伪造「AI 周计划」写进用户表。
 * 因此这里逐条锁死四道防线：时间戳窗口 → 签名（覆盖 body）→ Redis 幂等锁 → DB 唯一索引兜底。
 *
 * <h3>签名怎么来的</h3>
 * 用例里用生产代码 {@link HmacSignatureVerifier#sign} 生成签名，
 * 而**算法本身**由 {@code HmacSignatureVerifierTest} 的跨语言黄金向量锁死
 * （那组向量是 Python 侧算出来的）。两层测试合起来才能证明「两端一致且接口校验正确」。
 */
@WebMvcTest(controllers = AICallbackController.class)
@Import({GlobalExceptionHandler.class, HmacSignatureVerifier.class})
@EnableConfigurationProperties(CallbackProperties.class)
@ActiveProfiles("test")
@DisplayName("周计划回调：验签 / 防重放 / 幂等")
class AICallbackControllerTest {

    private static final String PATH = AICallbackController.WEEKLY_PLAN_PATH;
    private static final Long USER_ID = 1001L;
    private static final String TASK_ID = "weekly-plan-1001-2026-08-03";

    private static final String BODY = """
            {"taskId":"weekly-plan-1001-2026-08-03","userId":1001,"weekStart":"2026-08-03",
             "suggestionText":"## 📅 下周计划\\n\\n深蹲加 2.5kg",
             "weekSummary":{"trainingDays":5,"totalVolume":18500.5,"avgRpe":7.5}}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CallbackProperties callbackProperties;

    @MockBean
    private WeeklyPlanRepository weeklyPlanRepository;

    @MockBean
    private DistributedLockUtil distributedLockUtil;

    @MockBean
    private RedisCacheService redisCacheService;

    // WebConfig（WebMvcConfigurer）会被 @WebMvcTest 扫进来，它依赖 JwtInterceptor → 这里补齐依赖。
    // 注意：回调路径在 JWT 白名单里，所以本类**不带 Authorization 头**发请求也应通过
    //（validateToken 默认返回 false，若白名单失效就会得到 9001 —— 这正是第一条用例要证明的）。
    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private TokenBlacklistService tokenBlacklistService;

    @BeforeEach
    void setUp() {
        given(distributedLockUtil.tryLock(anyString(), anyLong())).willReturn("lock-value");
        given(weeklyPlanRepository.findByTaskId(anyString())).willReturn(Optional.empty());
        given(weeklyPlanRepository.findByUserIdAndWeekStart(anyLong(), any())).willReturn(Optional.empty());
        given(weeklyPlanRepository.save(any(WeeklyPlan.class))).willAnswer(inv -> {
            WeeklyPlan plan = inv.getArgument(0);
            plan.setId(6001L);
            return plan;
        });
        given(jwtUtil.validateToken(anyString())).willReturn(false);
    }

    // ==================== 正常路径 ====================

    @Test
    @DisplayName("合法回调（无 Authorization 头）→ code=0、写入 t_weekly_plan、失效周统计缓存")
    void validCallbackShouldPersist() throws Exception {
        mockMvc.perform(signedRequest(BODY, currentTimestamp()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("周计划已接收"))
                .andExpect(jsonPath("$.data.planId").value(6001));

        // 落库字段必须完整（AI 建议 + taskId 作为幂等键）
        org.mockito.ArgumentCaptor<WeeklyPlan> captor =
                org.mockito.ArgumentCaptor.forClass(WeeklyPlan.class);
        verify(weeklyPlanRepository).save(captor.capture());
        WeeklyPlan saved = captor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals(TASK_ID, saved.getTaskId());
        org.junit.jupiter.api.Assertions.assertEquals(USER_ID, saved.getUserId());
        org.junit.jupiter.api.Assertions.assertEquals(LocalDate.parse("2026-08-03"), saved.getWeekStart());
        org.junit.jupiter.api.Assertions.assertTrue(saved.getSuggestionText().contains("下周计划"));
        org.junit.jupiter.api.Assertions.assertTrue(saved.getWeekSummary().contains("trainingDays"));

        // 规范 8.1 第 4 步：失效 Redis 周统计缓存
        verify(redisCacheService).delete(anyString());
    }

    @Test
    @DisplayName("周统计任务已建过占位行时 → 复用同一行补写 AI 建议，而不是新增一行")
    void existingStatsRowShouldBeUpdatedInPlace() throws Exception {
        WeeklyPlan existing = WeeklyPlan.builder()
                .id(5001L).userId(USER_ID)
                .weekStart(LocalDate.parse("2026-08-03"))
                .taskId("weekly-stats-1001-2026-08-03")     // 周日 20:00 统计任务写的占位 taskId
                .suggestionText("")
                .build();
        given(weeklyPlanRepository.findByUserIdAndWeekStart(anyLong(), any()))
                .willReturn(Optional.of(existing));
        // 复用已有行时不应改动主键：让 save 原样返回
        given(weeklyPlanRepository.save(any(WeeklyPlan.class)))
                .willAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(signedRequest(BODY, currentTimestamp()))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.planId").value(5001));

        org.mockito.ArgumentCaptor<WeeklyPlan> captor =
                org.mockito.ArgumentCaptor.forClass(WeeklyPlan.class);
        verify(weeklyPlanRepository).save(captor.capture());
        // 同一用户同一周只应有一行：否则 /api/v1/weekly-plan/latest 取哪一行是不确定的
        org.junit.jupiter.api.Assertions.assertEquals(5001L, captor.getValue().getId());
        org.junit.jupiter.api.Assertions.assertEquals(TASK_ID, captor.getValue().getTaskId());
    }

    // ==================== 防线 1：时间戳窗口 ====================

    @Test
    @DisplayName("时间戳超出 ±5 分钟 → 9002（防重放）")
    void staleTimestampShouldBeRejected() throws Exception {
        long stale = System.currentTimeMillis() - HmacSignatureVerifier.MAX_TIMESTAMP_SKEW_MS - 60_000;

        mockMvc.perform(signedRequest(BODY, String.valueOf(stale)))
                .andExpect(jsonPath("$.code").value(9002));

        verify(weeklyPlanRepository, never()).save(any());
    }

    @Test
    @DisplayName("缺少 X-Timestamp → 9002")
    void missingTimestampShouldBeRejected() throws Exception {
        byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Signature", sign(body, currentTimestamp()))
                        .content(body))
                .andExpect(jsonPath("$.code").value(9002));
    }

    // ==================== 防线 2：签名（覆盖 body）====================

    @Test
    @DisplayName("签了 A 却发 B（body 被篡改）→ 9002，且不落库")
    void tamperedBodyShouldBeRejected() throws Exception {
        String timestamp = currentTimestamp();
        String signatureOfOriginal = sign(BODY.getBytes(StandardCharsets.UTF_8), timestamp);
        String tampered = BODY.replace("\"userId\":1001", "\"userId\":9999");

        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Timestamp", timestamp)
                        .header("X-Signature", signatureOfOriginal)
                        .content(tampered.getBytes(StandardCharsets.UTF_8)))
                .andExpect(jsonPath("$.code").value(9002));

        verify(weeklyPlanRepository, never()).save(any());
    }

    @Test
    @DisplayName("签名错误 / 缺失 → 9002")
    void wrongOrMissingSignatureShouldBeRejected() throws Exception {
        String timestamp = currentTimestamp();
        byte[] body = BODY.getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Timestamp", timestamp)
                        .header("X-Signature", "deadbeef")
                        .content(body))
                .andExpect(jsonPath("$.code").value(9002));

        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Timestamp", timestamp)
                        .content(body))
                .andExpect(jsonPath("$.code").value(9002));
    }

    // ==================== 防线 3/4：幂等 ====================

    @Test
    @DisplayName("同一 taskId 重复回调 → 6001「任务已处理」，不重复落库")
    void duplicateCallbackShouldReturn6001() throws Exception {
        given(weeklyPlanRepository.findByTaskId(TASK_ID))
                .willReturn(Optional.of(WeeklyPlan.builder().id(6001L).taskId(TASK_ID).build()));

        mockMvc.perform(signedRequest(BODY, currentTimestamp()))
                .andExpect(jsonPath("$.code").value(6001))
                .andExpect(jsonPath("$.msg").value("任务已处理（幂等返回）"));

        verify(weeklyPlanRepository, never()).save(any());
    }

    @Test
    @DisplayName("并发回调（抢不到幂等锁）→ 6001，且不再查库/落库")
    void concurrentCallbackShouldReturn6001() throws Exception {
        given(distributedLockUtil.tryLock(anyString(), anyLong())).willReturn(null);

        mockMvc.perform(signedRequest(BODY, currentTimestamp()))
                .andExpect(jsonPath("$.code").value(6001));

        verify(weeklyPlanRepository, never()).save(any());
    }

    @Test
    @DisplayName("落库时撞唯一索引（Redis 锁失效的兜底）→ 6001 而不是 9999")
    void dataIntegrityViolationShouldReturn6001() throws Exception {
        given(weeklyPlanRepository.save(any(WeeklyPlan.class)))
                .willThrow(new org.springframework.dao.DataIntegrityViolationException("uk_task_id"));

        mockMvc.perform(signedRequest(BODY, currentTimestamp()))
                .andExpect(jsonPath("$.code").value(6001));
    }

    // ==================== 入参校验 ====================

    @Test
    @DisplayName("缺少 suggestionText → 9003（不写入半条脏数据）")
    void missingSuggestionTextShouldReturn9003() throws Exception {
        String body = """
                {"taskId":"weekly-plan-1001-2026-08-03","userId":1001,"weekStart":"2026-08-03"}
                """;
        mockMvc.perform(signedRequest(body, currentTimestamp()))
                .andExpect(jsonPath("$.code").value(9003));

        verify(weeklyPlanRepository, never()).save(any());
    }

    @Test
    @DisplayName("weekStart 格式非法 → 9003，且提示里说明期望格式")
    void invalidWeekStartShouldReturn9003() throws Exception {
        String body = BODY.replace("\"2026-08-03\"", "\"2026/08/03\"");

        mockMvc.perform(signedRequest(body, currentTimestamp()))
                .andExpect(jsonPath("$.code").value(9003))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("yyyy-MM-dd")));
    }

    // ==================== 辅助 ====================

    private String currentTimestamp() {
        return String.valueOf(System.currentTimeMillis());
    }

    /** 用生产代码的密钥给原始 body 签名（密钥来自 application-test.yml 的 callback.hmac-secret） */
    private String sign(byte[] body, String timestamp) {
        return HmacSignatureVerifier.sign(
                "POST", PATH, timestamp, body, callbackProperties.getHmacSecret());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder signedRequest(
            String body, String timestamp) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Timestamp", timestamp)
                .header("X-Signature", sign(bytes, timestamp))
                .content(bytes);
    }
}

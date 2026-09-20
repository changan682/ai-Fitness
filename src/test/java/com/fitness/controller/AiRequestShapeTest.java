package com.fitness.controller;

import com.fitness.cache.TokenBlacklistService;
import com.fitness.exception.GlobalExceptionHandler;
import com.fitness.service.AiProxyService;
import com.fitness.service.AiSummaryService;
import com.fitness.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 接口的「请求形态错误」回归测试
 * <p>
 * <b>为什么需要它</b>：这两个 bug 都不是业务逻辑问题，而是「异常没被正确归类」——
 * 调用方漏传文件、或压根没用 multipart，最终都落到兜底分支返回
 * {@code 9999 系统内部错误}，把「你用错了接口」误导成「服务端挂了」。
 * 排查方向会被彻底带偏，而 {@code mvn test} 在补这个测试之前是查不出来的：
 * 原有的 @WebMvcTest 只覆盖了 JSON 接口，没碰过 multipart 路径。
 * <p>
 * 这里刻意 {@code @Import(GlobalExceptionHandler.class)}：@WebMvcTest 虽会自动扫描
 * {@code @ControllerAdvice}，但显式导入能让「测的就是生产那个处理器」这件事一目了然。
 */
@WebMvcTest(controllers = AIController.class)
@Import(GlobalExceptionHandler.class)
@ActiveProfiles("test")
@DisplayName("AI 接口的请求形态错误应返回 9003，而不是 9999")
class AiRequestShapeTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AiSummaryService aiSummaryService;

    @MockBean
    private AiProxyService aiProxyService;

    // JwtInterceptor 会被 @WebMvcTest 一起扫进来（HandlerInterceptor 在其包含列表内），
    // 而 WebConfig 又依赖它，因此必须替它补上依赖，否则上下文起不来。
    // 本测试关注的是「请求形态错误」，因此让鉴权一律放行。
    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private TokenBlacklistService tokenBlacklistService;

    @BeforeEach
    void allowAnyToken() {
        // 真实 JwtInterceptor + mock 依赖：任意 Token 都视为已登录的 1001
        given(jwtUtil.validateToken(org.mockito.ArgumentMatchers.anyString())).willReturn(true);
        given(jwtUtil.getUserIdFromToken(org.mockito.ArgumentMatchers.anyString())).willReturn(1001L);
        // 鉴权链路上的三个校验点都要放行：jti 黑名单（按 jti 查）+ iat 失效水位线
        given(jwtUtil.getJti(org.mockito.ArgumentMatchers.anyString())).willReturn("test-jti");
        given(jwtUtil.getIssuedAt(org.mockito.ArgumentMatchers.anyString())).willReturn(new java.util.Date());
        given(tokenBlacklistService.contains(org.mockito.ArgumentMatchers.anyString())).willReturn(false);
        given(tokenBlacklistService.isIssuedBeforeWatermark(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong())).willReturn(false);
    }

    /** /api/ai/** 全部需要 JWT，请求必须带 Token，否则先被鉴权拦成 9001 */
    private static final String TOKEN = "test.jwt.token";
    private static final String AUTH = "Bearer " + TOKEN;

    @Test
    @DisplayName("multipart 请求漏传 image → 9003（缺少必填的文件字段）")
    void missingImagePartShouldReturn9003() throws Exception {
        mockMvc.perform(multipart("/api/ai/pose-evaluate")
                        .file(new MockMultipartFile("dummy", new byte[0]))
                        .param("actionName", "深蹲")
                        .header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(9003))
                .andExpect(jsonPath("$.msg").value(
                        org.hamcrest.Matchers.containsString("缺少必填的文件字段")));
    }

    @Test
    @DisplayName("非 multipart 请求（form-urlencoded）→ 9003（不支持请求类型）")
    void wrongContentTypeShouldReturn9003() throws Exception {
        mockMvc.perform(post("/api/ai/pose-evaluate")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("actionName", "深蹲")
                        .header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(9003))
                .andExpect(jsonPath("$.msg").value(
                        org.hamcrest.Matchers.containsString("参数校验失败")));
    }

    @Test
    @DisplayName("参考：正常 multipart 上传能进到业务层（证明上面两个失败不是接口本身坏了）")
    void validMultipartShouldReachService() throws Exception {
        MockMultipartFile image = new MockMultipartFile(
                "image", "squat.png", MediaType.IMAGE_PNG_VALUE, new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/api/ai/pose-evaluate")
                        .file(image)
                        .param("actionName", "深蹲")
                        .header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("三个接口的路径都挂在 /api/ai 下（避免被误改成别的前缀）")
    void aiEndpointsShouldBeMountedUnderApiAi() throws Exception {
        // 用一个必然失败但能证明「路由存在」的请求：非法肌群 → 9003 而不是 404
        mockMvc.perform(post("/api/ai/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", AUTH)
                        .content("{\"targetMuscle\":\"外星肌\",\"equipment\":[\"哑铃\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(9003));
    }
}

package com.fitness.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitness.cache.TokenBlacklistService;
import com.fitness.dto.LoginRequest;
import com.fitness.dto.LoginResponse;
import com.fitness.dto.RegisterRequest;
import com.fitness.dto.RegisterResponse;
import com.fitness.exception.BusinessException;
import com.fitness.exception.ErrorCode;
import com.fitness.service.UserService;
import com.fitness.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
 * UserController 接口契约测试（提示词测试策略：@WebMvcTest + MockMvc，UserController 注册/登录）
 * <p>
 * 只切 Web 层：UserService 被 mock，因此本类验证的是<b>接口请求/响应契约</b> ——
 * URL、入参绑定与校验、<b>HTTP 状态码恒为 200 + body.code 表达业务结果</b>、响应字段与 msg 文案。
 * <p>
 * 鉴权边界说明：{@code WebConfig}（WebMvcConfigurer）会被 @WebMvcTest 扫描到，
 * 它依赖真实的 {@code JwtInterceptor}；这里没有 mock 拦截器本身，而是保持真实拦截器 +
 * mock 掉它的两个依赖（JwtUtil / TokenBlacklistService），
 * 这样「register/login 在 WebConfig 白名单内、无需 Token」这条真实行为也一并被断言到，
 * 而 Token 签发/黑名单这类鉴权细节不在本轮范围。
 */
@WebMvcTest(UserController.class)
@ActiveProfiles("test")
@DisplayName("Controller 测试：UserController（注册 / 登录）")
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private UserService userService;

    /** JwtInterceptor 的依赖，mock 掉即可，本类不测鉴权逻辑 */
    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private TokenBlacklistService tokenBlacklistService;

    @BeforeEach
    void stubJwtDependencies() {
        given(jwtUtil.validateToken(anyString())).willReturn(true);
        given(jwtUtil.getUserIdFromToken(anyString())).willReturn(1001L);
        // jti 黑名单按 jti 查（不再用 userId+token），iat 水位线校验一并放行
        given(jwtUtil.getJti(anyString())).willReturn("test-jti");
        given(jwtUtil.getIssuedAt(anyString())).willReturn(new java.util.Date());
        given(tokenBlacklistService.contains(anyString())).willReturn(false);
        given(tokenBlacklistService.isIssuedBeforeWatermark(anyLong(), anyLong())).willReturn(false);
    }

    // ==================== 1.1 注册 ====================

    @Test
    @DisplayName("注册成功：HTTP 200 + code=0，data 中手机号必须是脱敏的 138****8000")
    void registerShouldReturnCode0AndMaskedPhone() throws Exception {
        given(userService.register(any(RegisterRequest.class))).willReturn(
                RegisterResponse.builder().id(1L).nickname("张三").phone("138****8000").build());

        MvcResult result = mockMvc.perform(post("/api/v1/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nickname":"张三","phone":"13800138000","password":"Abc12345"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("success"))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.nickname").value("张三"))
                .andExpect(jsonPath("$.data.phone").value("138****8000"))
                .andReturn();

        // 规范第十章「敏感信息严禁返回完整手机号」：整个响应体里不得出现明文手机号
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(body.contains("13800138000"), "响应体不得包含完整手机号，实际响应=" + body);
        assertTrue(body.contains("138****8000"), "响应体应包含脱敏手机号");

        // 请求体必须被完整绑定到 DTO 后交给 Service（防止字段名写错导致昵称/密码丢参）
        verify(userService).register(org.mockito.ArgumentMatchers.argThat(req ->
                "张三".equals(req.getNickname())
                        && "13800138000".equals(req.getPhone())
                        && "Abc12345".equals(req.getPassword())));
        // 白名单校验：register 免鉴权，不应触发 Token 校验
        verify(jwtUtil, never()).validateToken(anyString());
    }

    @Test
    @DisplayName("注册手机号重复：HTTP 200 + code=1001 + msg=该手机号已注册")
    void registerShouldReturn1001WhenPhoneAlreadyRegistered() throws Exception {
        given(userService.register(any(RegisterRequest.class)))
                .willThrow(new BusinessException(ErrorCode.PHONE_REGISTERED));

        mockMvc.perform(post("/api/v1/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nickname":"张三","phone":"13800138000","password":"Abc12345"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.msg").value("该手机号已注册"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("注册参数校验失败：HTTP 200（不是 400）+ code=9003，且不调用 Service")
    void registerShouldReturn9003WithHttp200WhenParamInvalid() throws Exception {
        // 昵称 1 字符（要求 2-20）、手机号非 11 位、密码不含大写字母
        mockMvc.perform(post("/api/v1/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nickname":"张","phone":"12345","password":"abcdefgh"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(9003))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.startsWith("参数校验失败：")))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("手机号格式不正确")))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("密码需8-32位")))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(userService, never()).register(any(RegisterRequest.class));
    }

    @Test
    @DisplayName("注册请求体缺少必填字段：HTTP 200 + code=9003（不允许漏成 9999）")
    void registerShouldReturn9003WhenBodyMissing() throws Exception {
        mockMvc.perform(post("/api/v1/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(9003))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.startsWith("参数校验失败：")));

        verify(userService, never()).register(any(RegisterRequest.class));
    }

    // ==================== 1.2 登录 ====================

    @Test
    @DisplayName("登录成功：code=0，data 返回 token 与用户基本信息")
    void loginShouldReturnToken() throws Exception {
        given(userService.login(any(LoginRequest.class))).willReturn(LoginResponse.builder()
                .token("eyJhbGciOiJIUzI1NiJ9.test.token")
                .expiresAt("2026-08-06 14:30:00")
                .user(LoginResponse.UserBrief.builder()
                        .id(1L).nickname("张三").gender(1).trainingGoal("增肌").build())
                .build());

        mockMvc.perform(post("/api/v1/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"13800138000","password":"Abc12345"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.token").value("eyJhbGciOiJIUzI1NiJ9.test.token"))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-08-06 14:30:00"))
                .andExpect(jsonPath("$.data.user.id").value(1))
                .andExpect(jsonPath("$.data.user.nickname").value("张三"))
                .andExpect(jsonPath("$.data.user.gender").value(1))
                .andExpect(jsonPath("$.data.user.trainingGoal").value("增肌"));

        verify(userService).login(org.mockito.ArgumentMatchers.argThat(req ->
                "13800138000".equals(req.getPhone()) && "Abc12345".equals(req.getPassword())));
        // 登录接口同样在白名单内，不需要前置 Token
        verify(jwtUtil, never()).validateToken(anyString());
    }

    @Test
    @DisplayName("登录密码错误：code=1002 + msg=密码错误")
    void loginShouldReturn1002WhenPasswordWrong() throws Exception {
        given(userService.login(any(LoginRequest.class)))
                .willThrow(new BusinessException(ErrorCode.PASSWORD_ERROR));

        mockMvc.perform(post("/api/v1/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"13800138000","password":"Wrong12345"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1002))
                .andExpect(jsonPath("$.msg").value("密码错误"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("登录手机号未注册：code=1003 + msg=用户不存在")
    void loginShouldReturn1003WhenUserNotFound() throws Exception {
        given(userService.login(any(LoginRequest.class)))
                .willThrow(new BusinessException(ErrorCode.USER_NOT_FOUND));

        mockMvc.perform(post("/api/v1/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"13900139000","password":"Abc12345"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003))
                .andExpect(jsonPath("$.msg").value("用户不存在"));
    }

    @Test
    @DisplayName("登录手机号为空：HTTP 200 + code=9003（@NotBlank 生效）")
    void loginShouldReturn9003WhenPhoneBlank() throws Exception {
        mockMvc.perform(post("/api/v1/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"","password":"Abc12345"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(9003))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("手机号不能为空")));

        verify(userService, never()).login(any(LoginRequest.class));
    }

    @Test
    @DisplayName("请求体不是合法 JSON：HTTP 200 + code=9003（由 GlobalExceptionHandler 兜底）")
    void malformedJsonShouldReturn9003() throws Exception {
        mockMvc.perform(post("/api/v1/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{phone:}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(9003))
                .andExpect(jsonPath("$.msg").value("参数校验失败：请求体格式错误，需为合法JSON"));
    }
}

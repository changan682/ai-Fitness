package com.fitness.config;

import com.fitness.cache.TokenBlacklistService;
import com.fitness.controller.TrainingController;
import com.fitness.service.TrainingRecordService;
import com.fitness.util.JwtUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CORS 预检与鉴权拦截器的交互测试
 * <p>
 * <b>为什么需要这个测试</b>：`JwtInterceptor` 拦的是 `/api/**`，而浏览器跨域预检
 * （OPTIONS）<b>不会</b>携带 `Authorization` 头。Spring MVC 对预检请求仍会执行已注册的拦截器，
 * 因此修复前 `preHandle` 会直接返回 9001，导致 React 端所有 POST/PUT/DELETE 的预检失败 ——
 * 真实的业务请求根本发不出去，而 Postman 直接打接口却一切正常，属于极难排查的一类问题。
 * <p>
 * 这里同时给出<b>正向</b>与<b>反向</b>断言：只测「预检通过」是不够的 ——
 * 如果测试环境里拦截器压根没装上，预检也会通过，测试就成了假绿。
 * 因此反向断言「不带 Token 的真实请求仍被拦下（9001）」，证明拦截器确实在生效。
 */
@WebMvcTest(controllers = TrainingController.class)
@Import({WebConfig.class, CorsConfig.class, JwtInterceptor.class})
@ActiveProfiles("test")
@DisplayName("跨域预检：OPTIONS 放行，真实请求仍需鉴权")
class CorsPreflightTest {

    private static final String REACT_ORIGIN = "http://localhost:5173";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TrainingRecordService trainingRecordService;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private TokenBlacklistService tokenBlacklistService;

    @Test
    @DisplayName("OPTIONS 预检应放行并返回 CORS 头（不能返回 9001）")
    void preflightShouldBeAllowed() throws Exception {
        mockMvc.perform(options("/api/v1/training/record")
                        .header("Origin", REACT_ORIGIN)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", REACT_ORIGIN))
                // 预检的响应体里绝不能出现未登录错误码
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    @Test
    @DisplayName("反向断言：不带 Token 的真实请求仍应被拦下（code=9001）")
    void realRequestWithoutTokenShouldStillBeRejected() throws Exception {
        mockMvc.perform(get("/api/v1/training/records")
                        .param("startDate", "2026-07-01")
                        .param("endDate", "2026-07-30")
                        .header("Origin", REACT_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(9001))
                .andExpect(jsonPath("$.msg").value("未登录或Token已过期"));
    }

    @Test
    @DisplayName("放行预检不等于放行业务：OPTIONS 外的方法缺 Token 依旧 9001")
    void preflightBypassShouldNotLeakToOtherMethods() throws Exception {
        // DELETE 是跨域场景下最容易被预检问题掩盖的方法之一（预检失败时它根本发不出来）
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/training/record/1")
                        .header("Origin", REACT_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(9001));
    }
}

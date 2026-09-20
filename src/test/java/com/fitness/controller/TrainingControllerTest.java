package com.fitness.controller;

import com.fitness.cache.TokenBlacklistService;
import com.fitness.common.PageResult;
import com.fitness.dto.TrainingRecordRequest;
import com.fitness.dto.TrainingRecordUpdateRequest;
import com.fitness.entity.TrainingRecord;
import com.fitness.exception.BusinessException;
import com.fitness.exception.ErrorCode;
import com.fitness.service.TrainingRecordService;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TrainingController 接口契约测试（@WebMvcTest + MockMvc）
 * <p>
 * 覆盖新增 / 按日期范围分页查询 / 修改 / 删除四个核心接口，重点验证：
 * <ol>
 *   <li>响应体结构与字段名（分页的 {@code {list,total,page,size}}）</li>
 *   <li><b>本轮修复的关键点</b>：{@code PUT /record/{id}} 只传 {@code {"sets":5}} 必须成功
 *       （修改 DTO 不再复用新增 DTO 的 @NotNull，否则会被拦成 9003）</li>
 *   <li>删除接口的 msg 文案</li>
 *   <li>受保护接口的鉴权行为（无 Token → 9001），由真实 JwtInterceptor + mock 依赖验证</li>
 * </ol>
 * Service 层被 mock，因此这里只断言「请求 → 响应」契约，业务逻辑由 Service 单元测试覆盖。
 */
@WebMvcTest(TrainingController.class)
@ActiveProfiles("test")
@DisplayName("Controller 测试：TrainingController（新增 / 查询 / 修改 / 删除）")
class TrainingControllerTest {

    private static final Long USER_ID = 1001L;
    private static final String TOKEN = "test.jwt.token";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TrainingRecordService trainingRecordService;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private TokenBlacklistService tokenBlacklistService;

    @BeforeEach
    void stubJwtDependencies() {
        // 保持真实 JwtInterceptor，只替换它的依赖：模拟「已登录用户 1001」的合法请求
        given(jwtUtil.validateToken(TOKEN)).willReturn(true);
        given(jwtUtil.getUserIdFromToken(TOKEN)).willReturn(USER_ID);
        // jti 黑名单按 jti 查（不再用 userId+token），水位线校验一并放行
        given(jwtUtil.getJti(TOKEN)).willReturn("test-jti");
        given(jwtUtil.getIssuedAt(TOKEN)).willReturn(new java.util.Date());
        given(tokenBlacklistService.contains("test-jti")).willReturn(false);
        given(tokenBlacklistService.isIssuedBeforeWatermark(eq(USER_ID), anyLong())).willReturn(false);
    }

    // ==================== 2.1 新增训练记录 ====================

    @Test
    @DisplayName("新增记录：code=0，data 返回落库记录（含自动计算的 volume）")
    void addRecordShouldReturnSavedRecord() throws Exception {
        given(trainingRecordService.addRecord(eq(USER_ID), any(TrainingRecordRequest.class)))
                .willReturn(record(10L, "2026-07-30", "杠铃卧推", 4, 10, "60.0", "2400.0"));

        mockMvc.perform(post("/api/v1/training/record")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"trainingDate":"2026-07-30","actionName":"杠铃卧推",
                                 "sets":4,"reps":10,"weightKg":60.0,"durationMin":45,"rpe":8}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("success"))
                .andExpect(jsonPath("$.data.id").value(10))
                .andExpect(jsonPath("$.data.userId").value(1001))
                .andExpect(jsonPath("$.data.trainingDate").value("2026-07-30"))
                .andExpect(jsonPath("$.data.actionName").value("杠铃卧推"))
                .andExpect(jsonPath("$.data.sets").value(4))
                .andExpect(jsonPath("$.data.reps").value(10))
                .andExpect(jsonPath("$.data.weightKg").value(60.0))
                .andExpect(jsonPath("$.data.volume").value(2400.0));

        // 入参绑定必须完整（缺一个字段就会写出脏数据）
        verify(trainingRecordService).addRecord(eq(USER_ID), argThat(req ->
                "2026-07-30".equals(req.getTrainingDate())
                        && "杠铃卧推".equals(req.getActionName())
                        && Integer.valueOf(4).equals(req.getSets())
                        && Integer.valueOf(10).equals(req.getReps())
                        && new BigDecimal("60.0").compareTo(req.getWeightKg()) == 0
                        && Integer.valueOf(45).equals(req.getDurationMin())
                        && Integer.valueOf(8).equals(req.getRpe())));
    }

    @Test
    @DisplayName("新增记录缺少 sets：HTTP 200 + code=9003，且不调用 Service")
    void addRecordShouldReturn9003WhenSetsMissing() throws Exception {
        mockMvc.perform(post("/api/v1/training/record")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"actionName":"杠铃卧推","reps":10,"weightKg":60.0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(9003))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("组数不能为空")));

        verify(trainingRecordService, never()).addRecord(anyLong(), any(TrainingRecordRequest.class));
    }

    // ==================== 2.3 按日期范围查询 ====================

    @Test
    @DisplayName("按日期范围查询：data 必须是 {list,total,page,size} 分页结构")
    void queryByDateRangeShouldReturnPageStructure() throws Exception {
        given(trainingRecordService.queryByDateRange(
                eq(USER_ID), eq(LocalDate.parse("2026-07-01")), eq(LocalDate.parse("2026-07-31")),
                eq(2), eq(10)))
                .willReturn(new PageResult<>(
                        List.of(record(20L, "2026-07-20", "深蹲", 5, 5, "100.0", "2500.0")),
                        25L, 2, 10));

        mockMvc.perform(get("/api/v1/training/records")
                        .header("Authorization", "Bearer " + TOKEN)
                        .param("startDate", "2026-07-01")
                        .param("endDate", "2026-07-31")
                        .param("page", "2")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list").isArray())
                .andExpect(jsonPath("$.data.list.length()").value(1))
                .andExpect(jsonPath("$.data.list[0].id").value(20))
                .andExpect(jsonPath("$.data.list[0].actionName").value("深蹲"))
                .andExpect(jsonPath("$.data.list[0].volume").value(2500.0))
                .andExpect(jsonPath("$.data.total").value(25))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.size").value(10));
    }

    @Test
    @DisplayName("按日期范围查询：page/size 缺省为 1/50")
    void queryByDateRangeShouldUseDefaultPaging() throws Exception {
        given(trainingRecordService.queryByDateRange(
                eq(USER_ID), eq(LocalDate.parse("2026-07-01")), eq(LocalDate.parse("2026-07-31")),
                eq(1), eq(50)))
                .willReturn(new PageResult<>(List.of(), 0L, 1, 50));

        mockMvc.perform(get("/api/v1/training/records")
                        .header("Authorization", "Bearer " + TOKEN)
                        .param("startDate", "2026-07-01")
                        .param("endDate", "2026-07-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(50));
    }

    @Test
    @DisplayName("按日期范围查询：日期格式非法 → HTTP 200 + code=9003（不得落成 9999）")
    void queryByDateRangeShouldReturn9003WhenDateMalformed() throws Exception {
        mockMvc.perform(get("/api/v1/training/records")
                        .header("Authorization", "Bearer " + TOKEN)
                        .param("startDate", "2026-13-99")
                        .param("endDate", "2026-07-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(9003))
                .andExpect(jsonPath("$.msg").value("参数校验失败：日期格式错误，需为yyyy-MM-dd"));

        verify(trainingRecordService, never()).queryByDateRange(anyLong(), any(), any(), any(Integer.class), any(Integer.class));
    }

    @Test
    @DisplayName("按日期范围查询：未传 startDate → HTTP 200 + code=9003 且 msg 指明缺哪个参数")
    void queryByDateRangeShouldReturn9003WhenParamMissing() throws Exception {
        mockMvc.perform(get("/api/v1/training/records")
                        .header("Authorization", "Bearer " + TOKEN)
                        .param("endDate", "2026-07-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(9003))
                .andExpect(jsonPath("$.msg").value("参数校验失败：缺少必填参数 startDate"));
    }

    @Test
    @DisplayName("按日期范围查询：起止倒置 → 透传业务错误码 2002")
    void queryByDateRangeShouldReturn2002WhenRangeInverted() throws Exception {
        given(trainingRecordService.queryByDateRange(
                eq(USER_ID), eq(LocalDate.parse("2026-07-31")), eq(LocalDate.parse("2026-07-01")),
                any(Integer.class), any(Integer.class)))
                .willThrow(new BusinessException(ErrorCode.DATE_RANGE_INVALID));

        mockMvc.perform(get("/api/v1/training/records")
                        .header("Authorization", "Bearer " + TOKEN)
                        .param("startDate", "2026-07-31")
                        .param("endDate", "2026-07-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(2002))
                .andExpect(jsonPath("$.msg").value("日期范围无效"));
    }

    // ==================== 2.5 修改训练记录（本轮修复关键点） ====================

    @Test
    @DisplayName("修改记录：只传 {\"sets\":5} 必须成功（code=0，msg=记录已更新）")
    void updateRecordShouldAcceptPartialBodyWithOnlySets() throws Exception {
        given(trainingRecordService.updateRecord(
                eq(USER_ID), eq(7L), any(TrainingRecordUpdateRequest.class)))
                .willReturn(Map.of("id", 7L, "volume", new BigDecimal("1500.0")));

        mockMvc.perform(put("/api/v1/training/record/7")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sets\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("记录已更新"))
                .andExpect(jsonPath("$.data.id").value(7))
                .andExpect(jsonPath("$.data.volume").value(1500.0));

        // 关键断言：DTO 只被填充 sets，其余字段保持 null（说明修改请求没有复用「新增」的必填校验）
        verify(trainingRecordService).updateRecord(eq(USER_ID), eq(7L), argThat(req ->
                Integer.valueOf(5).equals(req.getSets())
                        && req.getReps() == null
                        && req.getWeightKg() == null
                        && req.getDurationMin() == null
                        && req.getRpe() == null
                        && req.getRemark() == null));
    }

    @Test
    @DisplayName("修改记录：空请求体 {} 同样成功（所有字段可选）")
    void updateRecordShouldAcceptEmptyBody() throws Exception {
        given(trainingRecordService.updateRecord(
                eq(USER_ID), eq(7L), any(TrainingRecordUpdateRequest.class)))
                .willReturn(Map.of("id", 7L, "volume", new BigDecimal("1400.0")));

        mockMvc.perform(put("/api/v1/training/record/7")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("记录已更新"));
    }

    @Test
    @DisplayName("修改记录：sets=0 违反取值范围 → code=9003")
    void updateRecordShouldReturn9003WhenSetsInvalid() throws Exception {
        mockMvc.perform(put("/api/v1/training/record/7")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sets\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(9003))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("组数至少为1")));

        verify(trainingRecordService, never())
                .updateRecord(anyLong(), anyLong(), any(TrainingRecordUpdateRequest.class));
    }

    @Test
    @DisplayName("修改他人/不存在的记录：透传 2001 训练记录不存在")
    void updateRecordShouldReturn2001WhenNotFound() throws Exception {
        given(trainingRecordService.updateRecord(
                eq(USER_ID), eq(999L), any(TrainingRecordUpdateRequest.class)))
                .willThrow(new BusinessException(ErrorCode.TRAINING_RECORD_NOT_FOUND));

        mockMvc.perform(put("/api/v1/training/record/999")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sets\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(2001))
                .andExpect(jsonPath("$.msg").value("训练记录不存在"));
    }

    // ==================== 2.6 删除训练记录 ====================

    @Test
    @DisplayName("删除记录：code=0 且 msg 文案为「记录已删除」，data 为 null")
    void deleteRecordShouldReturnExpectedMsg() throws Exception {
        MvcResult result = mockMvc.perform(delete("/api/v1/training/record/9")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("记录已删除"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"data\":null"), "删除成功应返回 data=null，实际响应=" + body);
        verify(trainingRecordService).deleteRecord(USER_ID, 9L);
    }

    @Test
    @DisplayName("删除不存在的记录：透传 2001 训练记录不存在")
    void deleteRecordShouldReturn2001WhenNotFound() throws Exception {
        org.mockito.BDDMockito.willThrow(new BusinessException(ErrorCode.TRAINING_RECORD_NOT_FOUND))
                .given(trainingRecordService).deleteRecord(USER_ID, 999L);

        mockMvc.perform(delete("/api/v1/training/record/999")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(2001))
                .andExpect(jsonPath("$.msg").value("训练记录不存在"));
    }

    // ==================== 鉴权边界（真实拦截器） ====================

    @Test
    @DisplayName("训练接口无 Token：HTTP 200 + code=9001（WebConfig 未把 /training/** 加入白名单）")
    void protectedEndpointWithoutTokenShouldReturn9001() throws Exception {
        mockMvc.perform(get("/api/v1/training/today"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(9001))
                .andExpect(jsonPath("$.msg").value("未登录或Token已过期"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(trainingRecordService, never()).getTodayRecords(anyLong());
        verify(jwtUtil, never()).validateToken(anyString());
    }

    // ==================== 测试数据 ====================

    private TrainingRecord record(Long id, String date, String action,
                                  int sets, int reps, String weight, String volume) {
        return TrainingRecord.builder()
                .id(id)
                .userId(USER_ID)
                .trainingDate(LocalDate.parse(date))
                .actionName(action)
                .sets(sets)
                .reps(reps)
                .weightKg(new BigDecimal(weight))
                .volume(new BigDecimal(volume))
                .build();
    }
}

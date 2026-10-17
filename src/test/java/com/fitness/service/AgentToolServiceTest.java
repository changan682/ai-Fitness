package com.fitness.service;

import com.fitness.common.PageResult;
import com.fitness.dto.BodyMetricTrendResponse;
import com.fitness.dto.ai.ToolInvokeResult;
import com.fitness.entity.TrainingRecord;
import com.fitness.exception.BusinessException;
import com.fitness.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Agent 工具网关的服务层测试。
 * <p>
 * 重点不是"能不能查到数据"（那是各 Service 自己的测试），
 * 而是<b>三道安全边界</b>：白名单派发、userId 强制来自签名 body、参数夹紧。
 * 这三条任何一条失守，都等于把服务端能力暴露给模型。
 */
class AgentToolServiceTest {

    private final StatsService statsService = mock(StatsService.class);
    private final TrainingRecordService trainingRecordService = mock(TrainingRecordService.class);
    private final BodyMetricService bodyMetricService = mock(BodyMetricService.class);
    private final AgentToolService service =
            new AgentToolService(statsService, trainingRecordService, bodyMetricService);

    // ==================== 白名单 ====================

    @Test
    @DisplayName("未知工具直接拒绝，且不会触碰任何 Service")
    void rejectsUnknownTool() {
        assertThatThrownBy(() -> service.invoke("drop_all_tables", 1L, Map.of()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ErrorCode.PARAM_INVALID.getCode()));

        // 关键：反射式派发会让未知名字落到方法上，这里必须完全没有调用
        verifyNoInteractions(statsService, trainingRecordService, bodyMetricService);
    }

    @Test
    @DisplayName("缺少 userId 直接拒绝 —— 否则无法确定代表谁取数")
    void rejectsMissingUserId() {
        assertThatThrownBy(() -> service.invoke("get_weekly_stats", null, Map.of()))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(statsService);
    }

    // ==================== 周统计 ====================

    @Test
    @DisplayName("周统计：不传 weekStart 时按今天所在周查询")
    void weeklyStatsDefaultsToNow() {
        when(statsService.getWeeklyStats(anyLong(), any(LocalDate.class)))
                .thenReturn(Map.of("weekStart", "2026-09-14", "weekEnd", "2026-09-20",
                        "trainingDays", 4, "totalVolume", new BigDecimal("18650.0")));

        ToolInvokeResult result = service.invoke("get_weekly_stats", 36L, Map.of());

        ArgumentCaptor<LocalDate> captor = ArgumentCaptor.forClass(LocalDate.class);
        verify(statsService).getWeeklyStats(eq(36L), captor.capture());
        assertThat(captor.getValue()).isEqualTo(LocalDate.now());
        assertThat(result.getData()).isInstanceOf(Map.class);
        assertThat(result.getMeta()).containsEntry("tool", "get_weekly_stats");
    }

    @Test
    @DisplayName("周统计：传了 weekStart 就用它")
    void weeklyStatsUsesProvidedWeek() {
        when(statsService.getWeeklyStats(anyLong(), any(LocalDate.class)))
                .thenReturn(Map.of("weekStart", "2026-09-07", "weekEnd", "2026-09-13"));

        service.invoke("get_weekly_stats", 36L, Map.of("weekStart", "2026-09-09"));

        verify(statsService).getWeeklyStats(36L, LocalDate.of(2026, 9, 9));
    }

    // ==================== 训练记录 ====================

    @Test
    @DisplayName("训练记录：压缩成精简行（不带 id/createdAt），控 token")
    void trainingRecordsAreCompacted() {
        TrainingRecord record = TrainingRecord.builder()
                .id(99L).userId(36L).trainingDate(LocalDate.of(2026, 9, 14))
                .actionName("杠铃卧推").sets(4).reps(10)
                .weightKg(new BigDecimal("60.0")).volume(new BigDecimal("2400.0"))
                .rpe(8).remark("状态不错").build();
        when(trainingRecordService.queryByDateRange(anyLong(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageResult<>(List.of(record), 1L, 1, 20));

        ToolInvokeResult result = service.invoke("get_training_records", 36L,
                Map.of("startDate", "2026-09-14", "endDate", "2026-09-20"));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.getData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("records");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsKeys("date", "action", "sets", "reps", "weightKg", "volume", "rpe");
        // id / remark / createdAt 这些对模型无用，必须剔除
        assertThat(rows.get(0)).doesNotContainKeys("id", "remark", "createdAt", "userId");
        assertThat(data).containsEntry("returned", 1).containsEntry("total", 1L);
    }

    @Test
    @DisplayName("训练记录：区间过长直接拒绝（模型很可能传 2000-01-01 到今天）")
    void rejectsOverlongRange() {
        assertThatThrownBy(() -> service.invoke("get_training_records", 36L,
                Map.of("startDate", "2000-01-01", "endDate", "2026-09-20")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("日期区间过长");
        verifyNoInteractions(trainingRecordService);
    }

    @Test
    @DisplayName("训练记录：endDate 早于 startDate 报 2002")
    void rejectsReversedRange() {
        assertThatThrownBy(() -> service.invoke("get_training_records", 36L,
                Map.of("startDate", "2026-09-20", "endDate", "2026-09-01")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ErrorCode.DATE_RANGE_INVALID.getCode()));
    }

    @Test
    @DisplayName("训练记录：缺少必填日期报参数错误")
    void rejectsMissingDates() {
        assertThatThrownBy(() -> service.invoke("get_training_records", 36L, Map.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("startDate");
    }

    @Test
    @DisplayName("训练记录：日期格式非法报参数错误（提示正确格式）")
    void rejectsBadDateFormat() {
        assertThatThrownBy(() -> service.invoke("get_training_records", 36L,
                Map.of("startDate", "2026/09/14", "endDate", "2026-09-20")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("yyyy-MM-dd");
    }

    @Test
    @DisplayName("训练记录：size 被夹到 [1,50]")
    void clampsSize() {
        when(trainingRecordService.queryByDateRange(anyLong(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageResult<>(List.of(), 0L, 1, 50));

        service.invoke("get_training_records", 36L,
                Map.of("startDate", "2026-09-14", "endDate", "2026-09-20", "size", 999));
        verify(trainingRecordService).queryByDateRange(anyLong(), any(), any(), eq(1), eq(50));

        service.invoke("get_training_records", 36L,
                Map.of("startDate", "2026-09-14", "endDate", "2026-09-20", "size", -5));
        verify(trainingRecordService).queryByDateRange(anyLong(), any(), any(), eq(1), eq(1));
    }

    @Test
    @DisplayName("训练记录：结果被截断时 meta 必须显式告知（否则模型会当成全量）")
    void marksTruncation() {
        when(trainingRecordService.queryByDateRange(anyLong(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageResult<>(List.of(), 120L, 1, 20));

        ToolInvokeResult result = service.invoke("get_training_records", 36L,
                Map.of("startDate", "2026-09-14", "endDate", "2026-09-20"));

        assertThat(result.getMeta()).containsEntry("truncated", true);
        assertThat(String.valueOf(result.getMeta().get("truncatedHint"))).contains("get_weekly_stats");
    }

    @Test
    @DisplayName("训练记录：带 actionName 时走按动作查询")
    void routesToActionQuery() {
        when(trainingRecordService.queryByAction(anyLong(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Map.of("list", List.of(), "total", 0L));

        service.invoke("get_training_records", 36L, Map.of(
                "startDate", "2026-09-14", "endDate", "2026-09-20", "actionName", "卧推"));

        verify(trainingRecordService).queryByAction(eq(36L), eq("卧推"), any(), any(), anyInt(), anyInt());
    }

    // ==================== 体测趋势 ====================

    @Test
    @DisplayName("体测趋势：样本不足时 meta 标注 insufficientSamples（7 日均值为 null 不是 0）")
    void bodyTrendMarksInsufficientSamples() {
        BodyMetricTrendResponse trend = new BodyMetricTrendResponse();
        trend.setList(List.of());
        trend.setTotalRecords(2);
        when(bodyMetricService.getTrend(anyLong(), any(), any())).thenReturn(trend);

        ToolInvokeResult result = service.invoke("get_body_trend", 36L,
                Map.of("startDate", "2026-09-01", "endDate", "2026-09-21"));

        assertThat(result.getMeta()).containsEntry("insufficientSamples", true);
    }

    @Test
    @DisplayName("体测趋势：区间上限比训练记录宽（体测一天一条，量级小）")
    void bodyTrendAllowsLongerRange() {
        BodyMetricTrendResponse trend = new BodyMetricTrendResponse();
        trend.setList(List.of());
        trend.setTotalRecords(10);
        when(bodyMetricService.getTrend(anyLong(), any(), any())).thenReturn(trend);

        // 200 天：超过训练记录的 92 天上限，但对体测是合法的
        service.invoke("get_body_trend", 36L,
                Map.of("startDate", "2026-03-01", "endDate", "2026-09-21"));

        verify(bodyMetricService).getTrend(anyLong(), any(), any());
    }
}

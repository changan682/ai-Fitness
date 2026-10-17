package com.fitness.service;

import com.fitness.common.PageResult;
import com.fitness.dto.BodyMetricTrendResponse;
import com.fitness.dto.ai.ToolInvokeResult;
import com.fitness.entity.TrainingRecord;
import com.fitness.exception.BusinessException;
import com.fitness.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent 工具网关 —— 把「查用户私有数据」暴露成 Python Agent 可调用的工具。
 *
 * <h3>为什么不给 Python 连数据库</h3>
 * 本项目的铁律是「业务口径只写在 Java 一处」（BFF 原则）。一旦 Python 直连 MySQL，
 * 「最新体重取档案还是取体测」「连续训练天数今日未练算不算断」这类口径就要在两种语言里
 * 各写一遍 —— 项目已经因为这个踩过一次。所以工具**只调已有 Service**，不写任何新业务。
 *
 * <h3>三道安全边界</h3>
 * <ol>
 *   <li><b>白名单派发</b>：只有 {@link #SUPPORTED_TOOLS} 里的名字可用，
 *       用 {@code switch} 而不是反射 —— 反射式派发等于把服务端方法暴露给模型，
 *       一旦模型（或被注入的模型）传了别的名字就会越权。</li>
 *   <li><b>userId 来自签名保护的 body</b>，不接受调用方用参数覆盖；
 *       所有 Service 调用都强制带上这个 userId。</li>
 *   <li><b>参数夹紧</b>：日期区间跨度、分页大小、日期格式都在这里收口。
 *       模型完全可能传 {@code 2000-01-01} 到今天的区间 —— 那会扫全表。</li>
 * </ol>
 *
 * <h3>失败语义</h3>
 * 参数不合法抛 {@link ErrorCode#PARAM_INVALID}（→ HTTP 200 + code 9003）。
 * Python 侧读信封得知 {@code code != 0} 后，把错误当**工具结果**回填给模型，
 * 模型会据此调整或如实告知用户 —— 不会让整轮问答 500。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentToolService {

    public static final String TOOL_WEEKLY_STATS = "get_weekly_stats";
    public static final String TOOL_TRAINING_RECORDS = "get_training_records";
    public static final String TOOL_BODY_TREND = "get_body_trend";

    /** 工具白名单 —— 新增工具必须同时在这里与 Python 侧 schema 里登记 */
    public static final Set<String> SUPPORTED_TOOLS = Set.of(
            TOOL_WEEKLY_STATS, TOOL_TRAINING_RECORDS, TOOL_BODY_TREND);

    /** 训练记录查询的最大区间：一个季度。模型传超长区间会退化成全表扫描 */
    private static final int MAX_RECORD_RANGE_DAYS = 92;

    /** 体测趋势的最大区间：一年（体测一天一条，量级小得多） */
    private static final int MAX_TREND_RANGE_DAYS = 366;

    /** 单次返回的训练记录条数上限（控 token，也控 DB 压力） */
    private static final int MAX_RECORD_SIZE = 50;
    private static final int DEFAULT_RECORD_SIZE = 20;

    private final StatsService statsService;
    private final TrainingRecordService trainingRecordService;
    private final BodyMetricService bodyMetricService;

    /**
     * 执行一个工具。
     *
     * @param tool   工具名（必须命中白名单）
     * @param userId 代表哪个用户执行（来自**签名保护的 body**）
     * @param args   工具参数（各分支自行校验）
     */
    public ToolInvokeResult invoke(String tool, Long userId, Map<String, Object> args) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "工具调用缺少 userId");
        }
        if (tool == null || tool.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "工具名不能为空");
        }
        String name = tool.trim();
        if (!SUPPORTED_TOOLS.contains(name)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "不支持的工具：" + name + "；可用：" + String.join("/", SUPPORTED_TOOLS));
        }

        Map<String, Object> safeArgs = args == null ? Map.of() : args;
        long started = System.currentTimeMillis();

        ToolInvokeResult result = switch (name) {
            case TOOL_WEEKLY_STATS -> weeklyStats(userId, safeArgs, started);
            case TOOL_TRAINING_RECORDS -> trainingRecords(userId, safeArgs, started);
            case TOOL_BODY_TREND -> bodyTrend(userId, safeArgs, started);
            // 白名单已校验，理论上不可达
            default -> throw new BusinessException(ErrorCode.PARAM_INVALID, "不支持的工具：" + name);
        };

        log.info("Agent 工具执行完成: tool={}, userId={}, 耗时={}ms",
                name, userId, System.currentTimeMillis() - started);
        return result;
    }

    // ==================== 3 个工具 ====================

    /** 周训练统计：训练天数 / 总容量 / 动作条数 / 平均 RPE / 动作 TOP5 / 体重区间 / 日均热量 */
    private ToolInvokeResult weeklyStats(Long userId, Map<String, Object> args, long started) {
        String weekStartRaw = text(args, "weekStart");
        LocalDate anyDayInWeek = weekStartRaw == null ? LocalDate.now() : parseDate(weekStartRaw, "weekStart");

        Map<String, Object> stats = statsService.getWeeklyStats(userId, anyDayInWeek);

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("range", Map.of("weekStart", String.valueOf(stats.get("weekStart")),
                "weekEnd", String.valueOf(stats.get("weekEnd"))));
        return ToolInvokeResult.of(stats, TOOL_WEEKLY_STATS,
                System.currentTimeMillis() - started, extra);
    }

    /** 训练记录明细（压缩成精简行 —— 喂给模型时不需要 id/createdAt 这类字段） */
    private ToolInvokeResult trainingRecords(Long userId, Map<String, Object> args, long started) {
        String startRaw = requireText(args, "startDate");
        String endRaw = requireText(args, "endDate");
        LocalDate start = parseDate(startRaw, "startDate");
        LocalDate end = parseDate(endRaw, "endDate");
        checkRange(start, end, MAX_RECORD_RANGE_DAYS);

        String actionName = text(args, "actionName");
        int size = clampSize(args.get("size"));

        List<TrainingRecord> records;
        long total;
        if (actionName != null) {
            Map<String, Object> page = trainingRecordService.queryByAction(
                    userId, actionName, start, end, 1, size);
            records = castRecords(page.get("list"));
            total = toLong(page.get("total"));
        } else {
            PageResult<TrainingRecord> page = trainingRecordService.queryByDateRange(
                    userId, start, end, 1, size);
            records = page.getList();
            total = page.getTotal();
        }

        List<Map<String, Object>> rows = new ArrayList<>(records.size());
        for (TrainingRecord record : records) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", record.getTrainingDate() == null ? null : record.getTrainingDate().toString());
            row.put("action", record.getActionName());
            row.put("sets", record.getSets());
            row.put("reps", record.getReps());
            row.put("weightKg", record.getWeightKg());
            row.put("volume", record.getVolume());
            row.put("rpe", record.getRpe());
            rows.add(row);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("records", rows);
        data.put("returned", rows.size());
        data.put("total", total);

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("range", Map.of("startDate", startRaw, "endDate", endRaw));
        // 记录被截断时必须显式告知 —— 否则模型会把"只看到前 20 条"当成"总共就这些"
        extra.put("truncated", rows.size() < total);
        if (rows.size() < total) {
            extra.put("truncatedHint", "仅返回前 " + rows.size() + " 条（共 " + total + " 条），统计类问题请改用 get_weekly_stats");
        }
        return ToolInvokeResult.of(data, TOOL_TRAINING_RECORDS,
                System.currentTimeMillis() - started, extra);
    }

    /** 体测趋势：每天的体重/腰围/臂围/腿围/体脂率 + 7 日滑动均值 */
    private ToolInvokeResult bodyTrend(Long userId, Map<String, Object> args, long started) {
        String startRaw = requireText(args, "startDate");
        String endRaw = requireText(args, "endDate");
        LocalDate start = parseDate(startRaw, "startDate");
        LocalDate end = parseDate(endRaw, "endDate");
        checkRange(start, end, MAX_TREND_RANGE_DAYS);

        BodyMetricTrendResponse trend = bodyMetricService.getTrend(userId, start, end);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("points", trend.getList());
        data.put("latestWeight", trend.getLatestWeight());
        data.put("weightChange", trend.getWeightChange());
        data.put("totalRecords", trend.getTotalRecords());

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("range", Map.of("startDate", startRaw, "endDate", endRaw));
        // 样本少于 3 时后端把 7 日均值返回 null（不是 0），模型需要知道这是"样本不足"而非"没有变化"
        extra.put("insufficientSamples", trend.getTotalRecords() < 3);
        return ToolInvokeResult.of(data, TOOL_BODY_TREND,
                System.currentTimeMillis() - started, extra);
    }

    // ==================== 参数解析与夹紧 ====================

    private static String text(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static String requireText(Map<String, Object> args, String key) {
        String value = text(args, key);
        if (value == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "缺少参数 " + key);
        }
        return value;
    }

    private static LocalDate parseDate(String value, String field) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "参数 " + field + " 需为 yyyy-MM-dd 格式，实际：" + value);
        }
    }

    /** 区间校验 + 跨度上限（模型很可能会传 2000-01-01 到今天） */
    private static void checkRange(LocalDate start, LocalDate end, int maxDays) {
        if (end.isBefore(start)) {
            throw new BusinessException(ErrorCode.DATE_RANGE_INVALID,
                    "endDate 不能早于 startDate");
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(start, end);
        if (days > maxDays) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "日期区间过长（" + days + " 天），最多支持 " + maxDays + " 天；统计类问题请改用 get_weekly_stats");
        }
    }

    private static int clampSize(Object value) {
        if (value == null) {
            return DEFAULT_RECORD_SIZE;
        }
        try {
            int size = Integer.parseInt(String.valueOf(value).trim());
            return Math.max(1, Math.min(MAX_RECORD_SIZE, size));
        } catch (NumberFormatException e) {
            return DEFAULT_RECORD_SIZE;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<TrainingRecord> castRecords(Object value) {
        if (value instanceof List<?> list) {
            List<TrainingRecord> records = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item instanceof TrainingRecord record) {
                    records.add(record);
                }
            }
            return records;
        }
        return List.of();
    }

    private static long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}

package com.fitness.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitness.cache.CacheKeys;
import com.fitness.cache.DistributedLockUtil;
import com.fitness.cache.RedisCacheService;
import com.fitness.common.Result;
import com.fitness.dto.WeeklyPlanCallbackRequest;
import com.fitness.entity.WeeklyPlan;
import com.fitness.exception.BusinessException;
import com.fitness.exception.ErrorCode;
import com.fitness.repository.WeeklyPlanRepository;
import com.fitness.util.HmacSignatureVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 回调接口 — 接收 Python 的异步回调（规范 8.1）
 *
 * <h3>为什么这条路径在 JWT 白名单里</h3>
 * 调用方是内网的 Python 消费者，它没有用户 Token；身份凭证是 **HMAC 签名**。
 * 因此 {@code WebConfig} 把 {@code /api/ai/callback/**} 排除在 JWT 之外，
 * 安全性完全由本类的验签逻辑承担 —— 验签一旦出问题，这个接口就是对内网完全开放的写入口。
 *
 * <h3>四道防线（缺一不可）</h3>
 * <ol>
 *   <li><b>时间戳窗口</b>：{@code |now - X-Timestamp| ≤ 5 分钟}，挡掉重放旧报文；</li>
 *   <li><b>HMAC 签名（覆盖 body 哈希）</b>：挡掉篡改与伪造；</li>
 *   <li><b>Redis 幂等锁</b>：同一 taskId 并发回调只放一个进来；</li>
 *   <li><b>DB 唯一索引 uk_task_id</b>：Redis 挂掉时的最终兜底（见
 *       {@link DataIntegrityViolationException} 分支）。</li>
 * </ol>
 *
 * <h3>为什么用 @RequestBody String 而不是 DTO</h3>
 * 验签必须基于**原始请求体字节**。若让 Spring 先反序列化成 DTO 再重新序列化算哈希，
 * 字段顺序/空格差异会让哈希与 Python 侧不一致，表现为「验签随机失败」。
 * 因此这里先收原始字符串，验签通过后再手动反序列化。
 */
@Slf4j
@RestController
@RequestMapping("/api/ai/callback")
@RequiredArgsConstructor
public class AICallbackController {

    /**
     * 参与签名的路径
     * <p>
     * 必须与 Python 侧 {@code callback_client} 里的 path 逐字符一致；
     * 刻意写成常量而不是取 {@code request.getRequestURI()}：后者可能带 context-path 或
     * 路径参数，一旦不一致就是「所有回调都验签失败」这种全链路故障。
     */
    public static final String WEEKLY_PLAN_PATH = "/api/ai/callback/weekly-plan";

    /** 幂等锁持有时间（秒）：需覆盖一次落库耗时 */
    private static final long CALLBACK_LOCK_SECONDS = 30L;

    /** 幂等返回的业务码（规范 8.1 示例：6001 = 任务已处理） */
    private static final String DUPLICATE_MESSAGE = "任务已处理（幂等返回）";

    /** MySQL 唯一键冲突错误码（ER_DUP_ENTRY）：用于把「重复回调」与其它约束错误区分开 */
    private static final int MYSQL_ER_DUP_ENTRY = 1062;

    private final HmacSignatureVerifier signatureVerifier;
    private final ObjectMapper objectMapper;
    private final WeeklyPlanRepository weeklyPlanRepository;
    private final DistributedLockUtil distributedLockUtil;
    private final RedisCacheService redisCacheService;

    /**
     * 周计划回调 — Python 生成完 AI 建议后回调写库
     *
     * @param timestamp {@code X-Timestamp}（毫秒）
     * @param signature {@code X-Signature}（HMAC-SHA256 十六进制小写）
     * @param rawBody   原始请求体（验签用）
     */
    @PostMapping("/weekly-plan")
    public Result<Map<String, Object>> weeklyPlan(
            @RequestHeader(value = "X-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Signature", required = false) String signature,
            @RequestBody(required = false) String rawBody) throws Exception {

        byte[] bodyBytes = rawBody == null
                ? new byte[0] : rawBody.getBytes(StandardCharsets.UTF_8);

        // ---- 防线 1：时间戳窗口（先查时间戳再验签，省一次 HMAC 计算；顺序也符合规范 8.1）----
        if (!signatureVerifier.isTimestampFresh(timestamp, System.currentTimeMillis())) {
            log.warn("回调被拒：时间戳缺失/非法/超出 ±5 分钟窗口, X-Timestamp={}", timestamp);
            throw new BusinessException(ErrorCode.SIGNATURE_INVALID, "时间戳缺失或已过期");
        }

        // ---- 防线 2：验签（签名原文覆盖 body 哈希）----
        if (!signatureVerifier.isSignatureValid(
                "POST", WEEKLY_PLAN_PATH, timestamp, bodyBytes, signature)) {
            throw new BusinessException(ErrorCode.SIGNATURE_INVALID);
        }

        WeeklyPlanCallbackRequest req = objectMapper.readValue(bodyBytes, WeeklyPlanCallbackRequest.class);
        validate(req);

        // ---- 防线 3：Redis 幂等锁 ----
        String lockKey = CacheKeys.lockWeeklyPlanCallback(req.getTaskId());
        String lockValue = distributedLockUtil.tryLock(lockKey, CALLBACK_LOCK_SECONDS);
        if (lockValue == null) {
            // 另一个线程正在处理同一 taskId：对调用方而言等价于「已处理」，返回 6001 而不是报错，
            // 否则 Python 会把这条消息 nack 重投，造成无意义的重试
            log.warn("重复回调（锁被占用，正在处理中）: taskId={}", req.getTaskId());
            throw new BusinessException(ErrorCode.AI_TIMEOUT.getCode(), DUPLICATE_MESSAGE);
        }

        try {
            return saveWeeklyPlan(req);
        } finally {
            distributedLockUtil.unlock(lockKey, lockValue);
        }
    }

    // ==================== 落库 ====================

    private Result<Map<String, Object>> saveWeeklyPlan(WeeklyPlanCallbackRequest req) {
        // 幂等显式检查（走 uk_task_id）
        if (weeklyPlanRepository.findByTaskId(req.getTaskId()).isPresent()) {
            log.info("重复回调（taskId 已存在，幂等返回）: taskId={}", req.getTaskId());
            throw new BusinessException(ErrorCode.AI_TIMEOUT.getCode(), DUPLICATE_MESSAGE);
        }

        LocalDate weekStart = LocalDate.parse(req.getWeekStart());

        // 同一用户同一周只保留一行：定时任务（周日20:00）已经用占位 taskId 建过一行并写了 week_summary，
        // 这里把 AI 建议补写到那一行上，而不是再插一行 —— 否则「latest 周计划」会因两行同周而变得不确定。
        WeeklyPlan plan = weeklyPlanRepository
                .findByUserIdAndWeekStart(req.getUserId(), weekStart)
                .orElseGet(WeeklyPlan::new);
        plan.setUserId(req.getUserId());
        plan.setWeekStart(weekStart);
        plan.setTaskId(req.getTaskId());          // taskId 归 AI 回调所有，作为 uk_task_id 的幂等键
        plan.setSuggestionText(req.getSuggestionText());
        if (req.getWeekSummary() != null && !req.getWeekSummary().isEmpty()) {
            plan.setWeekSummary(writeJson(req.getWeekSummary()));
        }

        try {
            WeeklyPlan saved = weeklyPlanRepository.save(plan);

            // 周统计缓存失效：前端下次读取会拿到含 AI 建议的最新数据（规范 8.1 第 4 步）
            redisCacheService.delete(CacheKeys.statsWeekly(req.getUserId(), weekStart));

            log.info("周计划回调入库成功: taskId={}, userId={}, weekStart={}, planId={}, 建议长度={}",
                    req.getTaskId(), req.getUserId(), weekStart, saved.getId(),
                    req.getSuggestionText() == null ? 0 : req.getSuggestionText().length());

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("planId", saved.getId());
            return Result.ok("周计划已接收", data);
        } catch (DataIntegrityViolationException e) {
            // ---- 防线 4：DB 唯一索引兜底（Redis 锁失效/Redis 不可用时的最后一道）----
            //
            // ⚠️ 这里**不能**把所有 DataIntegrityViolationException 都当成「已处理」：
            // 该异常同时覆盖字段过长(1406)、数值越界(1264)、NOT NULL(1048) 等约束问题。
            // 若不区分，一个 taskId 超长导致的写库失败会被上报成「6001 任务已处理」，
            // Python 侧视为成功而 ACK —— AI 建议就此静默丢失，且永远不会重试。
            if (!isDuplicateTaskId(e)) {
                log.error("回调写库失败（非唯一键冲突，不按幂等处理）: taskId={}, cause={}",
                        req.getTaskId(), e.getMostSpecificCause().getMessage());
                throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                        "周计划回调落库失败：" + e.getMostSpecificCause().getMessage());
            }
            log.info("并发回调撞 uk_task_id，按已处理返回: taskId={}", req.getTaskId());
            throw new BusinessException(ErrorCode.AI_TIMEOUT.getCode(), DUPLICATE_MESSAGE);
        }
    }

    /**
     * 判断这次约束冲突是否就是 {@code uk_task_id} 的唯一键冲突
     * <p>
     * 只看异常类型是不够的（见上面调用处的说明）。MySQL 的唯一键冲突错误码是
     * <b>1062 ER_DUP_ENTRY</b>，且消息里会带上索引名，因此两个条件同时满足才认。
     * <p>
     * 判定失败时的方向是**故意保守**的：宁可把重复回调误判成真错误（Python 会重试、
     * 最终进死信队列，人能看到），也不能把真错误误判成重复回调（静默丢数据、无人知晓）。
     */
    private boolean isDuplicateTaskId(DataIntegrityViolationException e) {
        Throwable cause = e.getMostSpecificCause();
        if (cause instanceof SQLException sqlException && sqlException.getErrorCode() == MYSQL_ER_DUP_ENTRY) {
            String message = sqlException.getMessage() == null ? "" : sqlException.getMessage();
            return message.contains("uk_task_id");
        }
        return false;
    }

    /** 入参校验：缺字段或格式不对时给 9003，避免写进半条脏数据 */
    private void validate(WeeklyPlanCallbackRequest req) {
        if (req == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "回调请求体不能为空");
        }
        requireText(req.getTaskId(), "taskId");
        requireText(req.getWeekStart(), "weekStart");
        requireText(req.getSuggestionText(), "suggestionText");
        if (req.getUserId() == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "userId 不能为空");
        }
        try {
            LocalDate.parse(req.getWeekStart());
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "weekStart 需为 yyyy-MM-dd 格式，实际：" + req.getWeekStart());
        }
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, field + " 不能为空");
        }
    }

    private String writeJson(Map<String, Object> summary) {
        try {
            return objectMapper.writeValueAsString(summary);
        } catch (Exception e) {
            log.warn("weekSummary 序列化失败，退化为 toString: {}", e.getMessage());
            return String.valueOf(summary);
        }
    }
}

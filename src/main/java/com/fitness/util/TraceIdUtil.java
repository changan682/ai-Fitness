package com.fitness.util;

import org.slf4j.MDC;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * traceId 工具类 — 跨语言调用链路串联
 * <p>
 * 规范生成规则：{@code <timestamp>-<random6hex>}，示例 {@code 20260730-143000-a1b2c3}。
 * <p>
 * 传递方式（与提示词第十章一致）：
 * <pre>
 * React  → Java      HTTP Header:   X-Trace-Id
 * Java   → Python    HTTP Header:   X-Trace-Id
 * Java   → RabbitMQ  Message Header: x-trace-id
 * Python → Java回调   HTTP Header:   X-Trace-Id
 * </pre>
 */
public final class TraceIdUtil {

    /** 链路追踪请求头名称 */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    /** MDC 中 traceId 的 key（与 logback 配置 %X{traceId} 对应） */
    public static final String MDC_TRACE_ID = "traceId";

    /** MDC 中 userId 的 key（JWT 校验通过后写入） */
    public static final String MDC_USER_ID = "userId";

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * 合法的外部 traceId 格式：仅允许字母/数字/连字符，长度 8-64。
     * 严格校验是为了防止日志注入（外部可传入含换行/控制字符的 Header）。
     */
    private static final Pattern VALID_TRACE_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    private static final SecureRandom RANDOM = new SecureRandom();

    private TraceIdUtil() {
        // 工具类，禁止实例化
    }

    /** 生成新的 traceId */
    public static String generate() {
        return LocalDateTime.now().format(TIMESTAMP_FORMAT) + "-" + randomHex6();
    }

    /** 校验上游传入的 traceId 是否可用（不可用时由调用方生成新的） */
    public static boolean isValid(String traceId) {
        return traceId != null && VALID_TRACE_ID.matcher(traceId).matches();
    }

    /** 获取当前线程 MDC 中的 traceId，无则返回 null */
    public static String currentTraceId() {
        return MDC.get(MDC_TRACE_ID);
    }

    /**
     * 获取当前 traceId，无则生成一个并写入 MDC。
     * <p>
     * 供异步线程（MQ 发送、RestClient 调用）使用：这些场景不在 TraceFilter 的
     * 请求线程内，需要通过此方法主动补齐 traceId 才能保持链路连续。
     */
    public static String currentOrCreate() {
        String traceId = MDC.get(MDC_TRACE_ID);
        if (traceId == null) {
            traceId = generate();
            MDC.put(MDC_TRACE_ID, traceId);
        }
        return traceId;
    }

    /** 生成 6 位十六进制随机串 */
    private static String randomHex6() {
        return String.format("%06x", RANDOM.nextInt(0x1000000));
    }
}

package com.fitness.util;

import com.fitness.config.CallbackProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 回调签名校验 — Python → Java 的 HMAC-SHA256 验签（规范 8.1 / 第十一章第 6 条）
 *
 * <h3>签名原文（两端必须逐字符一致）</h3>
 * <pre>
 *   canonical = method + "\n" + path + "\n" + X-Timestamp + "\n" + sha256Hex(rawBody)
 *   X-Signature = HMAC-SHA256(canonical, secret)      // 十六进制小写
 * </pre>
 *
 * <h3>为什么必须把 body 的哈希签进去</h3>
 * 早期实现只签 {@code taskId + timestamp}：攻击者只要拿到一次合法请求（或猜中 taskId 与时间戳），
 * 就能随意改写 body 里的 {@code userId}、{@code suggestionText}，而签名依然校验通过 ——
 * 防篡改能力形同虚设。把 body 哈希纳入签名后，body 改一个字节签名立刻失效。
 *
 * <h3>为什么必须用「原始请求体」算哈希</h3>
 * 若先反序列化成对象再重新序列化来算哈希，字段顺序、空格、转义差异都会让哈希与 Python 不一致，
 * 表现为「验签随机失败」—— 这类问题只在联调时暴露，排查成本极高。
 * 因此 Controller 用 {@code @RequestBody String} 直接接收原始报文，本类只接收 {@code byte[]}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HmacSignatureVerifier {

    /** 时间戳允许的最大偏移：规范要求 |now - X-Timestamp| ≤ 5 分钟（防重放） */
    public static final long MAX_TIMESTAMP_SKEW_MS = TimeUnit.MINUTES.toMillis(5);

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final CallbackProperties callbackProperties;

    // ==================== 校验 ====================

    /**
     * 校验时间戳是否在允许的偏移窗口内
     *
     * @param timestamp  {@code X-Timestamp} 头（毫秒时间戳字符串）
     * @param nowMillis  当前时间（毫秒），由调用方传入便于单测注入
     * @return 合法返回 true；缺失/非数字/超出 ±5 分钟返回 false
     */
    public boolean isTimestampFresh(String timestamp, long nowMillis) {
        if (timestamp == null || timestamp.isBlank()) {
            return false;
        }
        long sent;
        try {
            sent = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        long skew = Math.abs(nowMillis - sent);
        if (skew > MAX_TIMESTAMP_SKEW_MS) {
            // 过期或来自未来：可能是重放攻击，也可能是对端时钟没同步（日志里说清楚，便于排查）
            log.warn("回调时间戳超出允许偏移: 偏移={}ms, 上限={}ms, X-Timestamp={}",
                    skew, MAX_TIMESTAMP_SKEW_MS, timestamp);
            return false;
        }
        return true;
    }

    /**
     * 校验签名
     *
     * @param method    HTTP 方法（如 POST）
     * @param path      回调路径（如 /api/ai/callback/weekly-plan）
     * @param timestamp {@code X-Timestamp} 头的原值（参与签名的就是它，不要重新格式化）
     * @param rawBody   **原始**请求体字节
     * @param signature {@code X-Signature} 头
     * @return 通过返回 true
     */
    public boolean isSignatureValid(String method, String path, String timestamp,
                                    byte[] rawBody, String signature) {
        String secret = callbackProperties.getHmacSecret();
        if (secret == null || secret.isBlank()) {
            // 没配密钥就不能验签 —— 此时必须 fail-closed 拒绝，否则等于回调接口完全开放
            log.error("未配置 callback.hmac-secret（或 HMAC_SECRET 环境变量），无法校验回调签名，已拒绝");
            return false;
        }
        if (signature == null || signature.isBlank()) {
            return false;
        }

        String expected = sign(method, path, timestamp, rawBody, secret);
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] providedBytes = signature.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);

        // 常量时间比较：避免逐字节短路比较泄露时序侧信道
        boolean ok = MessageDigest.isEqual(expectedBytes, providedBytes);
        if (!ok) {
            log.warn("回调签名校验失败: method={}, path={}, 期望长度={}, 实收长度={}",
                    method, path, expectedBytes.length, providedBytes.length);
        }
        return ok;
    }

    // ==================== 签名计算（供 Python 侧对齐 / 单测复用） ====================

    /** 拼装签名原文：{@code method + "\n" + path + "\n" + timestamp + "\n" + sha256Hex(body)} */
    public static String canonicalString(String method, String path, String timestamp, byte[] rawBody) {
        return String.join("\n",
                method == null ? "" : method.toUpperCase(Locale.ROOT),
                path == null ? "" : path,
                timestamp == null ? "" : timestamp,
                sha256Hex(rawBody));
    }

    /** 计算签名（十六进制小写） */
    public static String sign(String method, String path, String timestamp, byte[] rawBody, String secret) {
        return hmacSha256Hex(secret, canonicalString(method, path, timestamp, rawBody));
    }

    /** SHA-256 → 十六进制小写 */
    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return toHex(digest.digest(data == null ? new byte[0] : data));
        } catch (Exception e) {
            // SHA-256 是 JDK 必备算法，取不到只可能是环境损坏 —— 不能静默降级成「不校验」
            throw new IllegalStateException("计算 SHA-256 失败", e);
        }
    }

    /** HMAC-SHA256 → 十六进制小写 */
    public static String hmacSha256Hex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return toHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("计算 HMAC-SHA256 失败", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}

package com.fitness.util;

import com.fitness.config.CallbackProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回调验签测试 —— 规范 8.1 / 第十一章第 6 条
 *
 * <h3>这里最重要的一条：跨语言黄金向量</h3>
 * {@link #javaAndPythonMustProduceTheSameSignature()} 里硬编码了一组
 * 「Python 侧算出来的」摘要与签名。它锁死的是**两端算法逐字符一致**这件事：
 * Java 与 Python 各自实现一遍 HMAC 很容易，但「拼串时少一个 \n、十六进制大小写不同、
 * body 编码不同」这类差异只会在联调时以「验签总是失败」的形式出现，且极难定位。
 * 有了这组向量，任何一端改动算法都会立刻在单测里暴露。
 */
@DisplayName("HmacSignatureVerifier：回调验签")
class HmacSignatureVerifierTest {

    private static final String SECRET = "shared-secret-test";
    private static final String PATH = "/api/ai/callback/weekly-plan";
    private static final String METHOD = "POST";
    private static final String TIMESTAMP = "1722355200000";

    /** 与 Python 侧 tests/test_hmac_signature.py 使用同一份 body（注意 \\n 是 JSON 里的转义换行） */
    private static final String BODY =
            "{\"taskId\":\"weekly-plan-1001-2026-08-03\",\"userId\":1001,"
                    + "\"weekStart\":\"2026-08-03\",\"suggestionText\":\"## 下周计划\\n深蹲加量\","
                    + "\"weekSummary\":{\"trainingDays\":5,\"totalVolume\":18500.5}}";

    /** Python `sha256_hex(body)` 的实测值 */
    private static final String PY_SHA256 =
            "8e627888f6ba4395916472aa25b55ef577d5bddacbd2355123dbcc5fb28e3431";

    /** Python `hmac_signature("POST", path, ts, body, secret)` 的实测值 */
    private static final String PY_SIGNATURE =
            "465f686de5993b579f9507086b25366a5412e14cad1ef079ae5a83b4c362fd49";

    private HmacSignatureVerifier verifier;

    @BeforeEach
    void setUp() {
        CallbackProperties properties = new CallbackProperties();
        properties.setHmacSecret(SECRET);
        verifier = new HmacSignatureVerifier(properties);
    }

    private byte[] bodyBytes() {
        return BODY.getBytes(StandardCharsets.UTF_8);
    }

    // ==================== 跨语言一致性 ====================

    @Test
    @DisplayName("跨语言黄金向量：Java 与 Python 必须算出同一个 sha256 与签名")
    void javaAndPythonMustProduceTheSameSignature() {
        assertEquals(PY_SHA256, HmacSignatureVerifier.sha256Hex(bodyBytes()),
                "body 的 sha256 与 Python 不一致 → 两端对「原始字节」的理解不同（编码/换行？）");

        String canonical = HmacSignatureVerifier.canonicalString(METHOD, PATH, TIMESTAMP, bodyBytes());
        assertEquals(METHOD + "\n" + PATH + "\n" + TIMESTAMP + "\n" + PY_SHA256, canonical,
                "签名原文必须严格是 method\\npath\\ntimestamp\\nsha256(body)");

        assertEquals(PY_SIGNATURE,
                HmacSignatureVerifier.sign(METHOD, PATH, TIMESTAMP, bodyBytes(), SECRET),
                "签名与 Python 不一致 → 验签会在联调时全量失败");
    }

    @Test
    @DisplayName("用 Python 算出的签名能通过 Java 校验（反向也成立）")
    void pythonSignatureShouldVerify() {
        assertTrue(verifier.isSignatureValid(METHOD, PATH, TIMESTAMP, bodyBytes(), PY_SIGNATURE));
    }

    // ==================== 篡改与伪造 ====================

    @Test
    @DisplayName("body 被篡改一个字节 → 验签失败（这是签名覆盖 body 的意义）")
    void tamperedBodyMustFail() {
        String tampered = BODY.replace("\"userId\":1001", "\"userId\":9999");
        assertFalse(verifier.isSignatureValid(
                        METHOD, PATH, TIMESTAMP, tampered.getBytes(StandardCharsets.UTF_8), PY_SIGNATURE),
                "只签 taskId+timestamp 的实现会放过这种篡改 —— 本用例就是防它回归");
    }

    @Test
    @DisplayName("method / path / timestamp 任一变化 → 验签失败")
    void otherSignedPartsMustMatter() {
        assertFalse(verifier.isSignatureValid("PUT", PATH, TIMESTAMP, bodyBytes(), PY_SIGNATURE));
        assertFalse(verifier.isSignatureValid(
                METHOD, "/api/ai/callback/other", TIMESTAMP, bodyBytes(), PY_SIGNATURE));
        assertFalse(verifier.isSignatureValid(METHOD, PATH, "1722355200001", bodyBytes(), PY_SIGNATURE));
    }

    @Test
    @DisplayName("换一个密钥 → 验签失败")
    void wrongSecretMustFail() {
        CallbackProperties other = new CallbackProperties();
        other.setHmacSecret("another-secret");
        assertFalse(new HmacSignatureVerifier(other)
                .isSignatureValid(METHOD, PATH, TIMESTAMP, bodyBytes(), PY_SIGNATURE));
    }

    @Test
    @DisplayName("十六进制大小写不敏感（Python 侧统一小写，这里也接受大写）")
    void signatureCaseInsensitive() {
        assertTrue(verifier.isSignatureValid(
                METHOD, PATH, TIMESTAMP, bodyBytes(), PY_SIGNATURE.toUpperCase()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "not-a-hex-signature", "465f686d"})
    @DisplayName("空/非法/被截断的签名一律失败")
    void malformedSignatureMustFail(String signature) {
        assertFalse(verifier.isSignatureValid(METHOD, PATH, TIMESTAMP, bodyBytes(), signature));
    }

    @Test
    @DisplayName("未配置密钥时必须 fail-closed（拒绝），绝不能默认放行")
    void blankSecretMustFailClosed() {
        CallbackProperties blank = new CallbackProperties();
        blank.setHmacSecret("  ");

        assertFalse(new HmacSignatureVerifier(blank)
                        .isSignatureValid(METHOD, PATH, TIMESTAMP, bodyBytes(), PY_SIGNATURE),
                "密钥没配就放行 = 回调接口对内网完全开放写入口");
    }

    // ==================== 时间戳窗口（防重放）====================

    @Test
    @DisplayName("时间戳窗口：±5 分钟内通过，超出失败")
    void timestampFreshnessWindow() {
        long now = 1722355200000L;

        assertTrue(verifier.isTimestampFresh(String.valueOf(now), now), "同一时刻应通过");
        assertTrue(verifier.isTimestampFresh(String.valueOf(now - 299_999), now), "差 1 秒到边界应通过");
        assertTrue(verifier.isTimestampFresh(String.valueOf(now + 299_999), now), "对端稍快也应通过");
        assertTrue(verifier.isTimestampFresh(String.valueOf(now - 300_000), now), "正好 5 分钟应通过（含边界）");

        assertFalse(verifier.isTimestampFresh(String.valueOf(now - 300_001), now), "超过 5 分钟应失败");
        assertFalse(verifier.isTimestampFresh(String.valueOf(now + 300_001), now), "来自未来过多也应失败");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "abc", "1722355200000.5", "null"})
    @DisplayName("时间戳缺失/非数字 → 失败")
    void malformedTimestampMustFail(String timestamp) {
        assertFalse(verifier.isTimestampFresh(timestamp, 1722355200000L));
    }

    @Test
    @DisplayName("时间戳为 null → 失败")
    void nullTimestampMustFail() {
        assertFalse(verifier.isTimestampFresh(null, 1722355200000L));
    }
}

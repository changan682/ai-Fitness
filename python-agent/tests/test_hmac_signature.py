"""回调签名（HMAC-SHA256）测试 —— 这是**安全修复**，不是格式美化。

## 为什么必须锁住「签名覆盖 body」

旧实现是 ``HMAC-SHA256(taskId + X-Timestamp, secret)``：签名原文里没有请求体，
于是签名与 body **完全解耦** —— 攻击者把 ``suggestionText`` 换成任意文案、
把 ``userId`` 改成别人，``X-Signature`` 依然校验通过。防篡改形同虚设。
本文件的核心用例就是 ``test_tampered_body_should_fail``：
它一旦失败，说明签名又退回了「不覆盖 body」的旧实现。

## 与 Java 侧的契约（规范 8.1 / 第 3612-3616 行）

    canonical = method + "\\n" + path + "\\n" + X-Timestamp + "\\n" + sha256Hex(rawBody)
    X-Signature = HMAC-SHA256(canonical, secret)   # 十六进制小写

Java 用 ``ContentCachingRequestWrapper`` 取**原始请求体**算哈希，
所以 Python 必须对「真正发出去的那份字节」签名；对 dict 重新序列化后算哈希，
会因为字段顺序/空格差异导致 Java 侧验签随机失败 —— 因此这里也锁住
「str 与 bytes 等价、拒绝 dict」这两条。
"""

from __future__ import annotations

import json

import pytest

from app.utils import (
    MAX_TIMESTAMP_SKEW_MS,
    SIGNATURE_HEADER,
    TIMESTAMP_HEADER,
    TRACE_ID_HEADER,
    build_callback_headers,
    canonical_signature_payload,
    hmac_sha256_hex,
    hmac_signature,
    sha256_hex,
    verify_hmac_signature,
)

CALLBACK_PATH = "/api/ai/callback/weekly-plan"

#: ⚠️ 测试**必须**使用与生产无关的密钥常量，绝不能照抄 `.env` 里的真实 HMAC_SECRET。
#: 真实密钥一旦出现在被提交的测试文件里，就等于把「回调接口唯一的身份凭证」公开了
#: —— 任何人都能伪造一条合法的 AI 周计划回调（该接口在 JWT 白名单里，**验签是唯一防线**）。
#: 因此这里用一个只在本文件出现的值：它只用于验证算法自洽，
#: 与「Java 与 Python 用同一密钥」这件事无关（那由跨语言黄金向量单独锁住）。
SECRET = "unit-test-only-hmac-secret"


def _weekly_plan_body(**overrides) -> bytes:
    """真实回调请求体（含中文，用于验证 UTF-8 编码一致性）。"""
    payload = {
        "taskId": "weekly-plan-1001-2026-08-03",
        "userId": 1001,
        "weekStart": "2026-08-03",
        "suggestionText": "## 📅 第32周训练复盘与下周计划\n\n深蹲容量较上周提升 5%。",
        "weekSummary": {"trainingDays": 5, "totalVolume": 18500.5, "avgRpe": 7.5},
    }
    payload.update(overrides)
    return json.dumps(payload, ensure_ascii=False).encode("utf-8")


def _sign(body: bytes, timestamp: str) -> str:
    return hmac_signature("POST", CALLBACK_PATH, timestamp, body, SECRET)


class TestCanonicalPayload:

    def test_payload_is_four_lines_with_body_digest(self):
        """规范化串必须是 method\\npath\\ntimestamp\\nsha256Hex(body) 四段。"""
        body = b'{"a":1}'
        payload = canonical_signature_payload("POST", CALLBACK_PATH, "1722355200000", body)

        method, path, timestamp, body_digest = payload.split("\n")
        assert method == "POST"
        assert path == CALLBACK_PATH
        assert timestamp == "1722355200000"
        assert body_digest == sha256_hex(body)
        assert len(body_digest) == 64, "sha256 十六进制应为 64 字符"

    def test_method_is_normalized_to_uppercase(self):
        """Java 侧固定传大写 POST；小写入参必须归一，否则签名对不上。"""
        assert canonical_signature_payload("post", CALLBACK_PATH, "1", b"{}") == \
            canonical_signature_payload("POST", CALLBACK_PATH, "1", b"{}")

    def test_sha256_hex_accepts_bytes_and_str(self):
        """Chinese 文本走 str 与 bytes 必须得到同一摘要（UTF-8 编码）。"""
        text = "深蹲时膝盖内扣怎么纠正"
        assert sha256_hex(text) == sha256_hex(text.encode("utf-8"))


class TestSignatureCoversBody:

    def test_same_body_should_verify(self):
        """同一份 body 的签名必须校验通过（正常回调路径）。"""
        body = _weekly_plan_body()
        timestamp = "1722355200000"
        signature = _sign(body, timestamp)

        assert verify_hmac_signature(
            "POST", CALLBACK_PATH, timestamp, body, signature, SECRET, now_ms=1722355200000
        )

    def test_tampered_body_should_fail(self):
        """**核心安全用例**：body 被改一个字节，签名必须失效。

        旧实现只签 taskId+timestamp，这里会误判为 True —— 防篡改形同虚设。
        """
        timestamp = "1722355200000"
        signature = _sign(_weekly_plan_body(), timestamp)

        tampered = _weekly_plan_body(userId=9999)  # 把 body 里的用户改成别人

        assert tampered != _weekly_plan_body()
        assert not verify_hmac_signature(
            "POST", CALLBACK_PATH, timestamp, tampered, signature, SECRET,
            now_ms=1722355200000,
        ), "请求体被篡改后签名必须校验失败（签名必须覆盖 body）"

    def test_reordering_keys_of_same_dict_should_fail(self):
        """字段顺序变化会让 sha256 改变 —— 这正是「不能对反序列化再序列化后的 JSON 签名」的原因。"""
        timestamp = "1722355200000"
        body = b'{"taskId":"t1","userId":1}'
        signature = _sign(body, timestamp)

        reordered = b'{"userId":1,"taskId":"t1"}'
        assert not verify_hmac_signature(
            "POST", CALLBACK_PATH, timestamp, reordered, signature, SECRET,
            now_ms=1722355200000,
        )

    def test_wrong_path_or_method_should_fail(self):
        """path/method 参与签名：换路径或换方法都不能通过。"""
        timestamp = "1722355200000"
        body = _weekly_plan_body()
        signature = _sign(body, timestamp)

        assert not verify_hmac_signature(
            "POST", "/api/ai/callback/other", timestamp, body, signature, SECRET,
            now_ms=1722355200000,
        )
        assert not verify_hmac_signature(
            "GET", CALLBACK_PATH, timestamp, body, signature, SECRET,
            now_ms=1722355200000,
        )

    def test_str_body_and_bytes_body_are_equivalent(self):
        """body 传 str（按 UTF-8 编码）与传同一份 bytes 必须等价。"""
        timestamp = "1722355200000"
        text = _weekly_plan_body().decode("utf-8")
        signature = _sign(text.encode("utf-8"), timestamp)

        assert verify_hmac_signature(
            "POST", CALLBACK_PATH, timestamp, text, signature, SECRET,
            now_ms=1722355200000,
        )

    def test_dict_body_is_rejected_loudly(self):
        """传 dict 必须立刻报错：静默算出一个与 Java 不一致的哈希会变成难查的联调故障。"""
        with pytest.raises(TypeError):
            hmac_signature("POST", CALLBACK_PATH, "1", {"taskId": "t1"}, SECRET)


class TestTimestampSkew:

    def test_timestamp_older_than_five_minutes_should_fail(self):
        """超过 5 分钟视为过期（可能是重放攻击），即使签名正确也拒绝。"""
        sent = 1722355200000
        body = _weekly_plan_body()
        signature = _sign(body, str(sent))

        too_late = sent + MAX_TIMESTAMP_SKEW_MS + 1
        assert not verify_hmac_signature(
            "POST", CALLBACK_PATH, str(sent), body, signature, SECRET, now_ms=too_late
        )

    def test_timestamp_from_the_future_should_fail(self):
        """时间戳超前同样拒绝（防重放的口径是 |now - ts| ≤ 5 分钟）。"""
        sent = 1722355200000
        body = _weekly_plan_body()
        signature = _sign(body, str(sent))

        too_early = sent - MAX_TIMESTAMP_SKEW_MS - 1
        assert not verify_hmac_signature(
            "POST", CALLBACK_PATH, str(sent), body, signature, SECRET, now_ms=too_early
        )

    def test_boundary_of_five_minutes_should_pass(self):
        """正好卡在 5 分钟边界上应通过（<= 而非 <）。"""
        sent = 1722355200000
        body = _weekly_plan_body()
        signature = _sign(body, str(sent))

        assert verify_hmac_signature(
            "POST", CALLBACK_PATH, str(sent), body, signature, SECRET,
            now_ms=sent + MAX_TIMESTAMP_SKEW_MS,
        )

    def test_non_numeric_timestamp_should_fail(self):
        """时间戳必须能解析成整数毫秒，否则一律不可信。"""
        body = _weekly_plan_body()
        assert not verify_hmac_signature(
            "POST", CALLBACK_PATH, "not-a-number", body, "deadbeef", SECRET
        )


class TestSignatureFormat:

    def test_uppercase_signature_should_be_accepted(self):
        """十六进制大小写不敏感：Java 侧若用 toUpperCase 也必须能校验通过。"""
        timestamp = "1722355200000"
        body = _weekly_plan_body()
        signature = _sign(body, timestamp)

        assert verify_hmac_signature(
            "POST", CALLBACK_PATH, timestamp, body, signature.upper(), SECRET,
            now_ms=1722355200000,
        )

    def test_signature_is_lowercase_hex_of_64_chars(self):
        """规范要求十六进制小写（Java 侧与 Python hexdigest 对齐）。"""
        signature = _sign(_weekly_plan_body(), "1722355200000")

        assert signature == signature.lower()
        assert len(signature) == 64
        int(signature, 16)  # 非十六进制会抛 ValueError

    def test_empty_or_missing_signature_should_fail(self):
        """签名缺失/空白/None 一律返回 False（不能抛异常，调用方按 9002 拒绝）。"""
        timestamp = "1722355200000"
        body = _weekly_plan_body()

        assert not verify_hmac_signature(
            "POST", CALLBACK_PATH, timestamp, body, "", SECRET, now_ms=1722355200000
        )
        assert not verify_hmac_signature(
            "POST", CALLBACK_PATH, timestamp, body, "   ", SECRET, now_ms=1722355200000
        )
        assert not verify_hmac_signature(
            "POST", CALLBACK_PATH, timestamp, body, None, SECRET, now_ms=1722355200000
        )

    def test_empty_secret_should_fail(self):
        """密钥未配置时必须拒绝，不能退化成「谁都能过」。"""
        timestamp = "1722355200000"
        body = _weekly_plan_body()
        assert not verify_hmac_signature(
            "POST", CALLBACK_PATH, timestamp, body, _sign(body, timestamp), "",
            now_ms=1722355200000,
        )

    def test_wrong_secret_should_fail(self):
        """密钥不一致（与 Java 侧没对齐）必须失败。"""
        timestamp = "1722355200000"
        body = _weekly_plan_body()
        signature = _sign(body, timestamp)

        assert not verify_hmac_signature(
            "POST", CALLBACK_PATH, timestamp, body, signature, "another-secret",
            now_ms=1722355200000,
        )


class TestBuildCallbackHeaders:

    def test_headers_should_be_self_consistent(self):
        """``build_callback_headers`` 产出的头必须能被自己的 verify 通过（自证回路）。"""
        body = _weekly_plan_body()
        headers = build_callback_headers("POST", CALLBACK_PATH, body, SECRET)

        assert headers["Content-Type"] == "application/json"
        assert TRACE_ID_HEADER in headers, "必须透传 traceId，保证 Python → Java 链路不断"
        assert headers[TIMESTAMP_HEADER].isdigit()

        assert verify_hmac_signature(
            "POST", CALLBACK_PATH, headers[TIMESTAMP_HEADER], body,
            headers[SIGNATURE_HEADER], SECRET,
        )

    def test_headers_signature_must_change_with_body(self):
        """同一时间戳下不同 body 必须得到不同签名（再次证明签名覆盖了 body）。"""
        headers_a = build_callback_headers("POST", CALLBACK_PATH, _weekly_plan_body(), SECRET)
        headers_b = build_callback_headers(
            "POST", CALLBACK_PATH, _weekly_plan_body(userId=2002), SECRET
        )
        assert headers_a[SIGNATURE_HEADER] != headers_b[SIGNATURE_HEADER]

    def test_headers_accept_str_body(self):
        """str body 也能构造头（内部按 UTF-8 编码，与 bytes 结果一致）。"""
        body_bytes = _weekly_plan_body()
        headers = build_callback_headers(
            "POST", CALLBACK_PATH, body_bytes.decode("utf-8"), SECRET
        )

        assert verify_hmac_signature(
            "POST", CALLBACK_PATH, headers[TIMESTAMP_HEADER], body_bytes,
            headers[SIGNATURE_HEADER], SECRET,
        )


class TestPrimitive:

    def test_hmac_sha256_hex_matches_reference_value(self):
        """用已知向量锁住底层实现（防止有人把 sha256 换成 md5 之类）。

        参考值由 .NET ``HMACSHA256`` / ``SHA256`` 独立算得（与 Python 实现互证）：
        ``HMACSHA256(key="key").ComputeHash(空)`` 与 ``SHA256(空数组)``。
        """
        assert hmac_sha256_hex("key", "") == \
            "5d5d139563c95b5967b9bd9a8c9b233a9dedb45072794cd232dc1b74832607d0"
        # sha256("") 的公开标准值
        assert sha256_hex(b"") == \
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

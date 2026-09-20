"""工具函数：traceId、日志、日期、数值格式化、HMAC 签名。

规范第十章「日志与链路追踪策略」的 Python 侧实现落地在这里。
"""

from __future__ import annotations

import hashlib
import hmac
import json
import logging
import os
import secrets
from contextvars import ContextVar
from datetime import datetime, timedelta
from typing import Any, Dict, Optional, Union

# ==================== traceId ====================

#: 跨语言统一链路追踪请求头（React → Java → Python → Java回调）
TRACE_ID_HEADER = "X-Trace-Id"

#: 回调签名请求头（规范 8.1）
SIGNATURE_HEADER = "X-Signature"
TIMESTAMP_HEADER = "X-Timestamp"

#: 服务名，写入结构化日志
SERVICE_NAME = "python-agent"

#: traceId 在协程/上下文中的存放位置；中间件里 set，业务代码里 get，
#: 因此日志、下游 HTTP 调用都能自动带上同一个 traceId。
trace_id_var: ContextVar[str] = ContextVar("trace_id", default="-")


def new_trace_id() -> str:
    """生成 traceId，规则：``<yyyyMMdd-HHmmss>-<random6hex>``。

    规范示例：``20260730-143000-a1b2c3``
    """
    return f"{datetime.now().strftime('%Y%m%d-%H%M%S')}-{secrets.token_hex(3)}"


def get_trace_id() -> str:
    """读取当前上下文的 traceId（无则返回 ``-``）。"""
    return trace_id_var.get()


def set_trace_id(trace_id: str):
    """写入 traceId，返回 token 供 reset 还原。"""
    return trace_id_var.set(trace_id)


def is_valid_trace_id(value: Optional[str]) -> bool:
    """简单校验外部传入的 traceId，防止超长/非法内容污染日志。"""
    if not value:
        return False
    value = value.strip()
    # 只允许 数字/字母/横线/下划线/点，长度 8-64
    return 8 <= len(value) <= 64 and all(c.isalnum() or c in "-_." for c in value)


# ==================== 异常 ====================

class AgentInputError(Exception):
    """入参在业务语义上不合法（HTTP 400）。

    与 Pydantic 的 422 校验错误区分开：
    - 422：格式/长度/范围不满足模型约束（如 question 超过 500 字）
    - 400：模型能通过但业务上不可执行（如 records 为空、图片为空）
    """

    def __init__(self, message: str):
        super().__init__(message)
        self.message = message


# ==================== 日志 ====================

class TraceIdFilter(logging.Filter):
    """把 contextvar 中的 traceId 注入每条日志记录（供格式串 ``%(traceId)s`` 使用）。"""

    def filter(self, record: logging.LogRecord) -> bool:
        if not hasattr(record, "traceId"):
            record.traceId = get_trace_id()
        return True


class TextLogFormatter(logging.Formatter):
    """开发环境：人类可读，带 traceId。"""

    def __init__(self) -> None:
        super().__init__(
            fmt="%(asctime)s %(levelname)-5s [%(traceId)s] %(name)s - %(message)s",
            datefmt="%Y-%m-%d %H:%M:%S",
        )


class JsonLogFormatter(logging.Formatter):
    """生产环境：结构化 JSON（规范第十章的日志示例格式）。

    不引入 structlog，用标准库 json 实现，避免额外依赖。
    """

    def format(self, record: logging.LogRecord) -> str:
        payload: Dict[str, Any] = {
            "timestamp": datetime.fromtimestamp(record.created)
            .astimezone()
            .isoformat(timespec="milliseconds"),
            "level": record.levelname,
            "traceId": getattr(record, "traceId", None) or get_trace_id(),
            "service": SERVICE_NAME,
            "class": record.name,
            "message": record.getMessage(),
        }
        if record.exc_info:
            payload["exception"] = self.formatException(record.exc_info)
        return json.dumps(payload, ensure_ascii=False)


_LOGGING_CONFIGURED = False


def setup_logging(level: str = "INFO", log_format: str = "text") -> None:
    """配置标准 logging：格式里始终包含 traceId。

    并且把 uvicorn 自带 logger 的 handler 清空、改为向 root 传播，
    这样 uvicorn 的启动/访问日志也会带上统一格式与 traceId。
    """
    global _LOGGING_CONFIGURED

    handler = logging.StreamHandler()
    handler.setFormatter(
        JsonLogFormatter() if log_format.lower() == "json" else TextLogFormatter()
    )
    handler.addFilter(TraceIdFilter())

    root = logging.getLogger()
    if not _LOGGING_CONFIGURED:
        root.handlers = [handler]
        root.setLevel(getattr(logging, level.upper(), logging.INFO))
        _LOGGING_CONFIGURED = True

    # 让 uvicorn 的日志走 root，统一格式（含 traceId）
    for name in ("uvicorn", "uvicorn.error", "uvicorn.access"):
        uv_logger = logging.getLogger(name)
        uv_logger.handlers = []
        uv_logger.propagate = True


# ==================== 时间 ====================

def now_local() -> datetime:
    """当前本地时间（naive，精确到秒）。

    刻意不用 UTC：规范示例是 ``2026-07-30T15:35:00``（无时区后缀），
    Java 侧按 ISO8601 解析成 String，保持与规范一致。
    精确到秒同样是为了对齐规范示例，避免 ``.554352`` 这类微秒尾巴漏进前端展示。
    """
    return datetime.now().replace(microsecond=0)


def now_iso() -> str:
    """当前时间的 ISO8601 字符串（如 ``2026-07-30T15:35:00``）。"""
    return now_local().isoformat(timespec="seconds")


def today_str() -> str:
    """今天，格式 ``yyyy-MM-dd``。"""
    return now_local().strftime("%Y-%m-%d")


def parse_date(value: Optional[str]) -> Optional[datetime]:
    """宽松解析 ``yyyy-MM-dd`` / ISO8601，失败返回 None（不抛异常）。"""
    if not value:
        return None
    text = str(value).strip()
    for fmt in ("%Y-%m-%d", "%Y/%m/%d", "%Y-%m-%d %H:%M:%S"):
        try:
            return datetime.strptime(text, fmt)
        except ValueError:
            continue
    try:
        return datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError:
        return None


def days_between(later: Optional[str], earlier: Optional[str]) -> Optional[int]:
    """两个日期相差天数（用于「距上次训练 N 天」这类文案），解析失败返回 None。"""
    a, b = parse_date(later), parse_date(earlier)
    if not a or not b:
        return None
    return abs((a - b).days)


def date_before(date_str: Optional[str], days: int) -> Optional[str]:
    """给定日期往前推 N 天，返回 ``yyyy-MM-dd``。"""
    base = parse_date(date_str)
    if not base:
        return None
    return (base - timedelta(days=days)).strftime("%Y-%m-%d")


# ==================== 数值 ====================

def to_float(value: Any, default: float = 0.0) -> float:
    """尽最大努力转 float：None/""/非法字符串都返回默认值（Mock 阶段要足够健壮）。"""
    if value is None or value == "":
        return default
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def to_int(value: Any, default: int = 0) -> int:
    """尽最大努力转 int。"""
    if value is None or value == "":
        return default
    try:
        return int(float(value))
    except (TypeError, ValueError):
        return default


def fmt_num(value: Any) -> str:
    """把数字变成人类可读文本：``60.0`` → ``60``，``62.50`` → ``62.5``，``3975`` → ``3975``。"""
    number = to_float(value)
    if number.is_integer():
        return str(int(number))
    text = f"{number:.2f}".rstrip("0").rstrip(".")
    return text


def fmt_pct(value: Any) -> str:
    """百分比文本，保留 1 位小数并带符号，如 ``+5.3%``。"""
    number = to_float(value)
    return f"{number:+.1f}%"


def round_to_plate(weight: float, step: float = 2.5) -> float:
    """把重量取整到可配重的最小步进（默认 2.5kg 的杠铃片）。"""
    if step <= 0:
        return weight
    return round(weight / step) * step


def mask_secret(value: Optional[str]) -> str:
    """敏感信息脱敏：仅保留前 3 后 4 位（规范第十章「敏感信息」强制要求）。"""
    if not value:
        return "(未配置)"
    text = str(value)
    if len(text) <= 8:
        return "*" * len(text)
    return f"{text[:3]}****{text[-4:]}"


# ==================== HMAC 回调签名 ====================
#
# ## 为什么签名必须覆盖请求体（安全修复，规范 8.1 / 第 3612-3616 行）
#
# 旧实现是 ``HMAC-SHA256(taskId + X-Timestamp, secret)``：签名原文里**没有 body**，
# 于是攻击者只要拿到一次合法请求（或猜中 taskId 与时间戳），就能把 body 内容
# 随意改成 ``suggestionText`` 里的任意文案、把 ``userId`` 换成别人，
# 而 X-Signature 依然校验通过 —— 防篡改能力形同虚设。
# 因此签名原文必须包含 body 的摘要，body 改一个字节签名立刻失效。
#
# ## 规范化的签名原文（与 Java 侧 ``AICallbackController`` 严格一致）
#
#     canonical = method + "\n" + path + "\n" + X-Timestamp + "\n" + sha256Hex(rawBody)
#     X-Signature = HMAC-SHA256(canonical, secret)   # 十六进制小写
#
# method 统一大写（POST），path 是回调路径（如 ``/api/ai/callback/weekly-plan``）。
#
# ## 必须对「发送出去的原始字节」签名
#
# Java 侧用 ``ContentCachingRequestWrapper`` / ``@RequestBody String`` 取**原始请求体**
# 算 sha256，所以 Python 必须对 ``json.dumps`` 之后、真正写到 socket 的那份字节做签名；
# **不能**先把 body 反序列化成 dict 再重新序列化来算哈希 ——
# 两次序列化的字段顺序、空格、``ensure_ascii`` 差异都会让哈希不同，
# 导致 Java 侧验签随机失败（且这类失败只在联调时暴露，排查成本极高）。

#: 时间戳允许的最大偏移（毫秒）：规范要求 |now - X-Timestamp| ≤ 5 分钟，用于防重放
MAX_TIMESTAMP_SKEW_MS = 300_000


def hmac_sha256_hex(secret: str, message: str) -> str:
    """HMAC-SHA256 → 十六进制小写字符串。"""
    return hmac.new(
        secret.encode("utf-8"), message.encode("utf-8"), hashlib.sha256
    ).hexdigest()


def _to_bytes(data: Union[bytes, str, None]) -> bytes:
    """把 body 归一成 bytes（``str`` 按 UTF-8 编码）。

    刻意不接受 dict/list：一旦有人图省事传了反序列化后的对象，
    这里必须**立刻报错**，而不是悄悄算出一个与 Java 侧不一致的哈希
    （那会变成联调时极难定位的「验签随机失败」）。
    """
    if data is None:
        return b""
    if isinstance(data, str):
        return data.encode("utf-8")
    if isinstance(data, (bytes, bytearray, memoryview)):
        return bytes(data)
    raise TypeError(
        f"签名 body 必须是 bytes 或 str（真正发送的原始请求体），实际传入 {type(data).__name__}；"
        f"不要传反序列化后的 dict，否则字段顺序/空格差异会让哈希与 Java 侧不一致。"
    )


def sha256_hex(data: Union[bytes, str]) -> str:
    """sha256 摘要 → 十六进制小写。

    :param data: ``bytes``（真正要发出去的原始请求体）或 ``str``（按 UTF-8 编码）
    """
    return hashlib.sha256(_to_bytes(data)).hexdigest()


def canonical_signature_payload(
    method: str,
    path: str,
    timestamp: str,
    body: Union[bytes, str, None],
) -> str:
    """拼出规范化的签名原文（与 Java 侧逐字符一致）。

    ``canonical = METHOD + "\\n" + path + "\\n" + timestamp + "\\n" + sha256Hex(body)``

    :param method: HTTP 方法，统一转大写（``post`` → ``POST``）
    :param path: 回调路径，如 ``/api/ai/callback/weekly-plan``
    :param timestamp: 毫秒时间戳字符串（即 ``X-Timestamp`` 头的原值）
    :param body: 请求体原始字节（``str`` 会被按 UTF-8 编码）
    """
    return "\n".join(
        (str(method).strip().upper(), str(path), str(timestamp), sha256_hex(body))
    )


def hmac_signature(
    method: str,
    path: str,
    timestamp: str,
    body: Union[bytes, str, None],
    secret: str,
) -> str:
    """按规范 8.1 生成回调签名（十六进制小写）。

    :param body: **必须是真正发出去的那份 body 字节**（见模块注释：Java 用原始请求体算哈希）
    """
    canonical = canonical_signature_payload(method, path, timestamp, body)
    return hmac_sha256_hex(secret, canonical)


def current_timestamp_ms() -> str:
    """当前毫秒时间戳字符串（``X-Timestamp`` 头）。"""
    return str(int(datetime.now().timestamp() * 1000))


def verify_hmac_signature(
    method: str,
    path: str,
    timestamp: str,
    body: Union[bytes, str, None],
    signature: str,
    secret: str,
    max_skew_ms: int = MAX_TIMESTAMP_SKEW_MS,
    now_ms: Optional[int] = None,
) -> bool:
    """校验回调签名（Java 侧用同一算法；这里供自测与对端联调复用）。

    校验三件事，任何一项不过都返回 ``False``（绝不抛异常，调用方按 9002 拒绝即可）：

    1. 签名存在（缺失/空白/None 直接拒绝；非十六进制的内容比较时自然不等）；
    2. 时间戳偏移 ``|now_ms - X-Timestamp| <= max_skew_ms``（默认 5 分钟，防重放）；
    3. 用常量时间比较 ``hmac.compare_digest`` 校验签名（十六进制大小写不敏感），
       避免逐字节短路比较泄露时序侧信道。

    :param now_ms: 仅用于测试注入时钟；``None`` 表示取当前时间
    """
    if not signature or not str(signature).strip():
        return False
    if not secret:
        return False

    # 时间戳必须是整数毫秒；非法值一律视为不可信
    try:
        sent_ms = int(str(timestamp).strip())
    except (TypeError, ValueError):
        return False

    current_ms = int(datetime.now().timestamp() * 1000) if now_ms is None else int(now_ms)
    if abs(current_ms - sent_ms) > int(max_skew_ms):
        # 过期或来自未来：可能是重放攻击，也可能是对端时钟没同步
        return False

    expected = hmac_signature(method, path, timestamp, body, secret)
    # 十六进制大小写不敏感：统一小写后再做常量时间比较
    return hmac.compare_digest(expected, str(signature).strip().lower())


def build_callback_headers(
    method: str,
    path: str,
    body: Union[bytes, str, None],
    secret: str,
) -> Dict[str, str]:
    """构造 Python → Java 回调所需的请求头（第 7 周 callback_client 使用）。

    自动带上当前 traceId，保证「Python → Java 回调」链路不断。

    :param body: **必须传入即将发送的那份 body 字节**（``str`` 会按 UTF-8 编码）。
        千万不要传反序列化后的 dict —— 中文与字段顺序会让哈希与 Java 侧不一致。
    """
    timestamp = current_timestamp_ms()
    return {
        "Content-Type": "application/json",
        TRACE_ID_HEADER: get_trace_id(),
        TIMESTAMP_HEADER: timestamp,
        SIGNATURE_HEADER: hmac_signature(method, path, timestamp, body, secret),
    }


def http_timeout_seconds() -> float:
    """下游 HTTP 调用的默认超时（可用环境变量覆盖，便于联调时放大）。"""
    return to_float(os.getenv("HTTP_TIMEOUT_SECONDS"), 10.0) or 10.0


def truncate(text: str, limit: int = 200) -> str:
    """截断长文本用于日志，避免刷屏。"""
    if text is None:
        return ""
    text = str(text)
    return text if len(text) <= limit else text[:limit] + f"...(共{len(text)}字)"


def first_sentences(text: str, count: int = 1) -> str:
    """取前 N 句（中文句号/换行切分），用于生成「简短回答」。"""
    if not text:
        return ""
    normalized = text.replace("\n", "。")
    parts = [p.strip() for p in normalized.split("。") if p.strip()]
    return "。".join(parts[:count]) + ("。" if parts else "")


# ==================== 大模型 JSON 输出解析 ====================

def extract_json_object(text: Optional[str]) -> Optional[dict]:
    """从大模型回复里抽出 JSON 对象，失败返回 ``None``（绝不抛异常）。

    现实情况是「要求模型只输出 JSON」并不总能得到纯 JSON，常见四种脏输出：

    1. 被 markdown 代码块包住：`` ```json {...} ``` ``
    2. 前后带客套话：``好的，以下是评估结果：{...} 希望对你有帮助``
    3. 末尾多出一个花括号：``{...}}``（实测 qwen-vl-max 会照抄 Prompt 里的示例括号）
    4. 末尾多逗号或夹带解释性文字

    解析顺序：先直接 ``json.loads``（最快路径），失败再剥掉代码块围栏，
    最后用**括号配对扫描**取出第一个完整的 JSON 对象 ——
    这一步能同时解决 2/3/4：既不依赖「最后一个 } 就是结尾」这种会被多余括号骗到的假设，
    也不会因为字符串值里含 ``{`` 或 ``}`` 而切错。

    :return: 解析出的 dict；拿不到合法对象时返回 None，由调用方决定降级策略
    """
    if not text:
        return None
    raw = str(text).strip()

    # 1) 直接解析
    parsed = _try_json(raw)
    if isinstance(parsed, dict):
        return parsed

    # 2) 剥掉 ```json ... ``` / ``` ... ``` 围栏
    if raw.startswith("```"):
        fenced = raw.split("```")
        # ["", "json\n{...}", ""] → 取中间那段
        for part in fenced[1:]:
            candidate = part.strip()
            if candidate.lower().startswith("json"):
                candidate = candidate[4:].strip()
            parsed = _try_json(candidate)
            if isinstance(parsed, dict):
                return parsed

    # 3) 括号配对扫描：取第一个完整对象（能容忍前后多余文字与多余的括号）
    balanced = _first_balanced_object(raw)
    if balanced:
        parsed = _try_json(balanced)
        if isinstance(parsed, dict):
            return parsed

    return None


def _first_balanced_object(text: str) -> Optional[str]:
    """扫描出 ``text`` 里第一个**括号配对完整**的 JSON 对象子串。

    实现要点：逐字符跟踪花括号深度，并跳过字符串字面量内部（含 ``\\`` 转义），
    这样 ``{"note": "他说 {这样}"}`` 里的花括号不会被误当成结构括号。
    找不到配对对象时返回 None。
    """
    start = text.find("{")
    while start != -1:
        depth = 0
        in_string = False
        escaped = False
        for index in range(start, len(text)):
            char = text[index]
            if in_string:
                if escaped:
                    escaped = False
                elif char == "\\":
                    escaped = True
                elif char == '"':
                    in_string = False
                continue
            if char == '"':
                in_string = True
            elif char == "{":
                depth += 1
            elif char == "}":
                depth -= 1
                if depth == 0:
                    return text[start:index + 1]
        # 从当前 '{' 起括号不配对（多半是文案里的花括号），换下一个 '{' 重试
        start = text.find("{", start + 1)
    return None


def _try_json(text: str) -> Optional[Any]:
    """``json.loads`` 的静默版本：任何异常都返回 None。"""
    try:
        return json.loads(text)
    except (TypeError, ValueError):
        return None

"""Python → Java 周计划回调客户端（规范 8.1，第 7 周）。

消费者拿到大模型生成的周计划后，通过本模块把结果交回 Java：
``POST {JAVA_CALLBACK_URL}/api/ai/callback/weekly-plan``，带 ``X-Signature`` / ``X-Timestamp``。

## 三条不可动摇的约定（都是踩过坑的口径）

1. **签名必须覆盖请求体**。签名原文（与 Java ``AICallbackController`` 逐字符一致）::

       canonical = method + "\n" + path + "\n" + X-Timestamp + "\n" + sha256Hex(rawBody)

   只签 ``taskId + timestamp`` 时 body 被篡改签名依然有效，防篡改形同虚设。
   实现上直接复用 :func:`app.utils.build_callback_headers`（已有单测锁住）。

2. **必须对「真正发出去的那份字节」签名**。所以这里先
   ``json.dumps(body, ensure_ascii=False).encode("utf-8")`` 得到 ``raw``，
   **同一个 ``raw`` 既用于 ``httpx`` 发送、也用于算签名**。
   绝不能把 dict 传给 :func:`build_callback_headers`：两次序列化的字段顺序、
   空格、``ensure_ascii`` 差异会让哈希与 Java 侧不一致，
   表现为「联调时验签随机失败」（Java 返回 9002），极难定位。

3. **Java 的 ``code=6001``（任务已处理，幂等返回）也算成功**，不能重投：
   那表示上一次回调其实已经落库成功，只是 ACK 丢了、消息被重投。
   若把它当失败重投，重试 3 次后消息会进死信队列，日志上看着像「任务失败」，
   实际上是重复回调 —— 必须按成功处理。

## 成功 / 失败的判定

- 成功：HTTP 200 **且** 响应体 ``code`` ∈ {0, 6001}
- 失败：HTTP 非 200、响应不是 JSON、``code`` 缺失或为其它值（如 9002 签名校验失败）
- 网络异常 / 超时：抛 :class:`CallbackError`，由消费者按失败处理
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass, field
from typing import Any, Dict, Optional

import httpx

from .config import Settings, settings as default_settings
from .utils import build_callback_headers, http_timeout_seconds, to_float, to_int, truncate

logger = logging.getLogger(__name__)

#: 回调路径（规范 8.1）。签名原文里用的就是它，改动必须与 Java 侧同步。
WEEKLY_PLAN_CALLBACK_PATH = "/api/ai/callback/weekly-plan"

#: 幂等返回：Java 表示「这个 taskId 已经处理过了」，Python 侧**视为成功**
CALLBACK_CODE_SUCCESS = 0
CALLBACK_CODE_ALREADY_DONE = 6001

#: 视为成功的 code 集合
SUCCESS_CODES = frozenset({CALLBACK_CODE_SUCCESS, CALLBACK_CODE_ALREADY_DONE})


class CallbackError(RuntimeError):
    """回调 Java 失败（网络异常 / 超时 / 未配置 HMAC 密钥）。"""


@dataclass
class CallbackResult:
    """一次回调的结果（消费者据此决定 ACK 还是重投）。"""

    status_code: int
    code: Optional[int] = None
    body: Dict[str, Any] = field(default_factory=dict)
    #: 供测试/日志追溯：真正发出去的字节数（中文按 UTF-8 计 3 字节）
    sent_bytes: int = 0

    @property
    def success(self) -> bool:
        """HTTP 200 且 ``code`` 为 0 或 6001（幂等已处理）。"""
        return self.status_code == 200 and self.code in SUCCESS_CODES


# ======================================================================
# 消息体 → 回调体
# ======================================================================


def extract_week_summary(message: Optional[dict]) -> Dict[str, Any]:
    """从 MQ 消息体里抽出规范 8.1 要求的**扁平** ``weekSummary``。

    ``{"trainingDays", "totalVolume", "avgRpe", "weightChange", "avgCalories"}``
    —— 注意是扁平结构，不是 MQ 消息里那种 ``trainingSummary`` / ``bodyMetrics`` /
    ``dietSummary`` 的三层嵌套；Java 侧按扁平字段接收，嵌套传过去会全部落成 null。

    字段缺失/类型不对一律按 0 处理（不抛异常）：周计划本身已经生成好了，
    不能因为一个统计字段缺失就把整条任务打成失败。
    """
    data = message if isinstance(message, dict) else {}
    training = data.get("trainingSummary")
    training = training if isinstance(training, dict) else {}
    metrics = data.get("bodyMetrics")
    metrics = metrics if isinstance(metrics, dict) else {}
    diet = data.get("dietSummary")
    diet = diet if isinstance(diet, dict) else {}

    return {
        "trainingDays": to_int(training.get("trainingDays")),
        "totalVolume": to_float(training.get("totalVolume")),
        "avgRpe": to_float(training.get("avgRpe")),
        "weightChange": to_float(metrics.get("weightChange")),
        "avgCalories": to_float(diet.get("avgDailyCalories")),
    }


def build_callback_body(message: Optional[dict], suggestion_text: str) -> Dict[str, Any]:
    """组装规范 8.1 的请求体（``taskId`` / ``userId`` / ``weekStart`` / ``suggestionText`` / ``weekSummary``）。"""
    data = message if isinstance(message, dict) else {}
    return {
        "taskId": str(data.get("taskId") or "").strip(),
        "userId": to_int(data.get("userId")),
        "weekStart": str(data.get("weekStart") or "").strip(),
        "suggestionText": suggestion_text or "",
        "weekSummary": extract_week_summary(data),
    }


def build_payload_bytes(body: Dict[str, Any]) -> bytes:
    """序列化成**唯一一份**待发送字节（签名与发送必须用同一个对象）。

    ``ensure_ascii=False`` + UTF-8：中文按原文发送，
    否则 ``\\u6760\\u94c3`` 这类转义会把 body 撑大好几倍（签名本身不受影响，
    但 Java 落库后前端要看到的是中文）。
    """
    return json.dumps(body, ensure_ascii=False).encode("utf-8")


# ======================================================================
# 发送
# ======================================================================


def _parse_code(response: httpx.Response) -> Optional[int]:
    """从响应体里取 ``code``；解析不出返回 None（按失败处理）。"""
    try:
        payload = response.json()
    except Exception:  # noqa: BLE001 - 非 JSON 响应（网关 HTML 等）一律按失败
        return None
    if not isinstance(payload, dict):
        return None
    code = payload.get("code")
    if code is None:
        return None
    try:
        return int(code)
    except (TypeError, ValueError):
        return None


def notify_weekly_plan(
    message: Optional[dict],
    suggestion_text: str,
    *,
    settings: Optional[Settings] = None,
    client: Optional[httpx.Client] = None,
) -> CallbackResult:
    """把周计划回调给 Java（带 HMAC 签名）。

    :param message: 原始 MQ 消息体（取 taskId / userId / weekStart 与统计字段）
    :param suggestion_text: 大模型（或本地拼装）生成的 Markdown 周计划
    :param settings: 便于测试注入；默认用全局配置
    :param client: 便于测试注入 ``httpx.Client``；``None`` 时用 ``httpx.post``
    :raises CallbackError: 未配置 HMAC 密钥、网络异常、超时
    :return: :class:`CallbackResult`（HTTP 非 200 / code 非 0/6001 时 ``success=False``）
    """
    cfg = settings or default_settings
    base_url = (cfg.java_callback_url or "").strip().rstrip("/")
    if not base_url:
        raise CallbackError("未配置 JAVA_CALLBACK_URL，无法回调 Java")
    secret = (cfg.hmac_secret or "").strip()
    if not secret:
        # 空密钥会让 Java 侧直接判 9002；这里提前失败，日志更清楚
        raise CallbackError("未配置 HMAC_SECRET（空值），无法生成有效回调签名")

    url = f"{base_url}{WEEKLY_PLAN_CALLBACK_PATH}"
    body = build_callback_body(message, suggestion_text)
    # ⚠️ 关键：raw 既用于签名也用于发送，绝不重新序列化
    raw = build_payload_bytes(body)
    headers = build_callback_headers("POST", WEEKLY_PLAN_CALLBACK_PATH, raw, secret)

    task_id = body["taskId"]
    logger.info(
        "回调 Java 周计划: taskId=%s userId=%s weekStart=%s url=%s 字节数=%d",
        task_id, body["userId"], body["weekStart"], url, len(raw),
    )

    timeout = http_timeout_seconds()
    try:
        if client is not None:
            response = client.post(url, content=raw, headers=headers, timeout=timeout)
        else:
            response = httpx.post(url, content=raw, headers=headers, timeout=timeout)
    except httpx.TimeoutException as exc:
        raise CallbackError(f"回调 Java 超时（{timeout}s）: {exc}") from exc
    except httpx.HTTPError as exc:
        raise CallbackError(f"回调 Java 网络异常: {type(exc).__name__}: {exc}") from exc

    code = _parse_code(response)
    result = CallbackResult(
        status_code=response.status_code, code=code, sent_bytes=len(raw)
    )
    try:
        parsed = response.json()
        if isinstance(parsed, dict):
            result.body = parsed
    except Exception:  # noqa: BLE001 - 只用于日志，解析失败无妨
        result.body = {}

    if result.success:
        logger.info(
            "回调 Java 成功: taskId=%s status=%s code=%s msg=%s",
            task_id, result.status_code, result.code, result.body.get("msg"),
        )
    else:
        logger.warning(
            "回调 Java 未成功: taskId=%s status=%s code=%s body=%s",
            task_id, result.status_code, result.code,
            truncate(response.text, 300),
        )
    return result

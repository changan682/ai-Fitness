"""Java 工具网关客户端 —— Python Agent 通过它**同步**读取用户私有数据（阶段二）。

## 为什么需要跨语言往返，而不是让 Python 直接查库

本项目铁律：「业务口径只写在 Java 一处」。一旦 Python 直连 MySQL，
「最新体重取档案还是取体测」「连续训练天数今日未练算不算断」这类口径
就要在两种语言里各写一遍 —— 项目已经因为这个踩过一次。

## 为什么复用回调那套 HMAC，而不是新建一套认证

两者本质相同：都是「内网 Python → Java」的机器间调用，调用方没有用户 Token。
既然 `utils.hmac_signature` / `build_callback_headers` 已经被跨语言黄金向量锁死，
再引入第二套签名只会多一处可能不一致的地方。

**关键点：`userId` 在签名保护的 body 里**，不在请求头 ——
否则攻击者改一个 userId 就能读别人的训练数据，而这种越权在日志里看起来完全正常。

## 失败语义

工具失败**必须变成工具结果**，而不是让整轮问答 500：
模型看到 `{"error": "..."}` 后可以调整策略或如实告知用户。
因此本模块**只抛 ``ToolGatewayError`` 一种异常**，且由调用方决定如何降级。
"""

from __future__ import annotations

import json
import logging
import time
from dataclasses import dataclass, field
from typing import Any, Dict, Optional

import httpx

from .config import Settings, settings as default_settings
from .utils import build_callback_headers, truncate

logger = logging.getLogger(__name__)


class ToolGatewayError(RuntimeError):
    """工具网关不可用（未配置、网络失败、签名失败、Java 返回业务错误）。"""


@dataclass
class ToolGatewayResult:
    """一次工具调用的原始返回。"""

    tool: str
    ok: bool
    data: Any = None
    meta: Dict[str, Any] = field(default_factory=dict)
    error: Optional[str] = None
    http_status: Optional[int] = None
    code: Optional[int] = None
    elapsed_ms: int = 0


def _resolve_base_url(cfg: Settings) -> str:
    """工具网关与回调共用一个 base_url。

    刻意不为工具单独加一个 `JAVA_TOOL_URL` 配置项：一旦两个变量指向不同主机，
    表现是「回调能通、工具不通」，排查成本远高于省下的那点配置灵活性。
    """
    base = (cfg.java_callback_url or "").strip().rstrip("/")
    if not base:
        raise ToolGatewayError("未配置 JAVA_CALLBACK_URL，无法调用 Java 工具网关")
    return base


def call_tool(
    tool: str,
    user_id: Optional[int],
    arguments: Optional[Dict[str, Any]] = None,
    *,
    settings: Optional[Settings] = None,
) -> ToolGatewayResult:
    """调用 Java 工具网关。

    :raises ToolGatewayError: 配置缺失 / 网络失败 / 签名失败 / 响应无法解析
    """
    cfg = settings or default_settings
    if user_id is None:
        raise ToolGatewayError("工具调用缺少 userId（无法确定代表哪个用户取数）")
    secret = (cfg.hmac_secret or "").strip()
    if not secret:
        raise ToolGatewayError("未配置 HMAC_SECRET，无法生成工具调用签名")

    base_url = _resolve_base_url(cfg)
    body = {
        "tool": tool,
        "user_id": int(user_id),
        "args": arguments or {},
    }
    # 同一个 raw 既用于签名也用于发送 —— 签名必须基于**原始字节**，
    # 否则字段顺序/空格差异会让两端算出的哈希不一致（表现为验签随机失败）
    raw = json.dumps(body, ensure_ascii=False).encode("utf-8")
    headers = build_callback_headers("POST", cfg.java_tool_path, raw, secret)

    url = base_url + cfg.java_tool_path
    started = time.monotonic()
    try:
        response = httpx.post(
            url,
            content=raw,
            headers=headers,
            timeout=cfg.java_tool_timeout_seconds,
        )
    except httpx.TimeoutException as exc:
        raise ToolGatewayError(
            f"调用 Java 工具网关超时（{cfg.java_tool_timeout_seconds}s）: {exc}"
        ) from exc
    except httpx.HTTPError as exc:
        raise ToolGatewayError(f"调用 Java 工具网关网络异常: {type(exc).__name__}: {exc}") from exc

    elapsed_ms = int((time.monotonic() - started) * 1000)

    try:
        payload = response.json()
    except Exception as exc:  # noqa: BLE001
        raise ToolGatewayError(
            f"工具网关响应不是 JSON（HTTP {response.status_code}）: {truncate(response.text, 200)}"
        ) from exc

    if not isinstance(payload, dict):
        raise ToolGatewayError(f"工具网关响应结构异常: {truncate(str(payload), 200)}")

    code = payload.get("code")
    message = str(payload.get("message") or payload.get("msg") or "").strip()

    if response.status_code != 200:
        return ToolGatewayResult(
            tool=tool, ok=False, error=message or f"HTTP {response.status_code}",
            http_status=response.status_code, code=code if isinstance(code, int) else None,
            elapsed_ms=elapsed_ms,
        )

    if code != 0:
        # Java 业务失败（例如参数不合法 9003 / 未支持的工具）——交给模型处理
        logger.info("工具网关返回业务错误: tool=%s code=%s msg=%s", tool, code, message)
        return ToolGatewayResult(
            tool=tool, ok=False, error=message or f"工具返回错误码 {code}",
            http_status=response.status_code, code=code if isinstance(code, int) else None,
            elapsed_ms=elapsed_ms,
        )

    data = payload.get("data")
    if not isinstance(data, dict):
        return ToolGatewayResult(
            tool=tool, ok=False, error="工具网关返回的 data 不是对象",
            http_status=response.status_code, code=code, elapsed_ms=elapsed_ms,
        )

    logger.info("工具网关调用成功: tool=%s userId=%s 耗时=%dms", tool, user_id, elapsed_ms)
    return ToolGatewayResult(
        tool=tool,
        ok=True,
        data=data.get("data"),
        meta=data.get("meta") if isinstance(data.get("meta"), dict) else {},
        http_status=response.status_code,
        code=code,
        elapsed_ms=elapsed_ms,
    )


__all__ = ["ToolGatewayError", "ToolGatewayResult", "call_tool"]

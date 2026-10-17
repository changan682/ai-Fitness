"""大模型客户端 —— DeepSeek（OpenAI 兼容协议）。

## 为什么单独一个模块

``agent.py`` 负责 Prompt 工程与业务组装，本模块只负责「把 messages 发出去、把文本收回来」。
分开的好处：Prompt 改动不碰网络代码，网络重试/超时改动不碰 Prompt；
测试时也能单独替换。

## 渐进增强

未配置 ``DEEPSEEK_API_KEY`` 时本模块不可用，``agent.py`` 会自动回落到第 4 周已验证的
本地拼装实现，因此**没有 Key 也能启动、也能通过全部冒烟测试**，
配好 Key 后同一套接口直接返回真实大模型文本。

## 降级

调用失败抛 ``LLMError``，由 ``agent.py`` 决定降级文案
（Java 侧最终会把它转成 6001 兜底文案，规范第十一章第 7 条）。
"""

from __future__ import annotations

import json
import logging
import time
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

import httpx

from .config import Settings, settings as default_settings
from .utils import mask_secret

logger = logging.getLogger(__name__)


class LLMError(RuntimeError):
    """大模型调用失败（未配置 Key、网络异常、限流、响应结构异常）。"""


@dataclass
class LLMReply:
    """一次工具调用型响应的解析结果（OpenAI 兼容 ``message`` 结构）。

    ``content`` 与 ``tool_calls`` **可能同时存在**：模型有时会先说一句
    "我查一下"再发起调用。两者都为空才算异常响应。
    """

    content: str = ""
    #: 每个元素形如 ``{"id": str, "name": str, "arguments": dict, "raw_arguments": Any}``。
    #: ``arguments`` 已尽量解析成 dict；模型给出坏 JSON 时退化为 ``{}`` 并保留原文。
    tool_calls: List[Dict[str, Any]] = field(default_factory=list)
    raw_message: Dict[str, Any] = field(default_factory=dict)
    usage: Optional[Dict[str, Any]] = None

    @property
    def has_tool_calls(self) -> bool:
        return bool(self.tool_calls)


class DeepSeekClient:
    """DeepSeek Chat 客户端（OpenAI 兼容的 /chat/completions）。"""

    def __init__(
        self,
        *,
        api_key: Optional[str],
        base_url: str = "https://api.deepseek.com",
        model: str = "deepseek-chat",
        timeout: float = 60.0,
        max_retries: int = 2,
    ):
        self._api_key = (api_key or "").strip()
        self._base_url = base_url.rstrip("/")
        self.model = model
        self._timeout = timeout
        self._max_retries = max(0, max_retries)

    @property
    def configured(self) -> bool:
        return bool(self._api_key)

    def describe(self) -> dict:
        return {
            "configured": self.configured,
            "model": self.model,
            "base_url": self._base_url,
            # 只输出脱敏后的 Key，绝不打印明文
            "api_key": mask_secret(self._api_key),
        }

    # ==================== 对话 ====================

    def chat(
        self,
        messages: List[dict],
        *,
        temperature: float = 0.7,
        max_tokens: int = 1200,
    ) -> str:
        """发送对话并返回助手回复文本。

        :raises LLMError: 未配置 Key、网络失败、HTTP 非 200、响应结构异常
        """
        if not self.configured:
            raise LLMError("未配置 DEEPSEEK_API_KEY，无法调用大模型")

        response = self._post(
            {
                "model": self.model,
                "messages": messages,
                "temperature": temperature,
                "max_tokens": max_tokens,
                "stream": False,
            }
        )
        return self._extract_content(response)

    def chat_with_tools(
        self,
        messages: List[dict],
        *,
        tools: List[dict],
        tool_choice: str = "auto",
        temperature: float = 0.3,
        max_tokens: int = 1200,
    ) -> LLMReply:
        """带工具（function calling）的单次对话，返回 :class:`LLMReply`。

        与 :meth:`chat` 共用同一套重试与超时策略（都走 :meth:`_post`），
        区别只有两处：请求体多 ``tools`` / ``tool_choice``，返回结构多 ``tool_calls``。

        **刻意不做循环**：本方法只发**一次**请求。
        “调工具 → 回填结果 → 再请求一次”由 ``agent._chat_with_agent_tools`` 负责 ——
        步数控制属于编排层，不属于传输层。

        :raises LLMError: 未配置 Key、网络失败、HTTP 非 200、响应结构异常
        """
        if not self.configured:
            raise LLMError("未配置 DEEPSEEK_API_KEY，无法调用大模型")
        if not tools:
            raise LLMError("chat_with_tools 需要至少一个工具定义")

        response = self._post(
            {
                "model": self.model,
                "messages": messages,
                "temperature": temperature,
                "max_tokens": max_tokens,
                "stream": False,
                "tools": tools,
                "tool_choice": tool_choice,
            }
        )
        return self._extract_reply(response)

    # ==================== 传输层（chat 与 chat_with_tools 共用） ====================

    def _post(self, payload: Dict[str, Any]) -> httpx.Response:
        """发请求并处理重试，返回状态码 200 的响应。

        重试策略（与改造前逐条一致，勿随意改动）：
        - 网络异常（``httpx.HTTPError``）→ 重试
        - **429 与 5xx → 重试**
        - 其它 4xx（如 401 鉴权失败）→ **立即失败，不重试**（重试无意义且浪费额度）
        """
        url = f"{self._base_url}/chat/completions"

        last_error: Optional[Exception] = None
        for attempt in range(self._max_retries + 1):
            started = time.monotonic()
            try:
                response = httpx.post(
                    url,
                    headers={
                        "Authorization": f"Bearer {self._api_key}",
                        "Content-Type": "application/json",
                    },
                    json=payload,
                    timeout=self._timeout,
                )
            except httpx.HTTPError as exc:
                last_error = exc
                logger.warning("大模型请求失败（第%d次）: %s", attempt + 1, exc)
                self._sleep_before_retry(attempt)
                continue

            elapsed_ms = (time.monotonic() - started) * 1000
            if response.status_code != 200:
                detail = response.text[:300]
                last_error = LLMError(f"HTTP {response.status_code}: {detail}")
                # 429（限流）与 5xx 值得重试；4xx 其它（如鉴权失败）重试无意义
                if response.status_code == 429 or response.status_code >= 500:
                    logger.warning("大模型返回 %d（第%d次），将重试: %s",
                                   response.status_code, attempt + 1, detail)
                    self._sleep_before_retry(attempt)
                    continue
                raise LLMError(
                    f"大模型调用失败 HTTP {response.status_code}: {detail}"
                )

            logger.info(
                "大模型调用成功: model=%s 耗时=%.0fms",
                self.model, elapsed_ms,
            )
            return response

        raise LLMError(f"大模型调用失败（已重试 {self._max_retries} 次）: {last_error}")

    def chat_with_system(
        self,
        system_prompt: str,
        user_prompt: str,
        *,
        temperature: float = 0.7,
        max_tokens: int = 1200,
    ) -> str:
        """便捷方法：一条 system + 一条 user。"""
        return self.chat(
            [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            temperature=temperature,
            max_tokens=max_tokens,
        )

    # ==================== 内部 ====================

    @staticmethod
    def _extract_content(response: httpx.Response) -> str:
        try:
            body = response.json()
            content = body["choices"][0]["message"]["content"]
        except Exception as exc:  # noqa: BLE001 - 响应结构异常统一转 LLMError
            raise LLMError(
                f"大模型响应结构异常: {response.text[:300]}"
            ) from exc

        if not content or not str(content).strip():
            raise LLMError("大模型返回了空内容")
        return str(content).strip()

    @classmethod
    def _extract_reply(cls, response: httpx.Response) -> LLMReply:
        """解析工具调用型响应（``message.content`` 可能为 null，此时不能套用空内容判定）。"""
        try:
            body = response.json()
            message = body["choices"][0]["message"]
        except Exception as exc:  # noqa: BLE001
            raise LLMError(
                f"大模型响应结构异常: {response.text[:300]}"
            ) from exc
        if not isinstance(message, dict):
            raise LLMError(f"大模型响应结构异常: {response.text[:300]}")

        raw_content = message.get("content")
        content = "" if raw_content is None else str(raw_content).strip()
        calls = cls._normalize_tool_calls(message.get("tool_calls"))

        # 两者都空才算真异常：“有内容”或“有工具调用”都是有效回复
        if not content and not calls:
            raise LLMError("大模型既没有返回内容也没有发起工具调用")

        usage = body.get("usage") if isinstance(body.get("usage"), dict) else None
        return LLMReply(
            content=content, tool_calls=calls, raw_message=message, usage=usage
        )

    @staticmethod
    def _normalize_tool_calls(raw_calls: Any) -> List[Dict[str, Any]]:
        """把模型返回的 ``tool_calls`` 归一成统一结构。

        容错点（实测模型会出这些）：
        - ``arguments`` 是**字符串**（标准）而不是 dict → 手动 ``json.loads``；
        - ``arguments`` 是坏 JSON / 空串 → **退化为 ``{}`` 并保留原文**，
          交给工具执行器决定是否用问题本身兜底，**绝不因此抛异常**；
        - ``id`` 缺失 → 生成一个稳定占位，保证回填 ``role=tool`` 时不缺字段。
        """
        if not isinstance(raw_calls, list):
            return []

        calls: List[Dict[str, Any]] = []
        for index, item in enumerate(raw_calls):
            if not isinstance(item, dict):
                continue
            function = item.get("function")
            if not isinstance(function, dict):
                continue
            name = str(function.get("name") or "").strip()
            if not name:
                continue

            raw_arguments = function.get("arguments")
            if isinstance(raw_arguments, dict):
                arguments: Dict[str, Any] = dict(raw_arguments)
            elif isinstance(raw_arguments, str) and raw_arguments.strip():
                try:
                    parsed = json.loads(raw_arguments)
                    arguments = dict(parsed) if isinstance(parsed, dict) else {}
                except (json.JSONDecodeError, TypeError, ValueError):
                    arguments = {}
            else:
                arguments = {}

            calls.append({
                "id": str(item.get("id") or f"call_{index}"),
                "name": name,
                "arguments": arguments,
                "raw_arguments": raw_arguments,
            })
        return calls

    def _sleep_before_retry(self, attempt: int) -> None:
        if attempt < self._max_retries:
            # 退避：0.8s、1.6s…避免瞬时打爆限流
            time.sleep(0.8 * (2 ** attempt))


_client_singleton: Optional[DeepSeekClient] = None


def get_llm(settings: Optional[Settings] = None) -> DeepSeekClient:
    """获取全局唯一的大模型客户端。"""
    global _client_singleton
    if _client_singleton is None:
        cfg = settings or default_settings
        _client_singleton = DeepSeekClient(
            api_key=cfg.deepseek_api_key,
            base_url=cfg.llm_base_url,
            model=cfg.llm_model,
        )
    return _client_singleton


def reset_llm_cache() -> None:
    """清空缓存（测试用）。"""
    global _client_singleton
    _client_singleton = None

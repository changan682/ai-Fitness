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

import logging
import time
from typing import List, Optional

import httpx

from .config import Settings, settings as default_settings
from .utils import mask_secret

logger = logging.getLogger(__name__)


class LLMError(RuntimeError):
    """大模型调用失败（未配置 Key、网络异常、限流、响应结构异常）。"""


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

        payload = {
            "model": self.model,
            "messages": messages,
            "temperature": temperature,
            "max_tokens": max_tokens,
            "stream": False,
        }
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

            text = self._extract_content(response)
            logger.info(
                "大模型调用成功: model=%s 耗时=%.0fms 回复长度=%d",
                self.model, elapsed_ms, len(text),
            )
            return text

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

"""多模态（视觉）大模型客户端 —— 通义千问 VL，当前用于动作姿态评估。

## 为什么单独一个模块

与 :mod:`app.llm` 的分工完全一致：``agent.py`` 负责 Prompt 工程与结果校验，
本模块只负责「把图片 + 文字发出去、把文本收回来」。
好处是换模型 / 换厂商只动这一个文件，Prompt 与业务逻辑不受影响；
测试时也能整体替换掉。

## 协议

百炼（DashScope）的 OpenAI 兼容接口 ``POST {base_url}/chat/completions``，
与纯文本模型的区别只在 ``content`` 是**数组**：

.. code-block:: json

    {"role": "user", "content": [
        {"type": "text", "text": "请评估这张深蹲照片的姿态"},
        {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,..."}}
    ]}

图片用 data URI 内联传输（不落地、不依赖公网可访问的 URL）。

## 模型选择（实测结论，2026-09 用本项目账号验证）

============== ======== ==================================================
模型            实测     说明
============== ======== ==================================================
qwen-vl-max    200 ✅   默认，姿态判断质量最好
qwen-vl-plus   200 ✅   更便宜，可作降级选项
qwen-vl-max-latest ❌   403 access_denied（账号未开通该版本）
qwen2.5-vl-7b-instruct ❌ 403 access_denied（账号未开通）
============== ======== ==================================================

另外实测到一条硬约束：**图片宽高都必须大于 10px**，否则返回
``InvalidParameter: The image length and width do not meet the model restrictions``。
Java 侧压缩后的图（最长边 ≤1024px）不受影响。

## 降级

任何失败（未配 Key、网络异常、限流、响应结构异常、内容被拦截）都抛
:class:`MultimodalError`，由调用方决定是降级还是如实报错 ——
姿态评估按规范第十一章第 7 条**如实报错**（Java 返回兜底文案），
而不是编一个看起来正常的分数。

其中「模型拒绝这次输入」（HTTP 400/422，例如图片宽高 ≤ 10px）抛
:class:`MultimodalInputError`（``MultimodalError`` 的子类）：
这是**入参问题**，调用方应按 HTTP 400 → Java 9003 回报，让用户换一张照片，
而不是伪装成「服务暂时不可用」让用户拿着同一张图反复重试。
"""

from __future__ import annotations

import logging
import time
from typing import Optional

import httpx

from .config import Settings, settings as default_settings
from .utils import mask_secret

logger = logging.getLogger(__name__)


class MultimodalError(RuntimeError):
    """多模态调用失败（未配置 Key、网络异常、限流、响应结构异常）。"""


class MultimodalInputError(MultimodalError):
    """模型**拒绝了这次输入**（HTTP 400/422），而不是服务本身不可用。

    继承 :class:`MultimodalError` 以保持既有 ``except MultimodalError`` 的兼容，
    同时让调用方能区分两种语义完全不同的失败：

    - **入参问题**（图片尺寸/格式不合法）→ 应回 9003，引导用户换一张照片；
    - **服务问题**（Key 未开通 403、限流 429、5xx）→ 应回 6002 兜底文案。

    两者若混为一谈，用户上传一张过小的图会看到「姿态评估服务暂时不可用，请稍后再试」，
    于是拿着同一张图反复重试 —— 这正是规范里要避免的那种误导。

    实测触发场景：图片宽或高 ≤ 10px 时模型返回
    ``InvalidParameter: The image length and width do not meet the model restrictions``。
    """


def sniff_image_mime(raw: bytes) -> str:
    """按魔数判断图片 MIME 类型，用于拼 data URI。

    不信文件名后缀（后缀随便改），只看真实字节头：
    - JPEG: ``FF D8 FF``
    - PNG:  ``89 50 4E 47 0D 0A 1A 0A``
    - WEBP: ``RIFF....WEBP``

    识别不出时回退 ``image/jpeg``（Java 侧压缩后统一输出 JPEG）。
    """
    if raw:
        if len(raw) >= 3 and raw[0] == 0xFF and raw[1] == 0xD8 and raw[2] == 0xFF:
            return "image/jpeg"
        if (len(raw) >= 8 and raw[0] == 0x89 and raw[1] == 0x50
                and raw[2] == 0x4E and raw[3] == 0x47):
            return "image/png"
        if len(raw) >= 12 and raw[0:4] == b"RIFF" and raw[8:12] == b"WEBP":
            return "image/webp"
    return "image/jpeg"


class QwenVLClient:
    """通义千问 VL 客户端（OpenAI 兼容的 /chat/completions）。"""

    def __init__(
        self,
        *,
        api_key: Optional[str],
        base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        model: str = "qwen-vl-max",
        timeout: float = 60.0,
        max_retries: int = 1,
    ):
        self._api_key = (api_key or "").strip()
        self._base_url = base_url.rstrip("/")
        self.model = model
        self._timeout = timeout
        self._max_retries = max(0, max_retries)

    @property
    def configured(self) -> bool:
        """是否配置了可用的 Key（不判断 Key 是否真的有效）。"""
        return bool(self._api_key)

    def describe(self) -> dict:
        """给健康检查用的自描述信息（Key 只输出脱敏值）。"""
        return {
            "configured": self.configured,
            "model": self.model,
            "base_url": self._base_url,
            "api_key": mask_secret(self._api_key),
        }

    # ==================== 视觉理解 ====================

    def describe_image(
        self,
        image_base64: str,
        user_prompt: str,
        *,
        system_prompt: Optional[str] = None,
        image_bytes: Optional[bytes] = None,
        temperature: float = 0.2,
        max_tokens: int = 800,
    ) -> str:
        """把「图片 + 提问」发给多模态模型，返回回复文本。

        :param image_base64: Base64 字符串（**不带** ``data:`` 前缀）
        :param image_bytes: 可选，原始图片字节；仅用于按魔数判断 MIME，
            不传时按 JPEG 处理（Java 侧压缩后总是 JPEG）
        :param temperature: 姿态评估需要稳定结论，默认取低温 0.2
        :raises MultimodalError: 未配置 Key、网络失败、HTTP 非 200、响应结构异常
        """
        if not self.configured:
            raise MultimodalError("未配置 DASHSCOPE_API_KEY，无法调用多模态模型")

        mime = sniff_image_mime(image_bytes or b"")
        messages = []
        if system_prompt:
            messages.append({"role": "system", "content": system_prompt})
        messages.append({
            "role": "user",
            "content": [
                {"type": "text", "text": user_prompt},
                {
                    "type": "image_url",
                    "image_url": {"url": f"data:{mime};base64,{image_base64}"},
                },
            ],
        })

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
                logger.warning("多模态请求失败（第%d次）: %s", attempt + 1, exc)
                self._sleep_before_retry(attempt)
                continue

            elapsed_ms = (time.monotonic() - started) * 1000
            if response.status_code != 200:
                detail = response.text[:300]
                # 429（限流）与 5xx 值得重试
                if response.status_code == 429 or response.status_code >= 500:
                    last_error = MultimodalError(f"HTTP {response.status_code}: {detail}")
                    logger.warning("多模态返回 %d（第%d次），将重试: %s",
                                   response.status_code, attempt + 1, detail)
                    self._sleep_before_retry(attempt)
                    continue

                # ---- 非重试类 4xx：必须区分「输入被拒」与「服务/配置问题」----
                if response.status_code in (400, 422):
                    # 模型明确拒绝这次输入（图片尺寸/格式不合法）→ 入参问题，重试无意义。
                    logger.warning("多模态拒绝该输入 HTTP %d: %s",
                                   response.status_code, detail)
                    raise MultimodalInputError(
                        f"多模态模型拒绝该输入 HTTP {response.status_code}: {detail}"
                    )

                # 401/403（Key 无效、模型未开通）等属于服务侧配置问题：
                # 同样不重试，但**绝不能**报成入参问题 —— 否则会误导用户去换照片，
                # 而真正该做的是检查 Key 与模型开通状态。
                logger.error("多模态调用被拒绝 HTTP %d: %s", response.status_code, detail)
                raise MultimodalError(
                    f"多模态调用失败 HTTP {response.status_code}: {detail}"
                )

            text = self._extract_content(response)
            logger.info(
                "多模态调用成功: model=%s 耗时=%.0fms 回复长度=%d",
                self.model, elapsed_ms, len(text),
            )
            return text

        raise MultimodalError(
            f"多模态调用失败（已重试 {self._max_retries} 次）: {last_error}"
        )

    # ==================== 内部 ====================

    @staticmethod
    def _extract_content(response: httpx.Response) -> str:
        try:
            body = response.json()
            content = body["choices"][0]["message"]["content"]
        except Exception as exc:  # noqa: BLE001 - 响应结构异常统一转 MultimodalError
            raise MultimodalError(f"多模态响应结构异常: {response.text[:300]}") from exc

        # 部分模型会把 content 返回成 [{"type":"text","text":"..."}] 数组，这里一并兼容
        if isinstance(content, list):
            content = "".join(
                part.get("text", "") for part in content if isinstance(part, dict)
            )

        if not content or not str(content).strip():
            raise MultimodalError("多模态模型返回了空内容")
        return str(content).strip()

    def _sleep_before_retry(self, attempt: int) -> None:
        if attempt < self._max_retries:
            # 退避：0.8s、1.6s…避免瞬时打爆限流
            time.sleep(0.8 * (2 ** attempt))


_client_singleton: Optional[QwenVLClient] = None


def get_multimodal(settings: Optional[Settings] = None) -> QwenVLClient:
    """获取全局唯一的多模态客户端。"""
    global _client_singleton
    if _client_singleton is None:
        cfg = settings or default_settings
        _client_singleton = QwenVLClient(
            api_key=cfg.dashscope_api_key,
            base_url=cfg.dashscope_base_url,
            model=cfg.qwen_vl_model,
            timeout=cfg.multimodal_timeout_seconds,
            max_retries=cfg.multimodal_max_retries,
        )
    return _client_singleton


def reset_multimodal_cache() -> None:
    """清空缓存（测试用）。"""
    global _client_singleton
    _client_singleton = None

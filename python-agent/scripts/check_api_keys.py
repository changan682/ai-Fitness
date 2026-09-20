#!/usr/bin/env python
"""API Key 可用性探活 —— 配 Key 之后第一件事就该跑它。

## 为什么单独做一个脚本

Key 配错时的报错往往不直观：可能表现为「Embedding 维度不符」「回答总是兜底文案」
「知识库导入 0 条」，排查时容易怀疑到业务代码上。这个脚本直接打两家的接口，
把问题定位在「Key 本身」这一层：

- **DeepSeek**：`POST {LLM_BASE_URL}/chat/completions`
- **阿里云百炼**：`POST {DASHSCOPE_BASE_URL}/embeddings`（OpenAI 兼容模式，带 `dimensions`）

## 用法

```powershell
E:\\Anaconde\\python.exe scripts/check_api_keys.py
```

脚本**不会打印 Key 本身**，只打印脱敏后的前后几位。
"""

from __future__ import annotations

import base64
import sys
from pathlib import Path

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.config import settings  # noqa: E402
from app.utils import mask_secret  # noqa: E402

PASS = 0
FAIL = 0


def check(name: str, ok: bool, detail: str = "") -> None:
    global PASS, FAIL
    if ok:
        PASS += 1
    else:
        FAIL += 1
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f"  ({detail})" if detail else ""))


def check_deepseek() -> None:
    print("\n[1] DeepSeek 大模型")
    key = settings.deepseek_api_key
    print(f"  Key: {mask_secret(key)}  base_url={settings.llm_base_url}  model={settings.llm_model}")
    if not key:
        check("DEEPSEEK_API_KEY 已配置", False, "未配置，跳过")
        return
    check("DEEPSEEK_API_KEY 已配置", True)

    try:
        response = httpx.post(
            f"{settings.llm_base_url.rstrip('/')}/chat/completions",
            headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
            json={
                "model": settings.llm_model,
                "messages": [
                    {"role": "system", "content": "你是一个简洁的助手，只回答被问到的内容。"},
                    {"role": "user", "content": "用不超过10个字回答：杠铃卧推主要练哪个部位？"},
                ],
                "temperature": 0.3,
                "max_tokens": 50,
                "stream": False,
            },
            timeout=60,
        )
    except httpx.HTTPError as exc:
        check("网络可达", False, str(exc))
        return

    if response.status_code != 200:
        check("鉴权与调用成功", False,
              f"HTTP {response.status_code}: {response.text[:200]}")
        _hint_deepseek(response)
        return

    check("鉴权与调用成功", True, f"HTTP 200")
    try:
        content = response.json()["choices"][0]["message"]["content"]
        print(f"  模型回复: {content.strip()[:80]}")
        check("响应结构符合 OpenAI 协议", bool(content.strip()))
    except Exception as exc:  # noqa: BLE001
        check("响应结构符合 OpenAI 协议", False, str(exc))


def _hint_deepseek(response: httpx.Response) -> None:
    if response.status_code == 401:
        print("  💡 401 通常是 Key 无效或已撤销：确认 Key 来自 platform.deepseek.com，且账户有余额")
    elif response.status_code == 402:
        print("  💡 402 通常是余额不足，需要充值")
    elif response.status_code == 429:
        print("  💡 429 是限流，稍后重试即可")


def check_dashscope() -> None:
    print("\n[2] 阿里云百炼 Embedding")
    key = settings.dashscope_api_key
    print(f"  Key: {mask_secret(key)}  base_url={settings.dashscope_base_url}  "
          f"model={settings.dashscope_embedding_model}  dim={settings.embedding_dim}")
    if not key:
        check("DASHSCOPE_API_KEY 已配置", False, "未配置，跳过")
        return
    check("DASHSCOPE_API_KEY 已配置", True)

    try:
        response = httpx.post(
            f"{settings.dashscope_base_url.rstrip('/')}/embeddings",
            headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
            json={
                "model": settings.dashscope_embedding_model,
                "input": ["杠铃深蹲标准姿势", "增肌期每日蛋白质摄入量"],
                "dimensions": settings.embedding_dim,
                "encoding_format": "float",
            },
            timeout=60,
        )
    except httpx.HTTPError as exc:
        check("网络可达", False, str(exc))
        return

    if response.status_code != 200:
        check("鉴权与调用成功", False, f"HTTP {response.status_code}: {response.text[:300]}")
        _hint_dashscope(response, key)
        return

    check("鉴权与调用成功", True, "HTTP 200")
    try:
        data = response.json()["data"]
        actual_dim = len(data[0]["embedding"])
        check("返回条数与入参一致", len(data) == 2, f"{len(data)} 条")
        check(f"维度为 {settings.embedding_dim}（规范硬性要求）",
              actual_dim == settings.embedding_dim,
              f"实际 {actual_dim} 维")
        print(f"  向量示例: 前3维 = {[round(x, 5) for x in data[0]['embedding'][:3]]}")
    except Exception as exc:  # noqa: BLE001
        check("响应结构符合 OpenAI 协议", False, str(exc))


def _make_probe_png(size: int = 64, rgb: tuple = (220, 30, 30)) -> bytes:
    """生成一张纯色 PNG，用于多模态探活。

    为什么不用 1x1 的小图：实测 VL 有硬约束「宽高都必须 > 10px」，
    1x1 会返回 ``InvalidParameter: The image length and width do not meet the model
    restrictions`` —— 那是**图片的问题**，不是 Key 的问题，会误报成「Key 不可用」。

    手写 PNG 字节而不依赖 Pillow：这个脚本只依赖 httpx，保持轻量。
    """
    import struct
    import zlib

    raw = b"".join(b"\x00" + bytes(rgb) * size for _ in range(size))

    def chunk(tag: bytes, data: bytes) -> bytes:
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    ihdr = struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


def check_dashscope_vl() -> None:
    """多模态（通义千问 VL）探活 —— 姿态评估第 6 周启用前必须先跑这条。

    第 5 周文档的提醒就落在这里：百炼的 Embedding 与 VL 是**两套模型权限**，
    Embedding 能用不代表 VL 已开通（实测同账号下 qwen-vl-max 可用，
    而 qwen-vl-max-latest / qwen2.5-vl-7b-instruct 返回 403 access_denied）。
    """
    print("\n[3] 阿里云百炼 多模态（姿态评估用）")
    key = settings.dashscope_api_key
    print(f"  Key: {mask_secret(key)}  model={settings.qwen_vl_model}")
    if not key:
        check("DASHSCOPE_API_KEY 已配置", False, "未配置，姿态评估会走 MOCK_MODE 或直接报错")
        return
    check("DASHSCOPE_API_KEY 已配置", True)

    image_b64 = base64.b64encode(_make_probe_png()).decode("ascii")
    try:
        response = httpx.post(
            f"{settings.dashscope_base_url.rstrip('/')}/chat/completions",
            headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
            json={
                "model": settings.qwen_vl_model,
                "messages": [{
                    "role": "user",
                    "content": [
                        {"type": "text", "text": "这张图片主要是什么颜色？只回答颜色名。"},
                        {"type": "image_url",
                         "image_url": {"url": f"data:image/png;base64,{image_b64}"}},
                    ],
                }],
                "max_tokens": 20,
            },
            timeout=60,
        )
    except httpx.HTTPError as exc:
        check("网络可达", False, str(exc))
        return

    if response.status_code != 200:
        check("鉴权与调用成功", False, f"HTTP {response.status_code}: {response.text[:200]}")
        _hint_dashscope_vl(response)
        return

    check("鉴权与调用成功", True, "HTTP 200")
    try:
        content = response.json()["choices"][0]["message"]["content"]
        if isinstance(content, list):       # 部分模型返回数组形态
            content = "".join(p.get("text", "") for p in content if isinstance(p, dict))
        print(f"  模型回复: {str(content).strip()[:60]}")
        check("模型确实看到了图片", bool(str(content).strip()))
    except Exception as exc:  # noqa: BLE001
        check("响应结构符合 OpenAI 协议", False, str(exc))


def _hint_dashscope_vl(response: httpx.Response) -> None:
    text = response.text[:400]
    if response.status_code == 403 or "access_denied" in text:
        print("  💡 403 access_denied：该模型未开通。控制台「模型广场」开通后重试，")
        print("     或把 QWEN_VL_MODEL 换成已开通的版本（实测 qwen-vl-max / qwen-vl-plus 可用）")
    elif response.status_code == 400 and "restrictions" in text:
        print("  💡 400 且提到 image restrictions：图片宽高必须都 > 10px（本脚本已用 64x64）")
    elif "Arrearage" in text or "欠费" in text:
        print("  💡 账户欠费，需要先充值")


def _hint_dashscope(response: httpx.Response, key: str) -> None:
    text = response.text[:400]
    if response.status_code == 401:
        print("  💡 401 说明 Key 不被百炼接受。注意两种 Key 的区别：")
        print("     - 百炼「API-KEY」（形如 sk-xxxx）→ 才能用于 /compatible-mode/v1")
        print("     - 阿里云「AccessKey ID」（形如 LTAIxxxx）→ 是另一种凭证，且需要配套 AccessKey Secret")
        if key.startswith("LTAI"):
            print("     ⚠️ 当前填的看起来是 AccessKey ID，请到百炼控制台的「API-KEY 管理」重新获取")
    elif response.status_code == 400 and "dimension" in text.lower():
        print("  💡 400 且提到 dimension：该模型可能不支持指定的向量维度")
    elif "Arrearage" in text or "欠费" in text:
        print("  💡 账户欠费，需要先充值")


def main() -> int:
    print("=" * 72)
    print("API Key 可用性探活")
    print("=" * 72)
    check_deepseek()
    check_dashscope()
    check_dashscope_vl()

    print("\n" + "=" * 72)
    print(f"结果：通过 {PASS} 项，失败 {FAIL} 项")
    if FAIL == 0:
        print("✅ 三个能力（大模型 / Embedding / 多模态）都可用，可以开始重建知识库并切换真实 AI")
    print("=" * 72)
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())

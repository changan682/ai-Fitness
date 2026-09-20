"""pytest 共享 fixture。

## 为什么用「本地桩服务」而不是 mock 掉 httpx

本项目的关键风险不在业务逻辑，而在**协议对齐**：
- 大模型走的是 OpenAI 兼容的 `/chat/completions`，请求体里的 `model`、
  `messages` 结构、`dimensions` 参数写错，真实调用时才报错；
- Embedding 走 `/embeddings`，分批大小、维度参数写错同理。

如果直接 mock `httpx.post`，就绕过了「请求体长什么样」这一层 —— 而恰恰是这层最容易错。
因此这里起一个**真实的本地 HTTP 服务**接收请求，既验证了请求体结构，
又不需要任何 API Key（Key 到位后只需换 base_url，接线已被证明）。

需要用到的第三方库只有 pytest 和 httpx（都是既有依赖），
服务端用标准库 `http.server`，不引入 respx/requests-mock 等新依赖。
"""

from __future__ import annotations

import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Callable, Dict, List, Tuple

import pytest

# 让 tests/ 能 import 到 app 包（tests 与 app 同级）
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))


class StubServer:
    """一个可记录请求、可定制响应的极简 HTTP 桩服务。"""

    def __init__(self, responder: Callable[[str, dict], Tuple[int, dict]]):
        self._responder = responder
        self.requests: List[Dict] = []
        self._httpd = ThreadingHTTPServer(("127.0.0.1", 0), _StubHandler)
        self._httpd.stub = self  # 让 handler 能拿回本对象
        self._thread = threading.Thread(target=self._httpd.serve_forever, daemon=True)
        self._thread.start()

    @property
    def base_url(self) -> str:
        host, port = self._httpd.server_address[:2]
        return f"http://{host}:{port}"

    def handle(self, path: str, body: dict) -> Tuple[int, dict]:
        self.requests.append({"path": path, "body": body})
        return self._responder(path, body)

    def last_body(self) -> dict:
        return self.requests[-1]["body"] if self.requests else {}

    def reset(self) -> None:
        self.requests.clear()

    def stop(self) -> None:
        self._httpd.shutdown()
        self._httpd.server_close()


class _StubHandler(BaseHTTPRequestHandler):
    def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler 的命名约定
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length).decode("utf-8") if length else "{}"
        try:
            body = json.loads(raw)
        except json.JSONDecodeError:
            body = {"_raw": raw}

        status, payload = self.server.stub.handle(self.path, body)
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self) -> None:  # noqa: N802
        status, payload = self.server.stub.handle(self.path, {})
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args) -> None:
        """静音访问日志，避免污染测试输出。"""


# ======================================================================
# 桩服务 fixture
# ======================================================================

@pytest.fixture
def stub_llm():
    """假的 OpenAI 兼容大模型服务。

    默认返回一个可识别的 Markdown 文本，用来证明「结果确实来自大模型」
    而不是本地拼装（本地拼装的文案里不会出现这个标记）。
    """
    state = {"text": "### 🤖 桩大模型返回的总结\n这是由桩服务生成的文本", "status": 200}

    def responder(path: str, body: dict) -> Tuple[int, dict]:
        if state["status"] != 200:
            return state["status"], {"error": {"message": "stub 强制错误"}}
        return 200, {
            "id": "chatcmpl-stub",
            "object": "chat.completion",
            "model": body.get("model", "stub"),
            "choices": [{
                "index": 0,
                "message": {"role": "assistant", "content": state["text"]},
                "finish_reason": "stop",
            }],
            "usage": {"prompt_tokens": 10, "completion_tokens": 10, "total_tokens": 20},
        }

    server = StubServer(responder)
    server.set_text = lambda text: state.update(text=text)          # type: ignore[attr-defined]
    server.set_status = lambda status: state.update(status=status)  # type: ignore[attr-defined]
    yield server
    server.stop()


@pytest.fixture
def stub_embedding():
    """假的 OpenAI 兼容 Embedding 服务。

    返回确定性向量（由文本长度派生），便于断言「维度」与「分批次数」。
    """
    state = {"dim": 768, "status": 200}

    def responder(path: str, body: dict) -> Tuple[int, dict]:
        if state["status"] != 200:
            return state["status"], {"error": {"message": "stub 强制错误"}}
        inputs = body.get("input") or []
        if isinstance(inputs, str):
            inputs = [inputs]
        dim = state["dim"]
        data = [
            {"object": "embedding", "index": i,
             "embedding": [round(0.001 * (i + 1), 6)] * dim}
            for i in range(len(inputs))
        ]
        return 200, {"object": "list", "data": data,
                     "model": body.get("model", "stub"),
                     "usage": {"prompt_tokens": 1, "total_tokens": 1}}

    server = StubServer(responder)
    server.set_dim = lambda dim: state.update(dim=dim)              # type: ignore[attr-defined]
    server.set_status = lambda status: state.update(status=status)  # type: ignore[attr-defined]
    yield server
    server.stop()


@pytest.fixture
def tmp_manifest_file():
    """给需要临时文件的测试提供一个可写路径。

    刻意不用 pytest 自带的 `tmp_path`：它落在系统临时目录，
    而本项目的开发环境（DSH 沙箱）对该目录没有写权限，会导致测试报
    PermissionError 而不是真正的断言失败 —— 那属于环境噪声，会掩盖真实问题。
    因此改用工作区内的 tests/.tmp/（已被 .gitignore 忽略）。
    """
    import uuid

    tmp_dir = Path(__file__).resolve().parent / ".tmp"
    tmp_dir.mkdir(parents=True, exist_ok=True)
    path = tmp_dir / f"manifest-{uuid.uuid4().hex}.json"
    yield path
    try:
        path.unlink(missing_ok=True)
    except OSError:
        pass


# ======================================================================
# 配置辅助
# ======================================================================

@pytest.fixture
def make_settings():
    """构造独立的 Settings（不读 .env，避免被本机配置干扰）。"""
    from app.config import Settings

    def _make(**overrides):
        base = dict(
            mock_mode=False,
            auto_init_knowledge=False,     # 测试里绝不自动连 Milvus
            embedding_provider="hashing",
            milvus_host="127.0.0.1",
            milvus_port=19530,
        )
        base.update(overrides)
        return Settings(_env_file=None, **base)

    return _make

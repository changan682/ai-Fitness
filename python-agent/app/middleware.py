"""traceId 中间件（规范第十章「链路追踪」强制要求）。

职责：
1. 从 ``X-Trace-Id`` 请求头读取 traceId；没有或非法则按
   ``<yyyyMMdd-HHmmss>-<random6hex>`` 生成；
2. 存入 ``contextvars.ContextVar``（业务代码/日志/下游调用都能取到）；
3. 同时写入 ``request.state.trace_id`` —— 异常处理器即使在本中间件之外运行
   （未预期异常由 ServerErrorMiddleware 兜底）也能拿到同一个 traceId；
4. 响应头回写 ``X-Trace-Id``（Java 侧 RestClient 可据此串联链路）。
"""

from __future__ import annotations

import logging
import time

from starlette.middleware.base import BaseHTTPMiddleware, RequestResponseEndpoint
from starlette.requests import Request
from starlette.responses import Response

from .utils import (
    TRACE_ID_HEADER,
    is_valid_trace_id,
    new_trace_id,
    set_trace_id,
    trace_id_var,
)

logger = logging.getLogger(__name__)


class TraceIdMiddleware(BaseHTTPMiddleware):
    """为每个请求建立 traceId 上下文。"""

    async def dispatch(
        self, request: Request, call_next: RequestResponseEndpoint
    ) -> Response:
        incoming = request.headers.get(TRACE_ID_HEADER)
        if is_valid_trace_id(incoming):
            trace_id = incoming.strip()
            source = "header"
        else:
            trace_id = new_trace_id()
            source = "generated"

        # 1) 写入 ContextVar（先 set，再 call_next，保证子任务继承同一 traceId）
        token = set_trace_id(trace_id)
        # 2) 写入 scope.state，供异常处理器读取
        request.state.trace_id = trace_id

        method, path = request.method, request.url.path
        start = time.perf_counter()
        logger.info(
            "→ 请求开始 method=%s path=%s client=%s traceIdFrom=%s",
            method,
            path,
            request.client.host if request.client else "-",
            source,
        )

        try:
            response = await call_next(request)
            duration_ms = (time.perf_counter() - start) * 1000
            # 3) 响应头回写 traceId
            response.headers[TRACE_ID_HEADER] = trace_id
            logger.info(
                "← 请求完成 method=%s path=%s status=%d durationMs=%.1f",
                method,
                path,
                response.status_code,
                duration_ms,
            )
            return response
        except Exception:
            duration_ms = (time.perf_counter() - start) * 1000
            logger.exception(
                "✗ 请求异常 method=%s path=%s durationMs=%.1f", method, path, duration_ms
            )
            raise
        finally:
            # 4) 还原上下文（响应头/日志已在上面用到 trace_id，不受影响）
            trace_id_var.reset(token)

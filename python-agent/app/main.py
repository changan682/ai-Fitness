"""FastAPI 应用入口 + 路由注册。

接口清单（全部挂在 ``/agent/v1`` 前缀下，仅内网供 Java 调用）：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET  | ``/agent/v1/health``           | Python 服务健康检查（规范 10.2） |
| POST | ``/agent/v1/summary``          | 训练智能总结（规范 7.1 的 Python 侧） |
| POST | ``/agent/v1/recommend``        | 动作智能推荐（规范 7.2） |
| POST | ``/agent/v1/pose-evaluate``    | 动作姿态评估（规范 7.3） |
| POST | ``/agent/v1/chat``             | 知识库 RAG 问答（规范 7.4） |
| GET  | ``/agent/v1/knowledge/health`` | Milvus 知识库健康（规范 7.5） |

统一响应信封：``{"success": bool, "message": str, "data": object|null}``
统一错误信封：404/405/422/400/500 全部走下面的 ``@app.exception_handler``，
不会把 FastAPI 默认的 ``{"detail": ...}`` 格式漏给 Java。
"""

from __future__ import annotations

import asyncio
import logging
from contextlib import asynccontextmanager
from typing import Any, Optional

from fastapi import APIRouter, FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from . import __version__, agent
from .config import settings
from .middleware import TraceIdMiddleware
from .models import (
    AgentResponse,
    ChatRequest,
    PoseEvaluateRequest,
    RecommendRequest,
    SummaryRequest,
)
from .utils import (
    TRACE_ID_HEADER,
    AgentInputError,
    get_trace_id,
    mask_secret,
    new_trace_id,
    set_trace_id,
    setup_logging,
)

# 日志必须在任何业务日志之前配置好，保证格式里带 traceId
setup_logging(settings.log_level, settings.log_format)
logger = logging.getLogger(__name__)


# ============================================================
# 统一信封工具
# ============================================================

def _envelope(success: bool, message: str, data: Optional[dict]) -> dict:
    """构造统一响应信封（data 用 json 模式序列化：datetime → ISO8601 字符串）。"""
    return AgentResponse(success=success, message=message, data=data).model_dump(
        mode="json"
    )


def _ok(data: Optional[dict]) -> AgentResponse:
    """成功响应：``{"success": true, "message": "ok", "data": {...}}``"""
    return AgentResponse(success=True, message="ok", data=data)


def _request_trace_id(request: Request) -> str:
    """取当前请求的 traceId。

    ``request.state.trace_id`` 由 TraceIdMiddleware 写入（写在 scope 上），
    即使响应由中间件之外的 ServerErrorMiddleware 兜底也能取到同一个 traceId。
    """
    return (
        getattr(request.state, "trace_id", None)
        or get_trace_id()
        or new_trace_id()
    )


def _error_response(status_code: int, message: str, trace_id: str) -> JSONResponse:
    """错误响应：统一信封 + 统一回写 X-Trace-Id 响应头。"""
    return JSONResponse(
        status_code=status_code,
        content=_envelope(False, message, None),
        headers={TRACE_ID_HEADER: trace_id},
    )


def _format_validation_error(exc: RequestValidationError) -> str:
    """把 Pydantic 校验错误压成一句可读中文，逐字段列出。"""
    details = []
    for error in exc.errors()[:5]:
        parts = [
            str(part)
            for part in error.get("loc", ())
            if part not in ("body", "query", "path")
        ]
        field = ".".join(parts) or "body"
        details.append(f"{field}: {error.get('msg', '校验失败')}")
    return "请求参数校验失败 → " + "；".join(details)


# ============================================================
# 生命周期
# ============================================================

@asynccontextmanager
async def lifespan(app: FastAPI):
    """启动/关闭钩子。

    第 4 周只打印配置与 Mock 提醒；
    第 5-6 周在此处接入知识库初始化（连接 Milvus → 检查集合 → 导入 seed_knowledge.json）。
    """
    logger.info("=" * 72)
    logger.info(
        "AI 健身私教 Python Agent 启动中: version=%s env=%s（实际监听地址由 uvicorn 命令决定，配置默认 %s:%s）",
        __version__,
        settings.agent_env,
        settings.agent_host,
        settings.agent_port,
    )
    logger.info(
        "配置: mockMode=%s llmModel=%s llmApiConfigured=%s deepseekApiKey=%s",
        settings.mock_mode,
        settings.llm_model,
        settings.llm_api_configured,
        mask_secret(settings.deepseek_api_key),
    )
    logger.info(
        "配置: milvus=%s:%s collection=%s embeddingProvider=%s embeddingDim=%d",
        settings.milvus_host,
        settings.milvus_port,
        settings.milvus_collection,
        settings.resolved_embedding_provider,
        settings.embedding_dim,
    )
    if settings.mock_mode:
        logger.warning(
            "MOCK_MODE=true：训练总结/动作推荐/姿态评估走本地实现，不调用大模型。"
            "知识问答仍使用真实 Milvus 检索，但回答由本地拼装（未经 AI 润色）。"
            "配置 DEEPSEEK_API_KEY 并把 MOCK_MODE 置为 false 即切换为真实 AI。"
        )
    if not settings.embedding_api_configured:
        logger.warning(
            "未配置真实 Embedding（当前 provider=%s）：RAG 检索只能命中词法相近的内容。"
            "配置 DASHSCOPE_API_KEY 后会自动切换到百炼 text-embedding-v3（%d 维）。",
            settings.resolved_embedding_provider,
            settings.embedding_dim,
        )
    if settings.enable_fault_injection:
        logger.warning(
            "故障注入接口已开启：GET /agent/v1/_debug/fault?mode=500|slow|400（仅用于联调验证降级链路）"
        )
    logger.info("=" * 72)

    # --- 知识库初始化（规范第 3544 行：启动时自动完成，确保 RAG 启动即可用）---
    # 失败不影响服务启动：Milvus 没起时，第 1-4 周的接口与 /health 仍要能用，
    # RAG 相关接口会走 agent.chat_with_rag 的三层降级。
    if settings.auto_init_knowledge:
        try:
            from .knowledge_init import ensure_knowledge_base

            init_result = ensure_knowledge_base(force_reload=settings.force_reload_knowledge)
            logger.info(
                "知识库初始化完成: collection=%s 写入=%d 跳过=%s 当前总数=%s provider=%s",
                init_result["collection"],
                init_result["loaded"],
                init_result["skipped"],
                init_result["total_documents"],
                init_result["embedding_provider"],
            )
        except Exception as exc:  # noqa: BLE001 - 初始化失败必须降级而非崩掉服务
            logger.error(
                "知识库初始化失败（服务继续启动，RAG 接口将降级为纯 LLM 或内置知识库）: %s",
                exc,
            )
    else:
        logger.info("AUTO_INIT_KNOWLEDGE=false，跳过知识库初始化")

    yield
    logger.info("AI 健身私教 Python Agent 已停止")


app = FastAPI(
    title="AI 健身私教 & 体态管家 · Python Agent",
    description=(
        "跨语言 AI 能力服务（Java BFF 内网调用）。第 4 周：骨架 + Mock 实现。"
    ),
    version=__version__,
    lifespan=lifespan,
)

# traceId 中间件（必须在路由之前注册）
app.add_middleware(TraceIdMiddleware)

# 说明：本服务只允许 Java 内网调用（规范第十一章第 3 条），
# 因此刻意不配置 CORS —— 跨域由 Java 侧的 CorsConfig 统一处理。


# ============================================================
# 路由
# ============================================================

router = APIRouter(prefix="/agent/v1", tags=["agent"])


@router.get("/health", response_model=AgentResponse, summary="Python AI 服务健康检查")
def health() -> AgentResponse:
    """服务健康检查。Java 启动时与 ``/api/v1/health`` 都会调用。"""
    return _ok(agent.check_agent_health().model_dump(mode="json"))


@router.post("/summary", response_model=AgentResponse, summary="训练智能总结")
def summary(request: SummaryRequest) -> AgentResponse:
    """根据当日训练记录 + 上次对比数据生成 Markdown 训练总结。"""
    logger.info(
        "收到训练总结请求: userId=%s date=%s records=%d hasComparison=%s",
        request.user_id,
        request.date,
        len(request.records or []),
        request.comparison is not None,
    )
    result = agent.generate_summary(
        user_id=request.user_id,
        date_str=request.date,
        records=request.records,
        comparison=request.comparison,
    )
    logger.info("训练总结响应: summaryLength=%d", len(result.summary))
    return _ok(result.model_dump(mode="json"))


@router.post("/recommend", response_model=AgentResponse, summary="动作智能推荐")
def recommend(request: RecommendRequest) -> AgentResponse:
    """按目标肌群与可用器械返回结构化动作推荐。"""
    logger.info(
        "收到动作推荐请求: targetMuscle=%s equipment=%s count=%d",
        request.target_muscle,
        request.equipment,
        request.count,
    )
    result = agent.recommend_actions(
        target_muscle=request.target_muscle,
        equipment=request.equipment,
        count=request.count,
    )
    logger.info("动作推荐响应: count=%d", len(result.recommendations))
    return _ok(result.model_dump(mode="json"))


@router.post("/pose-evaluate", response_model=AgentResponse, summary="动作姿态评估")
def pose_evaluate(request: PoseEvaluateRequest) -> AgentResponse:
    """对 Base64 图片做姿态评估（图片由 Java 侧转 Base64 后转发）。

    注意：**不打印** image_base64 内容，只记录长度（避免日志被巨型字符串污染）。
    """
    logger.info(
        "收到姿态评估请求: actionName=%s imageBase64Length=%d",
        request.action_name,
        len(request.image_base64 or ""),
    )
    result = agent.evaluate_pose(
        image_base64=request.image_base64,
        action_name=request.action_name,
    )
    logger.info(
        "姿态评估响应: score=%d level=%s", result.score, result.score_level
    )
    return _ok(result.model_dump(mode="json"))


@router.post("/chat", response_model=AgentResponse, summary="知识库 RAG 问答")
def chat(request: ChatRequest) -> AgentResponse:
    """健身知识库问答（第 5-6 周走 Embedding → Milvus → LLM）。"""
    logger.info(
        "收到知识库问答请求: userId=%s category=%s questionLength=%d",
        request.user_id,
        request.category,
        len(request.question or ""),
    )
    result = agent.chat_with_rag(
        question=request.question,
        category=request.category,
        user_id=request.user_id,
    )
    logger.info("知识库问答响应: sources=%d", len(result.sources))
    return _ok(result.model_dump(mode="json"))


@router.get(
    "/knowledge/health",
    response_model=AgentResponse,
    summary="Milvus 知识库健康检查",
)
def knowledge_health() -> AgentResponse:
    """Milvus 知识库健康状态（**真实探测**：连接状态、文档数、索引类型与维度、
    最后更新时间读自知识库清单的 built_at）。"""
    return _ok(agent.get_knowledge_health().model_dump(mode="json"))


if settings.enable_fault_injection:
    # 默认关闭（ENABLE_FAULT_INJECTION=false）。
    # 打开后用于验证 Java 侧 RestClient 的降级链路：
    #   mode=500  → 未预期异常 → Java 收到 6002 AI_RESPONSE_ERROR
    #   mode=slow → 长时间不返回 → Java 收到 6001 AI_TIMEOUT（读超时）
    #   mode=400  → 业务错误 → Java 收到 6002 AI_RESPONSE_ERROR
    @router.get(
        "/_debug/fault",
        response_model=AgentResponse,
        summary="故障注入（默认关闭，仅联调用）",
    )
    async def fault_injection(mode: str = "500") -> AgentResponse:
        logger.warning("故障注入被调用: mode=%s", mode)
        if mode == "slow":
            await asyncio.sleep(30)
        if mode == "400":
            raise HTTPException(status_code=400, detail="故障注入：模拟业务错误")
        raise RuntimeError("故障注入：模拟未预期异常")


# ============================================================
# 全局异常处理（统一信封，绝不让 FastAPI 默认格式漏出去）
# ============================================================

@app.exception_handler(RequestValidationError)
async def validation_exception_handler(
    request: Request, exc: RequestValidationError
) -> JSONResponse:
    """422：Pydantic 校验失败（字段缺失/超长/超范围）。"""
    message = _format_validation_error(exc)
    logger.warning(
        "请求参数校验失败: method=%s path=%s → %s",
        request.method,
        request.url.path,
        message,
    )
    return _error_response(422, message, _request_trace_id(request))


@app.exception_handler(AgentInputError)
async def agent_input_error_handler(
    request: Request, exc: AgentInputError
) -> JSONResponse:
    """400：模型能通过但业务上不可执行（无训练记录、图片为空……）。"""
    logger.warning(
        "入参业务校验失败: method=%s path=%s → %s",
        request.method,
        request.url.path,
        exc.message,
    )
    return _error_response(400, exc.message, _request_trace_id(request))


@app.exception_handler(StarletteHTTPException)
async def http_exception_handler(
    request: Request, exc: StarletteHTTPException
) -> JSONResponse:
    """FastAPI/Starlette 抛出的 HTTPException（含 404、405）。

    注意：必须注册 **Starlette 的 HTTPException（基类）**，而不是 ``fastapi.HTTPException``。
    路由未匹配时的 404 由 Starlette 内部抛出基类异常，只注册子类会漏掉，
    结果把 FastAPI 默认的 ``{"detail": "Not Found"}`` 格式漏给 Java。
    """
    message = exc.detail if isinstance(exc.detail, str) else "请求处理失败"
    logger.warning(
        "HTTP 异常: method=%s path=%s status=%s → %s",
        request.method,
        request.url.path,
        exc.status_code,
        message,
    )
    return _error_response(exc.status_code, message, _request_trace_id(request))


@app.exception_handler(Exception)
async def unhandled_exception_handler(request: Request, exc: Exception) -> JSONResponse:
    """500：兜底捕获所有未预期异常（规范第十一章第 2 条：不把堆栈抛给调用方）。

    这个处理器运行在 TraceIdMiddleware **之外**（Starlette 的 ServerErrorMiddleware 兜底），
    此时中间件的 ``finally`` 已经把 contextvar 还原了，所以这里要用
    ``request.state.trace_id``（写在 scope 上，不随 contextvar 还原）重新装回去，
    否则这条最需要排查的错误日志会丢掉 traceId。
    """
    trace_id = _request_trace_id(request)
    set_trace_id(trace_id)
    logger.exception(
        "未预期异常: method=%s path=%s type=%s",
        request.method,
        request.url.path,
        type(exc).__name__,
    )
    return _error_response(
        500, "AI 服务内部错误，请稍后重试 😅", trace_id
    )


app.include_router(router)


if __name__ == "__main__":
    # 便于本地直接 `python -m app.main` 启动（等价于 uvicorn 命令）
    import uvicorn

    uvicorn.run(app, host=settings.agent_host, port=settings.agent_port)

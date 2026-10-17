"""Agent 工具定义与执行器。

## 两类工具，两种执行方式

| 类型 | 工具 | 在哪执行 | 为什么 |
|:--|:--|:--|:--|
| **本地工具** | ``search_knowledge`` | Python 进程内（``RagEngine``） | 知识库是公共数据，Python 自己就有引擎，没必要绕一圈 |
| **远程工具** | ``get_weekly_stats`` / ``get_training_records`` / ``get_body_trend`` | **Java 工具网关**（HMAC 签名） | 涉及用户私有数据，业务口径必须只写在 Java 一处（BFF 原则） |

远程工具走 {@link tool_gateway}，与每周复盘回调**共用同一套 HMAC** ——
不新增任何认证机制。

## 为什么工具结果要带 ``ok`` / ``summary`` / ``elapsed_ms``

本项目有硬约束「不允许有看起来像真的假结果」。工具失败时如果只把错误文本丢给模型，
模型很可能顺着编下去。因此执行结果必须让编排层能判断：**这次调用成功了没**、
**拿到了什么**（``summary`` 给前端展示）、**花了多久**。
它们最终会变成响应里的 ``knowledge_used`` / ``steps`` / ``degradation_reason``。
"""

from __future__ import annotations

import json
import logging
import time
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

from . import tool_gateway

logger = logging.getLogger(__name__)

# ============================================================
# 工具名
# ============================================================

SEARCH_KNOWLEDGE = "search_knowledge"
GET_WEEKLY_STATS = "get_weekly_stats"
GET_TRAINING_RECORDS = "get_training_records"
GET_BODY_TREND = "get_body_trend"

#: 在 Python 进程内执行的工具
LOCAL_TOOLS = frozenset({SEARCH_KNOWLEDGE})
#: 需要经 Java 工具网关执行的工具（用户私有数据）
REMOTE_TOOLS = frozenset({GET_WEEKLY_STATS, GET_TRAINING_RECORDS, GET_BODY_TREND})

#: 允许检索的知识分类（与 ``knowledge_init.ALLOWED_CATEGORIES`` 一致）
ALLOWED_CATEGORIES = (
    "动作要领",
    "营养饮食",
    "恢复与伤病",
    "训练计划",
    "补剂科普",
)

MAX_TOP_K = 10
MIN_TOP_K = 1


# ============================================================
# 工具声明（OpenAI 兼容的 function calling schema）
# ============================================================

AGENT_TOOL_SCHEMAS: List[Dict[str, Any]] = [
    {
        "type": "function",
        "function": {
            "name": SEARCH_KNOWLEDGE,
            "description": (
                "检索内置健身知识库（200 条，覆盖：动作要领、营养饮食、恢复与伤病、"
                "训练计划、补剂科普）。仅当问题需要**通用知识依据**时调用，"
                "例如动作怎么做才标准、为什么这么练、营养与恢复原理、补剂怎么用。"
                "**不要为了显得严谨而检索**：闲聊、追问上文、"
                "以及需要用户个人数据的问题都不该调用它。"
                "检索不到就如实说明知识库没有这方面内容，绝不要编造依据。"
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {
                        "type": "string",
                        "description": "用于向量检索的查询语句，尽量保留用户原问题里的专业名词。",
                    },
                    "category": {
                        "type": "string",
                        "enum": list(ALLOWED_CATEGORIES),
                        "description": "可选，限定知识分类；不确定就不要传。",
                    },
                    "top_k": {"type": "integer", "description": "可选，返回条数，默认 5，最多 10。"},
                },
                "required": ["query"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": GET_WEEKLY_STATS,
            "description": (
                "查询用户某一周的训练统计（**这个是算好的汇总数据，不是记录明细**）："
                "训练天数、动作条数、总容量(kg)、平均RPE、容量Top5动作、体重区间变化、日均热量。"
                "用户问「这周练得怎么样 / 有进步吗 / 容量多少 / 练了几天 / 体重变化」时用它 —— "
                "**绝不要自己估算这些数字**。不传 weekStart 表示本周。"
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "weekStart": {
                        "type": "string",
                        "description": "周一日期 yyyy-MM-dd；不传表示本周。传任意一天会归一到该周周一。",
                    }
                },
                "required": [],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": GET_TRAINING_RECORDS,
            "description": (
                "按日期区间（可选动作名）查询用户的训练记录明细，用于回答"
                "「我昨天练了什么 / 卧推用了多少 kg / 这个动作做了几组」这类**看单条记录**的问题。"
                "需要汇总统计（容量、天数、趋势）时请改用 get_weekly_stats，"
                "明细有返回条数上限，直接统计会漏数。"
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "startDate": {"type": "string", "description": "开始日期 yyyy-MM-dd"},
                    "endDate": {"type": "string", "description": "结束日期 yyyy-MM-dd"},
                    "actionName": {
                        "type": "string",
                        "description": "可选，动作名模糊匹配（如「卧推」）",
                    },
                    "size": {
                        "type": "integer",
                        "description": "可选，返回条数，默认 20，最多 50",
                    },
                },
                "required": ["startDate", "endDate"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": GET_BODY_TREND,
            "description": (
                "查询用户的体测趋势：每天的体重/腰围/臂围/腿围/体脂率，"
                "以及 7 日滑动平均（样本不足 3 天时该值为 null，表示**样本不够**而不是没有变化）。"
                "用户问「我体重变化 / 腰围小了没 / 体脂率」时用它。"
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "startDate": {"type": "string", "description": "开始日期 yyyy-MM-dd"},
                    "endDate": {"type": "string", "description": "结束日期 yyyy-MM-dd"},
                },
                "required": ["startDate", "endDate"],
            },
        },
    },
]


# ============================================================
# 执行结果
# ============================================================

@dataclass
class ToolOutcome:
    """一次工具调用的结果。

    ``content`` 是回填给模型（``role=tool``）的**文本**；
    其余字段给编排层用来决定 ``data_source`` / ``sources`` / ``steps`` / ``degraded``。
    """

    name: str
    ok: bool
    content: str
    #: 给前端展示的一句话摘要（steps 面板里用），例如「训练 4 天 / 容量 18650kg」
    summary: str = ""
    #: 结构化知识来源（仅 ``search_knowledge`` 会填）
    sources: List[Dict[str, Any]] = field(default_factory=list)
    #: 本次是否**真的拿到了知识依据**（仅对知识库工具有意义）
    knowledge_used: bool = False
    best_score: float = 0.0
    below_floor: bool = False
    error: Optional[str] = None
    elapsed_ms: int = 0


# ============================================================
# 执行入口
# ============================================================

def execute_tool(
    name: str,
    arguments: Optional[Dict[str, Any]] = None,
    *,
    user_id: Optional[int] = None,
    fallback_query: Optional[str] = None,
    category: Optional[str] = None,
    default_top_k: int = 5,
) -> ToolOutcome:
    """执行一个工具。

    **任何异常都不外抛**，一律转成 ``ok=False`` 的 :class:`ToolOutcome` ——
    工具失败是 Agent 循环里的正常分支（模型会据此调整），不是系统故障。

    :param name: 工具名
    :param arguments: 模型给出的参数（可能为空 dict —— 模型有时把参数写成坏 JSON）
    :param user_id: 代表哪个用户查询（远程工具必需；本地知识库工具不需要）
    :param fallback_query: 知识检索没给 query 时用用户原问题兜底
    :param category: 请求级别的分类过滤
    """
    arguments = arguments if isinstance(arguments, dict) else {}

    if name in REMOTE_TOOLS:
        return _execute_remote(name, arguments, user_id)
    if name == SEARCH_KNOWLEDGE:
        return _execute_search_knowledge(
            arguments, fallback_query=fallback_query, category=category, default_top_k=default_top_k
        )

    message = f"未知工具：{name}"
    return ToolOutcome(
        name=name, ok=False, error=message, summary="工具不存在",
        content=json.dumps({"error": message}, ensure_ascii=False),
    )


# ============================================================
# 本地工具：知识库检索
# ============================================================

def _clamp_top_k(value: Any, default: int) -> int:
    try:
        number = int(value)
    except (TypeError, ValueError):
        return default
    return max(MIN_TOP_K, min(MAX_TOP_K, number))


def _normalize_category(value: Any) -> Optional[str]:
    text = str(value or "").strip()
    return text if text in ALLOWED_CATEGORIES else None


def _execute_search_knowledge(
    arguments: Dict[str, Any],
    *,
    fallback_query: Optional[str],
    category: Optional[str],
    default_top_k: int,
) -> ToolOutcome:
    started = time.monotonic()

    query = str(arguments.get("query") or "").strip() or str(fallback_query or "").strip()
    if not query:
        message = "search_knowledge 缺少 query 参数"
        return ToolOutcome(
            name=SEARCH_KNOWLEDGE, ok=False, error=message, summary="缺少查询词",
            content=json.dumps({"error": message}, ensure_ascii=False),
        )

    effective_category = _normalize_category(arguments.get("category")) or _normalize_category(category)
    top_k = _clamp_top_k(arguments.get("top_k"), default_top_k)

    try:
        from .rag import RAG_CONTEXT_FLOOR, get_rag_engine
    except Exception as exc:  # noqa: BLE001
        message = f"知识库模块不可用：{type(exc).__name__}: {exc}"
        logger.error("工具执行失败（导入 rag 失败）: %s", message)
        return ToolOutcome(
            name=SEARCH_KNOWLEDGE, ok=False, error=message, summary="知识库不可用",
            content=json.dumps({"error": message}, ensure_ascii=False),
            elapsed_ms=int((time.monotonic() - started) * 1000),
        )

    try:
        hits, context = get_rag_engine().retrieve_with_context(
            query, category=effective_category, top_k=top_k
        )
    except Exception as exc:  # noqa: BLE001
        message = f"知识库检索不可用：{type(exc).__name__}: {exc}"
        logger.warning("工具执行失败（%s）: %s", SEARCH_KNOWLEDGE, message)
        return ToolOutcome(
            name=SEARCH_KNOWLEDGE, ok=False, error=message, summary="检索失败",
            content=json.dumps({"error": message}, ensure_ascii=False),
            elapsed_ms=int((time.monotonic() - started) * 1000),
        )

    elapsed_ms = int((time.monotonic() - started) * 1000)
    best_score = float(hits[0].score) if hits else 0.0
    sources = [
        {
            "category": hit.category,
            "title": hit.title,
            "content": hit.content,
            "score": round(float(hit.score), 4),
        }
        for hit in hits
    ]

    if not hits:
        payload = {
            "hits": 0,
            "knowledge": "",
            "note": (
                "知识库中未检索到与该问题相关的资料。"
                "请如实说明知识库没有这方面内容，然后基于通用健身知识给出建议，"
                "不要编造知识库依据，也不要虚构参考来源。"
            ),
        }
        return ToolOutcome(
            name=SEARCH_KNOWLEDGE, ok=True, summary="知识库未命中",
            content=json.dumps(payload, ensure_ascii=False),
            sources=[], knowledge_used=False, best_score=0.0, below_floor=True,
            elapsed_ms=elapsed_ms,
        )

    below_floor = best_score < RAG_CONTEXT_FLOOR
    payload = {
        "hits": len(hits),
        "best_score": round(best_score, 4),
        "knowledge": context,
    }
    if below_floor:
        payload["note"] = (
            f"以下资料的相似度偏低（最高 {best_score:.4f}），可能答不上这个问题。"
            "请先判断它是否真的回答了问题；答不上就按「知识库没有覆盖」处理，"
            "改为通用知识回答并明确说明，不要强行引用。"
        )

    return ToolOutcome(
        name=SEARCH_KNOWLEDGE, ok=True,
        summary=f"命中 {len(hits)} 条知识（最高 {best_score:.2f}）",
        content=json.dumps(payload, ensure_ascii=False),
        sources=sources, knowledge_used=True, best_score=best_score,
        below_floor=below_floor, elapsed_ms=elapsed_ms,
    )


# ============================================================
# 远程工具：Java 工具网关
# ============================================================

def _execute_remote(name: str, arguments: Dict[str, Any], user_id: Optional[int]) -> ToolOutcome:
    started = time.monotonic()
    try:
        result = tool_gateway.call_tool(name, user_id, arguments)
    except tool_gateway.ToolGatewayError as exc:
        message = str(exc)
        logger.warning("工具网关调用失败(%s): %s", name, message)
        return ToolOutcome(
            name=name, ok=False, error=message, summary="取数失败",
            content=json.dumps({"error": message}, ensure_ascii=False),
            elapsed_ms=int((time.monotonic() - started) * 1000),
        )

    elapsed_ms = result.elapsed_ms or int((time.monotonic() - started) * 1000)

    if not result.ok:
        # 业务失败（参数不合法/工具不支持）也要让模型看到 —— 它会据此调整或如实告知
        message = result.error or "工具返回失败"
        return ToolOutcome(
            name=name, ok=False, error=message, summary="取数失败",
            content=json.dumps({"error": message}, ensure_ascii=False),
            elapsed_ms=elapsed_ms,
        )

    meta = result.meta or {}
    payload = {"result": result.data, "meta": meta}
    # 被截断时必须显式告诉模型，否则它会把"只看到前 20 条"当成"总共就这些"
    if meta.get("truncated"):
        payload["note"] = str(meta.get("truncatedHint") or "结果已截断")

    return ToolOutcome(
        name=name, ok=True,
        summary=_summarize(name, result.data, meta),
        content=json.dumps(payload, ensure_ascii=False),
        elapsed_ms=elapsed_ms,
    )


def _summarize(name: str, data: Any, meta: Dict[str, Any]) -> str:
    """把工具结果压成一句话，给前端「思考过程」面板展示。"""
    try:
        if not isinstance(data, dict):
            return "已取到数据"
        if name == GET_WEEKLY_STATS:
            return (f"训练 {data.get('trainingDays', 0)} 天 / "
                    f"容量 {data.get('totalVolume', 0)}kg / "
                    f"平均RPE {data.get('avgRpe') or '-'}")
        if name == GET_TRAINING_RECORDS:
            text = f"返回 {data.get('returned', 0)} 条记录（共 {data.get('total', 0)} 条）"
            return text + ("，已截断" if meta.get("truncated") else "")
        if name == GET_BODY_TREND:
            return (f"{data.get('totalRecords', 0)} 条体测 / "
                    f"体重变化 {data.get('weightChange', '-')}kg")
    except Exception:  # noqa: BLE001 - 摘要失败不影响主流程
        pass
    return "已取到数据"


def tool_names() -> List[str]:
    """当前注册的工具名（测试与日志用）。"""
    return [item["function"]["name"] for item in AGENT_TOOL_SCHEMAS]


__all__ = [
    "SEARCH_KNOWLEDGE",
    "GET_WEEKLY_STATS",
    "GET_TRAINING_RECORDS",
    "GET_BODY_TREND",
    "LOCAL_TOOLS",
    "REMOTE_TOOLS",
    "ALLOWED_CATEGORIES",
    "AGENT_TOOL_SCHEMAS",
    "ToolOutcome",
    "execute_tool",
    "tool_names",
]

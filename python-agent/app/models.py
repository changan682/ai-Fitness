"""Pydantic v2 请求/响应模型。

**本文件的模型逐字照抄规范（提示词.txt 第 1291-1381 行），不要改动字段名与约束。**

两条铁律：
1. 字段名全部是 **snake_case**（``user_id`` / ``action_name`` / ``generated_at``），
   Java 侧 DTO 已按同一协议用 ``@JsonProperty`` 映射，改成 camelCase 会直接对接失败。
2. ``generated_at`` 等字段类型是 ``datetime``，Pydantic 序列化后是 **ISO8601**
   （如 ``2026-07-30T15:35:00``）。Java 侧 ``PySummaryData.generatedAt`` 声明为 String
   并已适配 ISO8601，不要自行改成 ``yyyy-MM-dd HH:mm:ss``。
"""

from datetime import date, datetime
from typing import List, Optional

from pydantic import BaseModel, Field


# --- 通用响应 ---
class AgentResponse(BaseModel):
    """Python 返回给 Java 的统一结构"""

    success: bool = True
    message: str = "ok"
    data: Optional[dict] = None


# --- 训练总结 ---
class SummaryRequest(BaseModel):
    user_id: int
    date: str  # yyyy-MM-dd
    records: List[dict]  # [{action, sets, reps, weight, rpe}, ...]
    comparison: Optional[dict] = None  # 上次同部位对比数据


class SummaryResponse(BaseModel):
    summary: str  # Markdown 文本
    generated_at: datetime


# --- 动作推荐 ---
class RecommendRequest(BaseModel):
    target_muscle: str = Field(..., description="目标肌群：胸/背/腿/肩/手臂/核心")
    equipment: List[str] = Field(..., min_length=1, description="可用器械列表")
    count: int = Field(default=5, ge=1, le=10)


class ActionRecommendation(BaseModel):
    action_name: str
    target_muscle: str
    focus_area: str
    recommended_sets: str
    recommended_reps: str
    difficulty: str  # 新手/进阶/高级
    notes: str
    equipment: List[str]


class RecommendResponse(BaseModel):
    recommendations: List[ActionRecommendation]
    generated_at: datetime


# --- 姿态评估 ---
class PoseEvaluateRequest(BaseModel):
    # max_length=2_000_000 是 Base64 字符串长度上限（约等于解码后 1.5MB）；
    # 真正生效的上限是 config.pose_max_decoded_image_bytes，在 agent.evaluate_pose 入口校验。
    image_base64: str = Field(
        ...,
        max_length=2_000_000,
        description="Base64编码的图片（Java已压缩，解码后 ≤1MB）",
    )
    action_name: str = Field(..., description="动作名称：深蹲/卧推/硬拉/推举/划船")


class PoseEvaluateResponse(BaseModel):
    score: int = Field(ge=0, le=100)
    score_level: str  # 优秀/良好/一般/需改进
    issues: List[str]
    suggestions: List[str]
    good_points: List[str]
    evaluated_at: datetime


# --- RAG 问答 ---
class ChatRequest(BaseModel):
    question: str = Field(..., min_length=1, max_length=500)
    category: Optional[str] = None  # 可选，按分类过滤
    user_id: Optional[int] = None


class ChatSource(BaseModel):
    category: str
    title: str
    content: str
    score: float


class ChatResponse(BaseModel):
    question: str
    answer: str  # Markdown
    sources: List[ChatSource]
    generated_at: datetime


# --- Milvus 健康检查 ---
class KnowledgeHealthResponse(BaseModel):
    milvus_connected: bool
    collection_name: Optional[str] = None
    total_documents: Optional[int] = None
    last_updated: Optional[str] = None
    index_type: Optional[str] = None
    embedding_dim: Optional[int] = None


# --- 知识库条目（用于数据导入） ---
class KnowledgeEntry(BaseModel):
    category: str = Field(..., description="分类：动作要领/营养饮食/恢复与伤病/训练计划/补剂科普")
    title: str = Field(..., min_length=1, max_length=200)
    content: str = Field(..., min_length=50, max_length=2000)
    source: str = Field(..., description="参考来源")


# ============================================================
# 以下模型是第 4 周为「服务健康检查」补充的（规范 10.2 未给出 Pydantic 定义，
# 只给了 JSON 示例），字段名与示例严格一致。
# ============================================================
class HealthData(BaseModel):
    """``GET /agent/v1/health`` 的 data 部分（规范 10.2）"""

    status: str  # UP / DOWN
    milvus_connected: bool
    llm_api_configured: bool
    knowledge_base_ready: bool
    timestamp: str  # ISO8601


__all__ = [
    "AgentResponse",
    "SummaryRequest",
    "SummaryResponse",
    "RecommendRequest",
    "ActionRecommendation",
    "RecommendResponse",
    "PoseEvaluateRequest",
    "PoseEvaluateResponse",
    "ChatRequest",
    "ChatSource",
    "ChatResponse",
    "KnowledgeHealthResponse",
    "KnowledgeEntry",
    "HealthData",
]

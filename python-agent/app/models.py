"""Pydantic v2 请求/响应模型。

**本文件的模型逐字照抄规范（提示词.txt 第 1291-1381 行），不要改动字段名与约束。**

两条铁律：
1. 字段名全部是 **snake_case**（``user_id`` / ``action_name`` / ``generated_at``），
   Java 侧 DTO 已按同一协议用 ``@JsonProperty`` 映射，改成 camelCase 会直接对接失败。
2. ``generated_at`` 等字段类型是 ``datetime``，Pydantic 序列化后是 **ISO8601**
   （如 ``2026-07-30T15:35:00``）。Java 侧 ``PySummaryData.generatedAt`` 声明为 String
   并已适配 ISO8601，不要自行改成 ``yyyy-MM-dd HH:mm:ss``。

**关于本文件里几个「规范没有、但我们主动加上」的字段**（``data_source`` /
``score_type`` / ``degraded`` / ``degradation_reason``）：
规范只定义了分数本身，没定义「这个分数是怎么来的」。这在接入真实模型后成了问题 ——
``MOCK_MODE=true`` 时姿态评估会返回由图片哈希派生的分数，内置兜底问答会返回启发式
「相关度」，两者与真实推理/真实余弦相似度**在响应结构上完全一致**，调用方无从分辨。
因此这里做**纯增量**扩展（只加字段、不改既有字段名与约束），让调用方能把模拟结果
如实标出来。规范强调「分数与等级自洽由代码保证」，同理「分数的来源也由代码如实声明」。
"""

from datetime import date, datetime
from typing import Any, Dict, List, Optional

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
    #: 结果来源。**必须如实告知调用方**，否则「模拟打分」与「真实多模态推理」不可区分。
    #: - ``qwen_vl``：真实调用 qwen-vl-max 看图推理
    #: - ``mock_local``：MOCK_MODE=true 时的本地模拟打分（由图片哈希派生，**不是图像分析**）
    data_source: str = "qwen_vl"


# --- RAG 问答 ---
class ChatTurn(BaseModel):
    """一条对话历史消息（role = user / assistant）。

    由 Java 侧的对话记忆传下来：热层在 Redis、长期层在 ``t_ai_chat_history``；
    Python 自己**不存**任何会话状态（保持无状态，便于横向扩容）。
    """

    role: str = Field(..., pattern="^(user|assistant)$")
    content: str = Field(..., min_length=1)


class ChatRequest(BaseModel):
    question: str = Field(..., min_length=1, max_length=500)
    category: Optional[str] = None  # 可选，按分类过滤
    user_id: Optional[int] = None
    #: 对话历史（时间正序）。为空 = 单轮问答，行为与"加记忆功能"之前完全一致。
    #: 服务端会再截断一次（条数/总长度），不信任调用方传来的长度。
    #:
    #: ⚠️ 必须是 Optional：Java 侧序列化 DTO 时会把**未设置的字段以 null 发出来**，
    #: 若这里声明成 `List[ChatTurn] = []`，收到 `history: null` 会直接 422/9003
    #: （实测踩过：`history: Input should be a valid list`）。空与 null 都按"无历史"处理。
    history: Optional[List[ChatTurn]] = None


class ChatSource(BaseModel):
    category: str
    title: str
    content: str
    score: float
    #: ``score`` 的口径。**这一项是防止「编造的相似度」被当成真实检索分数展示**：
    #: - ``cosine``：真实 Milvus 余弦相似度（0-1，越大越相关）
    #: - ``heuristic``：内置 18 条兜底时的启发式合成分数（由关键词命中与二元组重合度算出，
    #:   **与向量相似度无关**，不同问题之间也不可比）
    score_type: str = "cosine"


class ChatResponse(BaseModel):
    question: str
    answer: str  # Markdown
    sources: List[ChatSource]
    generated_at: datetime
    #: 来源库与「这轮回答到底有没有用知识库」：
    #: - ``milvus``：回答确实基于 200 条真实知识库检索结果
    #: - ``llm_only``：检索到了资料，但大模型判定它与问题无关 → 回答改用通用知识，
    #:   ``sources`` 为空（**这一层是"知识库没覆盖该问题"的诚实表达**）
    #: - ``none``：压根没检索到任何来源（检索失败/无命中），回答同样来自通用知识
    #: - ``builtin``：内置 18 条兜底（``sources[].score_type=heuristic``）
    #:
    #: 判定「用没用知识库」的依据是大模型自己返回的标记
    #: （``rag.KB_USED_MARKER`` / ``rag.KB_MISS_MARKER``），而不是余弦阈值 ——
    #: 实测证明同领域无关问题也能拿到 0.82 的余弦分，阈值判不出来。
    data_source: str = "milvus"
    #: 是否走了降级路径（知识库没覆盖、检索失败/无命中、无 LLM Key 本地拼装、内置兜底）
    degraded: bool = False
    #: 降级原因（给人看的中文说明），未降级时为 None
    degradation_reason: Optional[str] = None


# --- 身体状态主动问询（体验优化批次 D） ---
class BodyConsultRequest(BaseModel):
    """Java 组装好的"身体状态快照"。

    Python 侧刻意**不查数据库**：本项目里 Python 只做 AI 推理，
    取数、口径、权限一律由 Java（BFF）负责 —— 否则同一份口径会在两种语言里各写一遍，
    迟早对不上（"最新体重"到底取档案还是取体测，项目里已经踩过一次）。

    所有字段都可空：新用户可能一次体测都没记过，此时不调大模型，直接用规则回复。
    """
    user_id: Optional[int] = None
    profile: Optional[Dict[str, Any]] = None
    #: 最新一条体测（t_body_metric）
    latest_metric: Optional[Dict[str, Any]] = None
    #: 上一条体测，用于算变化
    prev_metric: Optional[Dict[str, Any]] = None
    #: 近 7 天趋势（weight_delta / waist_delta / weight_avg7d / samples）
    trend7d: Optional[Dict[str, Any]] = None
    #: 近 7 天训练（sessions / total_volume / avg_rpe / muscles）
    training7d: Optional[Dict[str, Any]] = None
    #: 近 7 天有饮食记录的天数
    diet_days_recorded: Optional[int] = None


class BodyConsultQuestion(BaseModel):
    """一条主动追问。``why`` 说明"为什么问这个"，让用户知道这不是随机提问。"""

    id: str
    text: str
    why: str


class BodyConsultSuggestion(BaseModel):
    title: str
    detail: str


class BodyConsultRiskFlag(BaseModel):
    """风险提示。``level``：info / warn / high（high 会强制附就医提醒）。"""

    level: str = Field(..., pattern="^(info|warn|high)$")
    text: str


class BodyConsultResponse(BaseModel):
    assessment: str
    trend_summary: str
    questions: List[BodyConsultQuestion]
    suggestions: List[BodyConsultSuggestion]
    risk_flags: List[BodyConsultRiskFlag]
    #: ``llm`` = 大模型基于快照生成；``rule_based`` = 未用大模型的阈值规则兜底。
    #: 前端必须按它标注「规则生成（未使用大模型）」—— 与本项目其它降级标记同一原则。
    data_source: str = "llm"
    degraded: bool = False
    degradation_reason: Optional[str] = None
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

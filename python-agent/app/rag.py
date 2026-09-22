"""Milvus RAG 引擎 —— Embedding 生成 + 向量检索 + Context 拼接。

对应规范第二章「Python FastAPI 代码」的 ``app/rag.py``。

## RAG 流程（规范第 223-231 行）

```
用户提问 → [Step 1] Embedding(question) 得到 768 维向量
        → [Step 2] Milvus search(Top-5) 检索最相似的知识块
        → [Step 3] 把检索结果拼成 Context 注入 Prompt
        → [Step 4] LLM 基于 Context 生成带引用来源的回答
        → [Step 5] 返回 { answer, sources }
```

本模块负责 Step 1-3；Step 4-5 在 ``agent.py`` 的 ``chat_with_rag`` 里
（因为 LLM 调用属于 Agent 职责，这样 rag.py 可以完全不依赖大模型单独测试）。

## 降级策略（规范第 3547 行）

Milvus 不可用时，RAG 问答降级为纯 LLM 回答（不带知识库检索），
并在回答里标注「知识库不可用，回答可能不够准确」——
而不是直接报错让用户白屏。
"""

from __future__ import annotations

import logging
from typing import List, Optional, Tuple

from .config import Settings, settings as default_settings
from .embedding import EmbeddingError, get_embedding
from .knowledge_init import check_embedding_consistency
from .milvus_client import (
    KnowledgeHit,
    MilvusKnowledgeStore,
    MilvusUnavailableError,
    build_store,
)

logger = logging.getLogger(__name__)

#: 注入 Prompt 的 Context 总长度上限（字符）。知识块单条上限 500 字符，Top-5 最多 2500，
#: 留 4000 的余量既能容纳引用标注，又不会把大模型的上下文窗口挤爆。
MAX_CONTEXT_CHARS = 4000

#: 知识库不可用时的降级提示（规范第 3547 行要求明确标注）
DEGRADED_NOTICE = "⚠️ 知识库不可用，以下回答基于通用知识生成，可能不够准确。"

#: 检索到了资料、但资料与问题不相关（未被回答采用）时的提示。
#: 与 :data:`DEGRADED_NOTICE` 分开：那条说的是「知识库连不上」，
#: 这条说的是「知识库连得上、但没有覆盖这个问题」—— 两者的排查方向完全不同。
LOW_RELEVANCE_NOTICE = (
    "⚠️ 知识库中没有检索到与该问题相关的资料，以下回答由大模型基于通用健身知识生成，"
    "未经知识库佐证，仅供参考。"
)

#: 资料「地板分」：低于此分时**不再把资料交给大模型**，直接按「知识库没有覆盖」处理。
#:
#: <h3>为什么不能只靠阈值判「相关/不相关」（实测数据）</h3>
#: 用真实 DashScope Embedding + 200 条知识库实测（top1 余弦）：
#:
#: | 问题 | 是否被知识库覆盖 | top1 |
#: |:--|:--|:--|
#: | 增肌期每天应该吃多少蛋白质 | 是 | 0.9135 |
#: | 新手一周练几次比较合适 | 是 | 0.7877 |
#: | 肌酸怎么吃才有效 | 是 | 0.7727 |
#: | 深蹲时膝盖可以超过脚尖吗 | 是 | 0.7511 |
#: | 训练后肌肉酸痛还能继续练吗 | 是 | **0.7327** |
#: | 碳水循环具体怎么安排 | **否** | **0.8206** |
#: | 感冒发烧期间可以去健身房吗 | 否 | 0.6179 |
#: | 帮我写一首关于健身的诗 | 否 | 0.5507 |
#:
#: 可见「没覆盖的问题」能拿到 0.8206，比「覆盖了的问题」0.7327 还高 ——
#: 同一领域内的密向量检索**天生分不开**这两类。因此：
#: 1. 阈值**只做地板**（挡掉明显无关的，如 0.55 以下），不做「相关性判决」；
#: 2. 判决交给大模型自己（见 :data:`KB_MISS_MARKER`），它读得懂语义；
#: 3. 判不出来时**保守降级**：宁可把一次真 RAG 回答标成「未使用知识库」，
#:    也不能把一份基于无关资料的回答标成「有知识库依据」。
RAG_CONTEXT_FLOOR = 0.55

#: 大模型自报「这次回答有没有用上知识库」的标记，必须是回答的**第一行**。
#: 用标记而不是让 Python 猜：只有模型自己知道它到底有没有参考那些资料。
KB_USED_MARKER = "[[KB:USED]]"

#: 见 :data:`KB_USED_MARKER`：资料与问题无关（或不足以回答）时由模型输出。
KB_MISS_MARKER = "[[KB:MISS]]"


class RagUnavailableError(RuntimeError):
    """RAG 链路不可用（Embedding 或 Milvus 故障）。"""


class RagEngine:
    """RAG 检索引擎。"""

    def __init__(self, settings: Settings, store: Optional[MilvusKnowledgeStore] = None):
        self.settings = settings
        self.store = store or build_store(settings)

    # ==================== Step 1 + 2：向量化并检索 ====================

    def retrieve(
        self,
        question: str,
        *,
        category: Optional[str] = None,
        top_k: Optional[int] = None,
    ) -> List[KnowledgeHit]:
        """检索与问题最相关的知识块（默认 Top-5）。"""
        if not question or not question.strip():
            return []

        limit = top_k or self.settings.rag_top_k

        try:
            embedder = get_embedding(self.settings)
        except EmbeddingError as exc:
            raise RagUnavailableError(f"Embedding 初始化失败: {exc}") from exc

        # 一致性校验：库里的向量与查询向量必须来自同一个模型，
        # 否则余弦相似度算出来的是无意义的数字 —— 检索会「成功」返回一批不相关知识，
        # 再被大模型自信地引用。宁可明确失败并降级为纯 LLM 回答。
        mismatch = check_embedding_consistency(embedder.provider)
        if mismatch:
            logger.error("Embedding 一致性校验未通过，拒绝检索: %s", mismatch)
            raise RagUnavailableError(mismatch)

        try:
            vector = embedder.embed_query(question)
        except EmbeddingError as exc:
            raise RagUnavailableError(f"问题向量化失败: {exc}") from exc

        try:
            hits = self.store.search(vector, top_k=limit, category=category)
        except MilvusUnavailableError:
            raise
        except Exception as exc:  # noqa: BLE001 - 统一转成可降级的异常类型
            raise RagUnavailableError(f"向量检索失败: {exc}") from exc

        logger.info(
            "RAG 检索完成: topK=%d 命中=%d 分类过滤=%s 最高分=%s",
            limit, len(hits), category or "无",
            f"{hits[0].score:.4f}" if hits else "n/a",
        )
        return hits

    # ==================== Step 3：Context 拼接 ====================

    def build_context(self, hits: List[KnowledgeHit]) -> str:
        """把检索结果拼成适合塞进 Prompt 的上下文。

        编号与 sources 的顺序严格对应，这样大模型写的 [1][2] 引用标注
        才能和响应里的 sources 数组对上。
        """
        if not hits:
            return ""

        blocks: List[str] = []
        used = 0
        for index, hit in enumerate(hits, start=1):
            block = (
                f"[{index}] 【{hit.category}】{hit.title}\n"
                f"{hit.content}\n"
                f"来源：{hit.source}"
            )
            if used + len(block) > MAX_CONTEXT_CHARS:
                logger.warning("Context 已达长度上限 %d，截断后续 %d 条知识",
                               MAX_CONTEXT_CHARS, len(hits) - len(blocks))
                break
            blocks.append(block)
            used += len(block)

        return "\n\n".join(blocks)

    # ==================== 便捷组合 ====================

    def retrieve_with_context(
        self,
        question: str,
        *,
        category: Optional[str] = None,
        top_k: Optional[int] = None,
    ) -> Tuple[List[KnowledgeHit], str]:
        """一次拿到检索结果与拼接好的 Context。"""
        hits = self.retrieve(question, category=category, top_k=top_k)
        return hits, self.build_context(hits)

    def health(self) -> dict:
        """知识库健康状态（对应规范 7.5）。"""
        info = self.store.health()
        try:
            info["embedding"] = get_embedding(self.settings).describe()
        except EmbeddingError as exc:
            info["embedding"] = {"error": str(exc)}
        count = info.get("total_documents") or 0
        info["knowledge_base_ready"] = bool(info.get("milvus_connected")) and count > 0
        return info


_engine_singleton: Optional[RagEngine] = None


def get_rag_engine(settings: Optional[Settings] = None) -> RagEngine:
    """获取全局唯一的 RAG 引擎。"""
    global _engine_singleton
    if _engine_singleton is None:
        _engine_singleton = RagEngine(settings or default_settings)
    return _engine_singleton


def reset_rag_engine() -> None:
    """清空缓存（测试用）。"""
    global _engine_singleton
    _engine_singleton = None

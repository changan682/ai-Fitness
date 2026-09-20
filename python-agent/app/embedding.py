"""Embedding 抽象层 —— 支持多供应商切换。

## 为什么需要这一层

规范（提示词.txt 第 57 行）把「DeepSeek Embedding」列为 Embedding 备选，
但 **DeepSeek 并不提供 Embedding 接口**（见 deepseek-ai/DeepSeek-V3 issue #806），
规范这个前提是错的。既然「首选方案的 API 不存在」，就不能把某一家硬编码进业务代码，
否则将来换供应商要动 RAG 与知识库初始化两处逻辑。

因此把 Embedding 收敛成统一接口，由 `.env` 的 `EMBEDDING_PROVIDER` 决定实现：

| provider      | 说明                                                | 需要 Key |
|---------------|-----------------------------------------------------|----------|
| `dashscope`   | 阿里云百炼 text-embedding-v3（可指定 768 维）        | 是       |
| `siliconflow` | 硅基流动 BAAI/bge-base-zh-v1.5（原生 768 维）        | 是       |
| `local`       | 本地 sentence-transformers 跑 text2vec-base-chinese | 否（要下模型） |
| `hashing`     | 离线兜底：字符 n-gram 哈希向量                       | 否       |

> ⚠️ 本地模型固定用 `shibing624/text2vec-base-chinese`（hidden size = **768**）。
> 不要换成 `text2vec-large-chinese`：它的 hidden size 是 **1024**，
> 与 `dim=768` 的 Milvus Collection 不匹配，会在 `insert()` 时抛维度错误。

## 关于 hashing 兜底

它不是"假数据"：用 hashing trick 把中文的 1/2/3-gram 映射到 768 维并做符号散列、
L2 归一化，因此**文本越相似、余弦相似度越高**，RAG 检索出来的确实是相关内容。
这样在没有 API Key 的环境里也能真实验证「Milvus 建库 → 写入 → 检索 → 组装 Context」
整条链路，而不是靠 mock 假装通过。它只输在语义泛化（同义词/改写检索不到），
所以一旦配上真实 Key 就该切走。

## 维度一致性

规范硬性要求 768 维。每个 provider 返回后都会校验维度，
不一致时抛出明确的中文错误（而不是等 Milvus 报一句看不懂的 schema 错误）。
"""

from __future__ import annotations

import hashlib
import logging
import math
import re
from abc import ABC, abstractmethod
from typing import List, Optional

import httpx

from .config import Settings, settings as default_settings

logger = logging.getLogger(__name__)


class EmbeddingError(RuntimeError):
    """Embedding 调用失败（网络/鉴权/维度不符），由上层转成 6004 错误码。"""


class BaseEmbedding(ABC):
    """Embedding 统一接口。"""

    #: 供应商标识，用于日志与健康检查
    provider: str = "unknown"
    #: 实际使用的模型名
    model: str = ""
    #: 向量维度
    dim: int = 768

    #: 建议的「相关命中」余弦阈值。
    #: 真实语义模型（百炼/bge/text2vec）对正确命中的余弦通常在 0.7-0.95，
    #: 因此 0.75 是合理阈值；但字符 n-gram 哈希的余弦分布完全不同 ——
    #: 实测正确命中也只有 0.26-0.33，沿用 0.75 会让每次回答都误报
    #: 「未检索到高度相关的资料」。因此该阈值由各 provider 自行校准。
    relevance_threshold: float = 0.75

    @abstractmethod
    def embed_documents(self, texts: List[str]) -> List[List[float]]:
        """批量向量化（写入知识库时用）。"""

    def embed_query(self, text: str) -> List[float]:
        """单条向量化（检索时用）。默认复用批量实现。"""
        vectors = self.embed_documents([text])
        if not vectors:
            raise EmbeddingError("Embedding 返回为空")
        return vectors[0]

    def describe(self) -> dict:
        """给健康检查用的自描述信息。"""
        return {
            "provider": self.provider,
            "model": self.model,
            "dim": self.dim,
            "is_real_model": self.provider in ("dashscope", "siliconflow", "local"),
        }

    # ---------- 公共校验 ----------

    def _validate(self, vectors: List[List[float]], expected: int) -> List[List[float]]:
        if len(vectors) != expected:
            raise EmbeddingError(f"Embedding 返回条数不符：期望 {expected}，实际 {len(vectors)}")
        for i, vec in enumerate(vectors):
            if len(vec) != self.dim:
                raise EmbeddingError(
                    f"Embedding 维度不符：{self.provider} 返回 {len(vec)} 维，"
                    f"但配置要求 {self.dim} 维（规范硬性要求 768，与 Milvus Collection 的 dim 对齐）。"
                    f"常见的坑：本地模型 text2vec-large-chinese 的 hidden size 是 1024，"
                    f"会得到 1024 维向量，必须换成 text2vec-base-chinese（768 维）；"
                    f"若确实要换用其他维度的模型，必须同步修改 EMBEDDING_DIM 并重建 Collection 索引"
                    f"（FORCE_RELOAD_KNOWLEDGE=true，否则新旧向量不在同一语义空间）。"
                    f"另外百炼 text-embedding-v3 需显式传 dimensions={self.dim}。"
                )
            if not all(isinstance(x, (int, float)) for x in vec[:5]):
                raise EmbeddingError(f"Embedding 第 {i} 条向量包含非数值元素")
        return vectors


# ======================================================================
# 离线兜底：字符 n-gram 哈希向量
# ======================================================================

class HashingEmbedding(BaseEmbedding):
    """无需联网与模型的兜底实现。

    用 hashing trick 把中文 1/2/3-gram 散列到固定维度并做符号散列（减小碰撞的偏置影响），
    再 L2 归一化。这样余弦相似度能真实反映词法重合度，
    足以验证 Milvus 建库/写入/检索全链路，也能在没有 Key 时做演示。
    """

    provider = "hashing"
    model = "char-ngram-hashing(offline-fallback)"
    #: 实测校准值：正确命中的余弦在 0.26-0.33 区间，0.15 可区分「有词法重合」与「毫无关系」。
    #: 这个数只对字符哈希有意义，切到真实模型后会自动用回 0.75。
    relevance_threshold = 0.15

    #: 不同长度 n-gram 的权重：单字噪声大、三元组更具体，故中间权重最高
    _NGRAM_WEIGHTS = {1: 0.5, 2: 1.0, 3: 0.7}

    def __init__(self, dim: int = 768):
        self.dim = dim

    def embed_documents(self, texts: List[str]) -> List[List[float]]:
        vectors = [self._hash_vector(t) for t in texts]
        return self._validate(vectors, len(texts))

    def _hash_vector(self, text: str) -> List[float]:
        # 去掉空白：中文文本的换行/空格不应影响相似度
        normalized = re.sub(r"\s+", "", (text or "").lower())
        vec = [0.0] * self.dim

        for n, weight in self._NGRAM_WEIGHTS.items():
            for i in range(len(normalized) - n + 1):
                gram = normalized[i:i + n]
                digest = hashlib.blake2b(gram.encode("utf-8"), digest_size=8).digest()
                index = int.from_bytes(digest[:4], "big") % self.dim
                # 符号散列：用第 5 个字节的最低位决定正负，抵消哈希碰撞带来的系统性偏置
                sign = 1.0 if digest[4] & 1 else -1.0
                vec[index] += sign * weight

        norm = math.sqrt(sum(v * v for v in vec))
        if norm == 0.0:
            # 空文本：返回单位向量里的一维，避免除零
            vec[0] = 1.0
            return vec
        return [v / norm for v in vec]


# ======================================================================
# 云端：OpenAI 兼容的 /embeddings 接口（百炼、硅基流动都是这套协议）
# ======================================================================

class _OpenAICompatibleEmbedding(BaseEmbedding):
    """OpenAI 兼容 Embedding 客户端的公共实现。

    百炼与硅基流动都提供 ``POST {base_url}/embeddings``，
    请求体 ``{"model": ..., "input": [...]}``、响应 ``{"data": [{"embedding": [...], "index": 0}]}``。
    """

    def __init__(self, *, api_key: str, base_url: str, model: str, dim: int,
                 batch_size: int = 10, timeout: float = 30.0,
                 extra_body: Optional[dict] = None):
        if not api_key or not api_key.strip():
            raise EmbeddingError(f"{self.provider} 需要 API Key，但未配置")
        self._api_key = api_key.strip()
        self._base_url = base_url.rstrip("/")
        self.model = model
        self.dim = dim
        self._batch_size = max(1, batch_size)
        self._timeout = timeout
        # 百炼 text-embedding-v3 需要显式传 dimensions 才能拿到非默认维度
        self._extra_body = extra_body or {}

    def embed_documents(self, texts: List[str]) -> List[List[float]]:
        if not texts:
            return []

        vectors: List[List[float]] = []
        for start in range(0, len(texts), self._batch_size):
            batch = texts[start:start + self._batch_size]
            vectors.extend(self._embed_batch(batch))
        return self._validate(vectors, len(texts))

    def _embed_batch(self, batch: List[str]) -> List[List[float]]:
        payload = {"model": self.model, "input": batch, **self._extra_body}
        url = f"{self._base_url}/embeddings"
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
            # 不把 api_key 带进异常信息
            raise EmbeddingError(f"{self.provider} Embedding 请求失败: {exc}") from exc

        if response.status_code != 200:
            # 截断响应体，避免把整页 HTML 错误页刷进日志
            detail = response.text[:300]
            raise EmbeddingError(
                f"{self.provider} Embedding 返回 HTTP {response.status_code}: {detail}"
            )

        try:
            body = response.json()
            items = body["data"]
        except Exception as exc:  # noqa: BLE001 - 响应结构异常统一转成 EmbeddingError
            raise EmbeddingError(f"{self.provider} Embedding 响应结构异常: {response.text[:300]}") from exc

        # 按 index 排序，保证返回顺序与入参严格对应（否则知识条目与向量会错位）
        items = sorted(items, key=lambda x: x.get("index", 0))
        return [item["embedding"] for item in items]


class DashScopeEmbedding(_OpenAICompatibleEmbedding):
    """阿里云百炼 text-embedding-v3（OpenAI 兼容模式）。

    选它的理由：第 6 周的「姿态评估」本来就需要通义千问 VL 多模态模型，
    Embedding 也用百炼的话**只需要维护一个 Key**。
    """

    provider = "dashscope"

    def __init__(self, *, api_key: str, base_url: str, model: str, dim: int, batch_size: int = 10):
        super().__init__(
            api_key=api_key, base_url=base_url, model=model, dim=dim,
            batch_size=batch_size, timeout=60.0,
            # v3 支持 512/768/1024 等维度，必须显式指定，否则默认 1024 与规范的 768 不符
            extra_body={"dimensions": dim, "encoding_format": "float"},
        )


class SiliconFlowEmbedding(_OpenAICompatibleEmbedding):
    """硅基流动 Embedding（BAAI/bge-base-zh-v1.5 原生 768 维）。"""

    provider = "siliconflow"

    def __init__(self, *, api_key: str, base_url: str, model: str, dim: int, batch_size: int = 10):
        # 该模型原生 768 维，不需要 dimensions 参数
        super().__init__(
            api_key=api_key, base_url=base_url, model=model, dim=dim,
            batch_size=batch_size, timeout=60.0,
        )


# ======================================================================
# 本地：sentence-transformers
# ======================================================================

class LocalEmbedding(BaseEmbedding):
    """本地 `shibing624/text2vec-base-chinese`（规范的首选方案，hidden size = 768）。

    代价是首次使用要下载模型（base 版约 400MB）并安装 torch/sentence-transformers（数 GB），
    因此这里**延迟导入**：只有真的选了这个 provider 才会触发依赖检查，
    没装也不影响其他 provider 启动。

    ⚠️ 不要用 `text2vec-large-chinese`：它输出 **1024** 维，与 `dim=768` 的
    Milvus Collection 不匹配，`_validate()` 会直接拦住并在 `insert()` 前报错。
    """

    provider = "local"

    def __init__(self, *, model_name: str, dim: int):
        self.model = model_name
        self.dim = dim
        try:
            from sentence_transformers import SentenceTransformer  # noqa: PLC0415 - 延迟导入是刻意设计
        except ImportError as exc:
            raise EmbeddingError(
                "选择了本地 Embedding 但未安装 sentence-transformers。"
                "请执行：pip install sentence-transformers（会一并安装 torch，体积较大）；"
                "或改用 EMBEDDING_PROVIDER=dashscope。"
            ) from exc

        logger.info("正在加载本地 Embedding 模型（首次会下载，约 400MB）: %s", model_name)
        self._model = SentenceTransformer(model_name)

    def embed_documents(self, texts: List[str]) -> List[List[float]]:
        # normalize_embeddings=True 让向量为单位长度，与 COSINE 度量配合更稳
        vectors = self._model.encode(texts, normalize_embeddings=True, show_progress_bar=False)
        return self._validate([list(map(float, v)) for v in vectors], len(texts))


# ======================================================================
# 工厂
# ======================================================================

def build_embedding(settings: Settings) -> BaseEmbedding:
    """按配置构造 Embedding 客户端。"""
    provider = settings.resolved_embedding_provider
    dim = settings.embedding_dim

    if provider == "dashscope":
        logger.info("Embedding 供应商: dashscope（%s, %d维）",
                    settings.dashscope_embedding_model, dim)
        return DashScopeEmbedding(
            api_key=settings.dashscope_api_key or "",
            base_url=settings.dashscope_base_url,
            model=settings.dashscope_embedding_model,
            dim=dim,
            batch_size=settings.embedding_batch_size,
        )

    if provider == "siliconflow":
        logger.info("Embedding 供应商: siliconflow（%s, %d维）",
                    settings.siliconflow_embedding_model, dim)
        return SiliconFlowEmbedding(
            api_key=settings.siliconflow_api_key or "",
            base_url=settings.siliconflow_base_url,
            model=settings.siliconflow_embedding_model,
            dim=dim,
            batch_size=settings.embedding_batch_size,
        )

    if provider == "local":
        logger.info("Embedding 供应商: local（%s, %d维）", settings.local_embedding_model, dim)
        return LocalEmbedding(model_name=settings.local_embedding_model, dim=dim)

    if provider == "hashing":
        logger.warning(
            "Embedding 供应商: hashing（离线兜底，非真实语义模型）。"
            "检索只能命中词法相近的内容，语义改写检索不到。"
            "配置 DASHSCOPE_API_KEY 后会自动切换到真实 Embedding。"
        )
        return HashingEmbedding(dim=dim)

    raise EmbeddingError(
        f"未知的 EMBEDDING_PROVIDER: {provider}；"
        f"可选值：auto / dashscope / siliconflow / local / hashing"
    )


_embedding_singleton: Optional[BaseEmbedding] = None


def get_embedding(settings: Optional[Settings] = None) -> BaseEmbedding:
    """获取全局唯一的 Embedding 客户端（进程内缓存，避免重复加载模型）。"""
    global _embedding_singleton
    if _embedding_singleton is None:
        _embedding_singleton = build_embedding(settings or default_settings)
    return _embedding_singleton


def reset_embedding_cache() -> None:
    """清空缓存（测试用：切换 provider 后需要重建）。"""
    global _embedding_singleton
    _embedding_singleton = None

"""Milvus 客户端封装 —— 连接管理、Collection 创建、索引配置、增删查。

对应规范第二章「Python FastAPI 代码」的 ``app/milvus_client.py``。

## 架构边界（规范第 150 行）

Milvus **只允许 Python 直连**，Java 不得直接访问（保持架构边界清晰）。
因此这个模块是 Python 侧唯一的 Milvus 出口，``rag.py`` 也只通过它检索。

## Collection 设计（规范第 234-248 行）

字段：``id / category / title / content / source / embedding / created_at``
索引：IVF_FLAT（或 HNSW） + COSINE + nlist=16，检索 nprobe=4，Top-5。

> **索引参数必须与数据规模匹配（规范第 3630-3631 行）**：nlist 的经验值是 √N，
> 知识库 N=200 → √200≈14，故取 16；检索时 nprobe 与 nlist 配套取 4。
> 严禁用 nlist=128 这类远超 √N 的值 —— 每个聚类只会分到 1-2 条向量，
> 聚类近似失效，召回率反而下降。数据量增长到万级后再按 √N 重新调 nlist/nprobe。

## 关于 pymilvus 版本

本项目环境里装的是 pymilvus 2.6.x，使用其 **MilvusClient** 门面（2.4+ 引入）。
它要求服务端也不低于 2.4 —— 规范示例给的 ``milvusdb/milvus:v2.3.4`` 是 2023 年的版本，
与 2.6 客户端跨了 3 个小版本，容易出现协议不兼容，因此 docker-compose 里改用与客户端同代的镜像。
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Optional, Sequence

logger = logging.getLogger(__name__)

# pymilvus 是第 5 周才引入的依赖，缺失时不要让它把整个服务拖挂 —— 
# 只有真的调用 Milvus 相关接口才报错，其余接口（第 4 周已交付的）不受影响。
try:
    from pymilvus import DataType, MilvusClient  # type: ignore

    PYMILVUS_AVAILABLE = True
    PYMILVUS_IMPORT_ERROR: Optional[str] = None
except Exception as exc:  # noqa: BLE001 - 捕获所有导入异常（缺包/版本不兼容）
    PYMILVUS_AVAILABLE = False
    PYMILVUS_IMPORT_ERROR = str(exc)
    DataType = None  # type: ignore
    MilvusClient = None  # type: ignore


# ======================================================================
# 建造清单时间 —— 健康检查 last_updated 的数据来源
# ======================================================================
#
# ## 为什么 last_updated 要读文件，而不是问 Milvus 要
#
# Milvus 的 Collection 只存知识条目本身，不记录「这批向量是什么时候建的」；
# ``created_at`` 是逐条写入时间，取 max 也能凑出个数字，但：
#   - 它是**写入那一刻**的毫秒时间戳，批量导入 200 条会得到 200 个略有差异的值；
#   - 重建索引后若数据是从别处迁移来的，它会谎报成「刚刚更新」。
# 而 ``data/.kb_manifest.json`` 是**知识库导入/重建成功时**由
# ``knowledge_init.write_manifest`` 主动落盘的「建造清单」，它的 ``built_at``
# 语义正好就是「知识库最后一次重建的时间」，和规范 7.5 里 last_updated 的含义一致。
#
# ## 为什么必须防御性读取
#
# ``/agent/v1/knowledge/health`` 是运维/前端用来判断「服务活没活、知识库有没有数据」
# 的接口。清单文件属于**附带信息**：不存在（更早版本建的库）、被手改坏、
# 字段缺失，都只应该让 last_updated 变成 null，绝不能让健康检查 500。

#: 健康检查返回的时间格式：``2026-07-15 10:00:00``
#: （与规范 models.py 的健康检查示例、agent.MOCK_KNOWLEDGE_STATS 一致）
HEALTH_TIME_FORMAT = "%Y-%m-%d %H:%M:%S"

#: 建造清单的兜底路径。刻意**不**在模块顶层 ``from .knowledge_init import KB_MANIFEST_FILE``：
#: knowledge_init 在顶层 import 本模块，反向导入会形成循环依赖。因此运行时惰性取真值，
#: 既保持「路径只有一个定义处」，又不会制造 import 环。
DEFAULT_MANIFEST_PATH = Path(__file__).resolve().parent.parent / "data" / ".kb_manifest.json"


def manifest_path() -> Path:
    """当前生效的建造清单路径（正常情况下来自 ``knowledge_init.KB_MANIFEST_FILE``）。"""
    try:
        from .knowledge_init import KB_MANIFEST_FILE  # 惰性导入，避免循环依赖

        return Path(KB_MANIFEST_FILE)
    except Exception:  # noqa: BLE001 - 拿不到就退回默认路径，健康检查不该因此失败
        return DEFAULT_MANIFEST_PATH


def format_health_timestamp(value: object) -> Optional[str]:
    """把 ``built_at`` 归一成 ``YYYY-MM-DD HH:MM:SS``；认不出格式时返回 ``None``。

    兼容本项目可能落盘的几种形态（清单由 ``datetime.now().isoformat()`` 写入）：

    - ``2026-09-14T19:34:35`` —— 现行写法（T 分隔）
    - ``2026-09-14T19:34:35.123456`` / ``...+08:00`` / ``...Z`` —— 带微秒或时区
    - ``2026-09-14 19:34:35`` —— 已经是对外格式（直接放行）
    - ``datetime`` 对象 —— 便于单测与将来改结构

    **绝不抛异常**：认不出来返回 ``None``，由调用方（health）原样透出 null。
    """
    if value is None:
        return None
    if isinstance(value, datetime):
        return value.strftime(HEALTH_TIME_FORMAT)

    text = str(value).strip()
    if not text:
        return None

    # 1) ISO8601（这是 write_manifest 的写法，正常情况下走这条）
    candidate = text[:-1] + "+00:00" if text.endswith(("Z", "z")) else text
    parsed: Optional[datetime] = None
    try:
        parsed = datetime.fromisoformat(candidate)
    except (ValueError, TypeError):
        parsed = None

    # 2) 退回逐个白名单格式：覆盖手写/从别处导出的写法
    if parsed is None:
        for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M",
                    "%Y/%m/%d %H:%M:%S", "%Y-%m-%d", "%Y%m%d%H%M%S"):
            try:
                parsed = datetime.strptime(text, fmt)
                break
            except ValueError:
                continue

    if parsed is None:
        logger.debug("建造清单里的 built_at 格式无法识别: %r", text)
        return None

    if parsed.tzinfo is not None:
        # 带时区的写法统一换算成本地时间：规范里的 last_updated 示例是本地时间，不带后缀
        parsed = parsed.astimezone()
    return parsed.strftime(HEALTH_TIME_FORMAT)


def read_manifest_built_at(
    path: Optional[Path] = None,
    *,
    collection: Optional[str] = None,
) -> Optional[str]:
    """读取建造清单的 ``built_at``，返回 ``YYYY-MM-DD HH:MM:SS``，任何异常都返回 ``None``。

    覆盖的「坏情况」全部只降级为 null，不抛异常：

    - 清单不存在（更早版本建的库 / 被删掉）
    - 文件不是合法 JSON、不是 UTF-8、无读权限
    - 顶层不是对象，或缺少 ``built_at`` 字段
    - ``built_at`` 是空串 / 数字 / 认不出的时间格式

    :param collection: 传入时额外校验清单记录的是**同一个** Collection；
        不一致（例如改过 MILVUS_COLLECTION）时返回 None —— 别的集合的构建时间对当前知识库没有意义
    """
    manifest_file = path or manifest_path()
    try:
        if not manifest_file.exists():
            return None
        payload = json.loads(manifest_file.read_text(encoding="utf-8-sig"))
        if not isinstance(payload, dict):
            logger.debug("建造清单顶层不是对象: %s", type(payload).__name__)
            return None
        if collection and payload.get("collection") \
                and str(payload["collection"]) != str(collection):
            logger.debug("建造清单记录的是另一个 Collection（%s ≠ %s），last_updated 置空",
                         payload.get("collection"), collection)
            return None
        return format_health_timestamp(payload.get("built_at"))
    except Exception as exc:  # noqa: BLE001 - 见 docstring：绝不向上抛
        logger.warning("读取知识库建造清单失败（last_updated 将为空）: %s", exc)
        return None


class MilvusUnavailableError(RuntimeError):
    """Milvus 不可用（未安装依赖 / 连不上 / Collection 未初始化），对应错误码 6003。"""


@dataclass
class KnowledgeHit:
    """一条检索结果。"""

    category: str
    title: str
    content: str
    source: str
    score: float

    def to_source_dict(self) -> dict:
        """转成规范 7.4 响应里的 sources 元素结构。"""
        return {
            "category": self.category,
            "title": self.title,
            "content": self.content,
            "score": round(float(self.score), 4),
        }


class MilvusKnowledgeStore:
    """健身知识库的向量存储。"""

    def __init__(
        self,
        *,
        host: str = "localhost",
        port: int = 19530,
        collection: str = "fitness_knowledge",
        dim: int = 768,
        index_type: str = "IVF_FLAT",
        # nlist ≈ √N：N=200 的知识库取 16（√200≈14）；nprobe 必须与 nlist 配套
        nlist: int = 16,
        nprobe: int = 4,
    ):
        self.host = host
        self.port = port
        self.collection = collection
        self.dim = dim
        self.index_type = index_type
        self.nlist = nlist
        self.nprobe = nprobe
        self._client = None

    # ==================== 连接 ====================

    @property
    def uri(self) -> str:
        return f"http://{self.host}:{self.port}"

    def _require_pymilvus(self) -> None:
        if not PYMILVUS_AVAILABLE:
            raise MilvusUnavailableError(
                f"未安装 pymilvus（或版本不兼容）：{PYMILVUS_IMPORT_ERROR}。"
                f"请执行：pip install 'pymilvus>=2.4'"
            )

    def client(self):
        """惰性创建客户端（MilvusClient 本身是惰性连接的）。"""
        self._require_pymilvus()
        if self._client is None:
            logger.info("连接 Milvus: %s", self.uri)
            self._client = MilvusClient(uri=self.uri)
        return self._client

    def health(self) -> dict:
        """健康检查：返回连接状态、集合名、文档数、最后一次构建时间、索引与维度。"""
        result = {
            "milvus_connected": False,
            "collection_name": self.collection,
            "total_documents": None,
            # last_updated 取自 data/.kb_manifest.json 的 built_at（写入见 knowledge_init.write_manifest）。
            # 刻意放在 try 之外、且不依赖 Milvus 连接是否成功：
            #   1) 它是「知识库最后一次重建时间」，Milvus 连不上时前端仍需要这个信息来判断
            #      手里这份库有多旧（配合 milvus_connected=false 展示即可）；
            #   2) read_manifest_built_at 内部已吞掉所有异常并返回 None，
            #      不会把健康检查拖成 500。
            "last_updated": read_manifest_built_at(collection=self.collection),
            "index_type": None,
            # 索引参数与配置不一致时的提示语（供健康检查/统计脚本展示，正常时为 None）
            "index_warning": None,
            "embedding_dim": self.dim,
            "error": None,
        }
        try:
            client = self.client()
            names = client.list_collections()
            result["milvus_connected"] = True
            if self.collection in names:
                result["total_documents"] = self.count()
                result["index_type"] = self._describe_index(client)
                mismatch = self.index_nlist_mismatch()
                if mismatch:
                    # 高频陷阱，必须显式暴露：改了配置没重建 = 文档与实跑不一致
                    result["index_warning"] = mismatch
                    logger.warning(mismatch)
        except Exception as exc:  # noqa: BLE001 - 健康检查不能抛异常
            result["error"] = str(exc)
            logger.warning("Milvus 健康检查失败: %s", exc)
        return result

    def _describe_index(self, client) -> Optional[str]:
        """把**真实**索引参数拼成一行摘要，如 ``IVF_FLAT(COSINE, nlist=128)``。

        ⚠️ 必须读 ``describe_index`` 返回的真实参数，**不能**用 ``self.nlist``（配置值）：
        配置改了但没重建 Collection 时，索引里仍是旧参数，
        用配置值渲染摘要等于「谎报索引状态」—— 实测就踩过这个坑：
        配置已改成 nlist=16，而索引里实际还是 128，旧实现的输出却显示 16。
        """
        try:
            indexes = client.list_indexes(self.collection)
            if not indexes:
                return None
            desc = client.describe_index(self.collection, indexes[0])
            index_type = desc.get("index_type") or self.index_type
            metric = desc.get("metric_type") or "COSINE"
            # pymilvus 不同版本把 nlist 放在顶层或 params 里，两处都试
            nlist = desc.get("nlist") or (desc.get("params") or {}).get("nlist")
            if nlist is None:
                return f"{index_type}({metric})"
            return f"{index_type}({metric}, nlist={nlist})"
        except Exception:  # noqa: BLE001 - 只是展示用，拿不到就算了
            return self.index_type

    def index_nlist_mismatch(self) -> Optional[str]:
        """检查「索引里的 nlist」与「配置里的 nlist」是否一致。

        不一致是**高频且隐蔽**的运维陷阱：改了 ``MILVUS_NLIST`` 却忘了
        ``FORCE_RELOAD_KNOWLEDGE=true`` 重建，检索质量与文档描述就对不上。

        :return: 不一致时返回可读的提示语；一致 / 读不到 / 非 IVF 索引时返回 None
        """
        try:
            client = self.client()
            if self.collection not in client.list_collections():
                return None
            indexes = client.list_indexes(self.collection)
            if not indexes:
                return None
            desc = client.describe_index(self.collection, indexes[0])
            if (desc.get("index_type") or "").upper() not in ("IVF_FLAT", "IVF_SQ8", "IVF_PQ"):
                return None
            actual = desc.get("nlist") or (desc.get("params") or {}).get("nlist")
            if actual is None:
                return None
            if int(actual) != int(self.nlist):
                return (
                    f"索引参数与配置不一致：Milvus 里实际 nlist={actual}，"
                    f"配置里是 nlist={self.nlist}。配置改动尚未生效，"
                    f"需要 FORCE_RELOAD_KNOWLEDGE=true 重建 Collection"
                    f"（改配置不会修改已建好的索引）"
                )
        except Exception as exc:  # noqa: BLE001 - 诊断信息不该抛异常
            logger.debug("索引参数一致性检查失败: %s", exc)
        return None

    # ==================== Collection 与索引 ====================

    def has_collection(self) -> bool:
        try:
            return self.collection in self.client().list_collections()
        except MilvusUnavailableError:
            raise
        except Exception as exc:  # noqa: BLE001
            raise MilvusUnavailableError(f"查询 Milvus 集合列表失败: {exc}") from exc

    def ensure_collection(self, *, recreate: bool = False) -> bool:
        """确保 Collection 与索引存在。

        :return: True 表示本次新建了 Collection，False 表示原本就存在。
        """
        client = self.client()

        if self.has_collection():
            if not recreate:
                return False
            logger.warning("按请求删除已有 Collection: %s（其数据将全部丢失）", self.collection)
            client.drop_collection(self.collection)

        schema = client.create_schema(auto_id=True, enable_dynamic_field=False)
        schema.add_field("id", DataType.INT64, is_primary=True, auto_id=True)
        # VARCHAR 的 max_length 按字节计，中文一个字符最多 4 字节，因此留足余量：
        # title 规范上限 200 字符 → 1024 字节；content 规范上限 2000 字符 → 8192 字节
        schema.add_field("category", DataType.VARCHAR, max_length=64)
        schema.add_field("title", DataType.VARCHAR, max_length=1024)
        schema.add_field("content", DataType.VARCHAR, max_length=8192)
        schema.add_field("source", DataType.VARCHAR, max_length=1024)
        schema.add_field("embedding", DataType.FLOAT_VECTOR, dim=self.dim)
        schema.add_field("created_at", DataType.INT64)

        index_params = client.prepare_index_params()
        index_params.add_index(
            field_name="embedding",
            index_type=self.index_type,
            metric_type="COSINE",
            params={"nlist": self.nlist},
        )

        client.create_collection(
            collection_name=self.collection,
            schema=schema,
            index_params=index_params,
        )
        logger.info(
            "已创建 Milvus Collection: %s（dim=%d, index=%s, metric=COSINE, nlist=%d）",
            self.collection, self.dim, self.index_type, self.nlist,
        )
        return True

    def drop_collection(self) -> None:
        client = self.client()
        if self.has_collection():
            client.drop_collection(self.collection)
            logger.warning("已删除 Collection: %s", self.collection)

    # ==================== 写入 ====================

    def insert_entries(self, entries: Sequence[dict], vectors: Sequence[Sequence[float]]) -> int:
        """写入知识条目及其向量。

        :param entries: 形如 ``[{"category":..,"title":..,"content":..,"source":..}, ...]``
        :param vectors: 与 entries 一一对应的向量
        :return: 写入条数
        """
        if len(entries) != len(vectors):
            raise ValueError(f"条目数({len(entries)})与向量数({len(vectors)})不一致")
        if not entries:
            return 0

        import time

        now_ms = int(time.time() * 1000)
        rows = []
        for entry, vector in zip(entries, vectors):
            if len(vector) != self.dim:
                raise MilvusUnavailableError(
                    f"写入向量维度 {len(vector)} 与 Collection 维度 {self.dim} 不符"
                )
            rows.append({
                "category": entry["category"],
                "title": entry["title"],
                "content": entry["content"],
                "source": entry["source"],
                "embedding": list(vector),
                "created_at": now_ms,
            })

        client = self.client()
        result = client.insert(collection_name=self.collection, data=rows)
        inserted = int(result.get("insert_count", len(rows))) if isinstance(result, dict) else len(rows)

        # 必须显式 flush，否则：
        # 1) get_collection_stats().row_count 会长时间返回 0（统计基于已封存的 segment），
        #    导致 count() 判断失误 —— 实测刚写完 200 条时 row_count 仍为 0；
        # 2) 数据只在内存中，进程/容器异常退出有丢失风险。
        try:
            client.flush(self.collection)
        except Exception as exc:  # noqa: BLE001 - flush 失败不应让导入整体失败
            logger.warning("Milvus flush 失败（数据已写入，统计可能滞后）: %s", exc)

        logger.info("已写入 Milvus %d 条知识（collection=%s）", inserted, self.collection)
        return inserted

    def count(self) -> int:
        """当前 Collection 内的文档数。

        用 ``query(count(*))`` 而不是 ``get_collection_stats().row_count``：
        后者读取的是基于已封存 segment 的统计，刚写入的数据在 flush/封存前不计入
        （实测连续两次调用会得到 0 与 200 两个不同结果），
        而本方法的返回值要用于「判断知识库是否已初始化」，必须准确。
        """
        client = self.client()
        if not self.has_collection():
            return 0
        try:
            rows = client.query(
                collection_name=self.collection,
                filter="",
                output_fields=["count(*)"],
            )
            if rows:
                # 返回形如 [{'count(*)': 200}]
                return int(list(rows[0].values())[0])
        except Exception as exc:  # noqa: BLE001 - 回退到统计值
            logger.warning("count(*) 查询失败，回退到集合统计: %s", exc)

        stats = client.get_collection_stats(self.collection)
        return int(stats.get("row_count", 0))

    # ==================== 检索 ====================

    def search(
        self,
        vector: Sequence[float],
        *,
        top_k: int = 5,
        category: Optional[str] = None,
    ) -> List[KnowledgeHit]:
        """向量检索（COSINE，Top-K，可按分类过滤）。"""
        if not self.has_collection():
            raise MilvusUnavailableError(
                f"知识库未初始化：Milvus 中不存在 Collection {self.collection}。"
                f"请先执行知识导入（启动时自动初始化，或运行 scripts/ingest_knowledge.py）。"
            )

        client = self.client()
        # 分类过滤用 Milvus 的标量过滤表达式；category 来自内部枚举，
        # 但仍做一次引号转义，避免将来接入用户输入时产生表达式注入。
        expr = None
        if category:
            safe = str(category).replace('"', '\\"')
            expr = f'category == "{safe}"'

        results = client.search(
            collection_name=self.collection,
            data=[list(vector)],
            limit=top_k,
            output_fields=["category", "title", "content", "source"],
            search_params={"metric_type": "COSINE", "params": {"nprobe": self.nprobe}},
            filter=expr,
        )

        hits: List[KnowledgeHit] = []
        if not results:
            return hits
        for item in results[0]:
            entity: Dict = item.get("entity", {}) or {}
            hits.append(KnowledgeHit(
                category=entity.get("category", ""),
                title=entity.get("title", ""),
                content=entity.get("content", ""),
                source=entity.get("source", ""),
                score=float(item.get("distance", 0.0)),
            ))
        return hits


def build_store(settings) -> MilvusKnowledgeStore:
    """按配置构造 Milvus 存储。"""
    return MilvusKnowledgeStore(
        host=settings.milvus_host,
        port=settings.milvus_port,
        collection=settings.milvus_collection,
        dim=settings.embedding_dim,
        index_type=settings.milvus_index_type,
        nlist=settings.milvus_nlist,
        nprobe=settings.milvus_nprobe,
    )

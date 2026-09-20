"""知识库初始化 —— 启动时检查并加载 seed_knowledge.json 到 Milvus。

对应规范第二章「Python FastAPI 代码」的 ``app/knowledge_init.py``。

## 规范要求（第 250-254、3544 行）

- **启动时自动初始化**：应用启动即检查 Collection 是否存在且有数据，无则自动导入
- **懒加载检查**：先查数量再决定要不要 Embedding，避免每次启动都重算 200 条向量
- **增量追加**：另有 ``scripts/ingest_knowledge.py`` 支持后期追加自定义条目

## 为什么初始化逻辑要能"空跑"

没有配置 Embedding Key 时（第 5 周早期），embedding 层会退化到 hashing 兜底。
此时仍然把知识导入 Milvus 是有意义的：可以真实验证
「建 Collection → 批量 Embedding → 写入 → 检索」整条链路是否通，
只是检索质量受限于兜底实现。日志里会明确标注当前用的是哪个 provider。
"""

from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import List, Optional

from .config import BASE_DIR, Settings, settings as default_settings
from .embedding import EmbeddingError, get_embedding
from .milvus_client import MilvusKnowledgeStore, MilvusUnavailableError, build_store

logger = logging.getLogger(__name__)

#: 知识库种子数据文件（规范强制：≥200 条、5 大分类、UTF-8 无 BOM）
SEED_FILE = BASE_DIR / "data" / "seed_knowledge.json"

#: 知识库「建造清单」——记录当前 Collection 里的向量是用哪个 Embedding 建的。
#: 为什么必须记：向量检索的正确性依赖「库中向量」与「查询向量」处于**同一个语义空间**。
#: 用 A 模型建库、用 B 模型查询时，余弦相似度计算出来的是无意义的数字，
#: 检索会「成功返回」一批毫不相关的知识，再被大模型自信地引用 —— 这比检索直接失败更糟。
#: 因此导入成功时把 provider/model/dim 落盘，查询前比对，不一致就明确失败。
KB_MANIFEST_FILE = BASE_DIR / "data" / ".kb_manifest.json"

#: 规范要求的 5 大分类（规范第 235-240、298 行）——**白名单**，顺序即规范展示顺序。
#: ``scripts/ingest_knowledge.py`` 用它校验增量条目的 category；``EXPECTED_CATEGORIES``
#: 是同一批分类的「建议条数」，两者的键必须一致（由 tests/test_ingest_knowledge.py 锁定）。
ALLOWED_CATEGORIES = ("动作要领", "营养饮食", "恢复与伤病", "训练计划", "补剂科普")

#: 规范要求的 5 大分类及建议条数（用于校验，不强制精确匹配）
EXPECTED_CATEGORIES = {
    "动作要领": 60,
    "营养饮食": 40,
    "恢复与伤病": 35,
    "训练计划": 35,
    "补剂科普": 30,
}

#: 单条知识 content 的长度约束（规范第 293 行）——**只适用于内置种子文件**
#: ``data/seed_knowledge.json``（规范第 298 行：150-500 字符的语义单元）。
MIN_CONTENT_CHARS = 150
MAX_CONTENT_CHARS = 500

#: 增量入库（``scripts/ingest_knowledge.py``）的长度约束 —— 与 pydantic 模型
#: ``models.KnowledgeEntry`` 完全对齐（title 1-200、content 50-2000）。
#: 刻意**不复用**上面的种子约束：种子文件是「用来建库的标准化语料」，口径更严；
#: 而用户自行追加的条目允许写得更短/更长，只要求能安全落进 Milvus 的 VARCHAR 上限
#: （title 1024 字节、content 8192 字节，中文按最长 4 字节/字符估算 → 200/2000 字符均有余量）。
INGEST_MIN_TITLE_CHARS = 1
INGEST_MAX_TITLE_CHARS = 200
INGEST_MIN_CONTENT_CHARS = 50
INGEST_MAX_CONTENT_CHARS = 2000
#: source 的字符上限。models.KnowledgeEntry 没限制它，但 Milvus schema 里
#: source 是 VARCHAR(1024 字节)；不设上限的话，一条超长来源会在 insert() 时
#: 抛出难懂的 schema 报错，不如在校验阶段就明确指出来。
INGEST_MAX_SOURCE_CHARS = 200


class KnowledgeInitError(RuntimeError):
    """知识库初始化失败。"""


# ======================================================================
# 建造清单（记录当前 Collection 的向量是用哪个 Embedding 建的）
# ======================================================================

def write_manifest(*, provider: str, model: str, dim: int,
                   collection: str, documents: int,
                   path: Optional[Path] = None) -> None:
    """导入成功后写入建造清单（失败不影响主流程，只是失去一致性校验能力）。

    ⚠️ ``built_at`` 有两个消费方，改这里要一起改：

    1. ``check_embedding_consistency`` —— 校验「建库用的 Embedding」与「查询用的 Embedding」一致；
    2. ``milvus_client.read_manifest_built_at`` —— ``/agent/v1/knowledge/health`` 的
       ``last_updated`` 字段直接读它。

    所以**每次真正重建/导入知识库都必须调用本函数**（哪怕其余字段没变）：
    否则重建了索引、数据换了，健康检查的 last_updated 却还是旧时间。
    反过来，「知识库已就绪 → 跳过导入」的路径不该调用它 —— 什么都没变，时间就不该变。
    """
    import datetime

    manifest_file = path or KB_MANIFEST_FILE
    payload = {
        "collection": collection,
        "provider": provider,
        "model": model,
        "dim": dim,
        "documents": documents,
        "built_at": datetime.datetime.now().isoformat(timespec="seconds"),
    }
    try:
        manifest_file.parent.mkdir(parents=True, exist_ok=True)
        manifest_file.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8", newline="\n")
        logger.info("已记录知识库建造清单: provider=%s model=%s documents=%d",
                    provider, model, documents)
    except OSError as exc:
        logger.warning("写入知识库建造清单失败（将失去 Embedding 一致性校验）: %s", exc)


def read_manifest(path: Optional[Path] = None) -> Optional[dict]:
    """读取建造清单；不存在或损坏时返回 None（老库不阻断，只失去校验）。"""
    manifest_file = path or KB_MANIFEST_FILE
    if not manifest_file.exists():
        return None
    try:
        return json.loads(manifest_file.read_text(encoding="utf-8-sig"))
    except (OSError, json.JSONDecodeError) as exc:
        logger.warning("读取知识库建造清单失败: %s", exc)
        return None


def check_embedding_consistency(current_provider: str,
                                path: Optional[Path] = None) -> Optional[str]:
    """校验「当前查询用的 Embedding」与「建库时用的 Embedding」是否一致。

    :return: 一致或无法判断时返回 None；不一致时返回可读的说明（调用方据此决定是否降级）
    """
    manifest = read_manifest(path)
    if not manifest:
        # 没有清单（例如清单被删、或库是更早版本建的）——不阻断，只提示
        logger.warning(
            "未找到知识库建造清单（%s），无法校验 Embedding 一致性。"
            "若刚换过 Embedding 模型，请用 FORCE_RELOAD_KNOWLEDGE=true 重建知识库。",
            KB_MANIFEST_FILE.name,
        )
        return None

    recorded = manifest.get("provider")
    if not recorded:
        return None
    if recorded == current_provider:
        return None

    return (
        f"知识库是用「{recorded}」（{manifest.get('model')}）建立的，"
        f"但当前查询用的是「{current_provider}」——"
        f"两者的向量语义空间不同，检索结果没有意义。"
        f"请设置 FORCE_RELOAD_KNOWLEDGE=true 重建知识库后再查。"
    )


# ======================================================================
# 种子数据加载与校验
# ======================================================================

def load_seed_entries(path: Optional[Path] = None) -> List[dict]:
    """读取并校验种子知识条目。

    校验失败会**明确指出是哪一条**，而不是等 Milvus 报一句看不懂的 schema 错误。
    """
    seed_path = path or SEED_FILE
    if not seed_path.exists():
        raise KnowledgeInitError(
            f"知识库种子文件不存在: {seed_path}。"
            f"请确认 data/seed_knowledge.json 已生成（可用 scripts/merge_seed_parts.py 合并分类文件）。"
        )

    # utf-8-sig：兼容 Windows 编辑器可能写入的 BOM（否则 json.load 会在第一个字符处报错）
    raw = seed_path.read_text(encoding="utf-8-sig")
    try:
        entries = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise KnowledgeInitError(f"种子文件不是合法 JSON: {seed_path} — {exc}") from exc

    if not isinstance(entries, list):
        raise KnowledgeInitError(f"种子文件顶层必须是数组，实际是 {type(entries).__name__}")

    required = {"category", "title", "content", "source"}
    seen_titles = set()
    problems: List[str] = []

    for index, entry in enumerate(entries):
        if not isinstance(entry, dict):
            problems.append(f"第{index}条不是对象")
            continue
        missing = required - set(entry.keys())
        if missing:
            problems.append(f"第{index}条缺字段 {sorted(missing)}")
            continue
        content = str(entry["content"])
        if not (MIN_CONTENT_CHARS <= len(content) <= MAX_CONTENT_CHARS):
            problems.append(
                f"第{index}条 content 长度 {len(content)} 不在 "
                f"{MIN_CONTENT_CHARS}-{MAX_CONTENT_CHARS} 之间（title={entry['title']}）"
            )
        title = str(entry["title"])
        if title in seen_titles:
            problems.append(f"第{index}条标题重复：{title}")
        seen_titles.add(title)

    if problems:
        preview = "\n  - ".join(problems[:10])
        more = f"\n  …另有 {len(problems) - 10} 处问题" if len(problems) > 10 else ""
        raise KnowledgeInitError(f"种子数据校验未通过（共 {len(problems)} 处）：\n  - {preview}{more}")

    return entries


def summarize_entries(entries: List[dict]) -> dict:
    """统计各分类条数，便于与规范要求的分布对比。"""
    counts: dict = {}
    for entry in entries:
        counts[entry["category"]] = counts.get(entry["category"], 0) + 1
    return counts


# ======================================================================
# 初始化
# ======================================================================

def ensure_knowledge_base(
    settings: Optional[Settings] = None,
    *,
    store: Optional[MilvusKnowledgeStore] = None,
    recreate: bool = False,
    force_reload: bool = False,
    seed_path: Optional[Path] = None,
) -> dict:
    """确保 Milvus 中存在可用的知识库。幂等。

    决策逻辑：
    1. 检查/创建 Collection 与索引
    2. 统计已有文档数：
       - 已 ≥ 种子条数且 ``force_reload=False`` → 直接返回（不重复 Embedding，省时间省钱）
       - 否则清空后全量导入

    :return: 初始化结果摘要
    """
    cfg = settings or default_settings
    milvus = store or build_store(cfg)

    result = {
        "collection": cfg.milvus_collection,
        "created": False,
        "loaded": 0,
        "skipped": False,
        "total_documents": 0,
        "embedding_provider": cfg.resolved_embedding_provider,
        "categories": {},
    }

    # --- Step 1：Collection 与索引 ---
    try:
        result["created"] = milvus.ensure_collection(recreate=recreate)
    except MilvusUnavailableError as exc:
        raise KnowledgeInitError(f"Milvus 不可用，知识库初始化中止: {exc}") from exc

    # --- Step 2：种子数据 ---
    entries = load_seed_entries(seed_path)
    result["categories"] = summarize_entries(entries)
    logger.info("种子知识条目: 共 %d 条，分类分布 %s", len(entries), result["categories"])

    required_categories = set(EXPECTED_CATEGORIES)
    missing_categories = required_categories - set(result["categories"])
    if missing_categories:
        # 只告警不阻断：规范要求 5 大分类齐全，但缺分类时导入仍然可用
        logger.warning("种子数据缺少规范要求的分类: %s", sorted(missing_categories))

    # --- Step 3：判断是否需要导入 ---
    existing = milvus.count()
    result["total_documents"] = existing

    if existing > 0 and not force_reload and not result["created"]:
        if existing >= len(entries):
            logger.info(
                "知识库已就绪（现有 %d 条 ≥ 种子 %d 条），跳过导入。"
                "需要强制重建请设置 FORCE_RELOAD=true。",
                existing, len(entries),
            )
            result["skipped"] = True
            return result
        logger.info("知识库现有 %d 条 < 种子 %d 条，将全量重建以保证一致性",
                    existing, len(entries))

    if existing > 0:
        # 走到这里说明要重建：先清空，避免重复导入产生重复条目
        milvus.drop_collection()
        milvus.ensure_collection()
        result["total_documents"] = 0

    # --- Step 4：Embedding 并写入 ---
    try:
        embedder = get_embedding(cfg)
    except EmbeddingError as exc:
        raise KnowledgeInitError(f"Embedding 初始化失败，无法导入知识库: {exc}") from exc

    texts = [f"{e['title']}\n{e['content']}" for e in entries]
    logger.info(
        "开始向量化 %d 条知识（provider=%s, model=%s, dim=%d, batch=%d）…",
        len(texts), embedder.provider, embedder.model, embedder.dim, cfg.embedding_batch_size,
    )

    try:
        vectors = embedder.embed_documents(texts)
    except EmbeddingError as exc:
        raise KnowledgeInitError(f"知识条目向量化失败: {exc}") from exc

    loaded = milvus.insert_entries(entries, vectors)
    result["loaded"] = loaded
    result["total_documents"] = milvus.count()

    # 记录「这批向量是用谁建的」——查询时据此校验 Embedding 一致性
    write_manifest(
        provider=embedder.provider,
        model=embedder.model,
        dim=embedder.dim,
        collection=cfg.milvus_collection,
        documents=result["total_documents"],
    )

    logger.info(
        "知识库初始化完成: 写入 %d 条，当前总数 %d 条（provider=%s）",
        loaded, result["total_documents"], embedder.provider,
    )
    return result

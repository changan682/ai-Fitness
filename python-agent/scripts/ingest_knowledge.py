#!/usr/bin/env python
"""知识库入库工具 —— 统计 / 增量追加 / 重建，一个命令搞定。

规范（提示词.txt 第 254、259 行）要求「提供 Python 脚本 scripts/ingest_knowledge.py，
支持后期追加自定义知识条目」；``app/knowledge_init.py`` 与 ``app/milvus_client.py``
的报错文案里也都指向本脚本。

## 三种模式

| 模式 | 作用 | 连 Milvus |
| --- | --- | --- |
| ``--stats`` | 看统计：Collection 是否存在、总条数、**5 大分类分布**、索引类型、向量维度、last_updated | ✅ |
| ``--file x.json`` | **增量追加**自定义条目（格式与 ``data/seed_knowledge.json`` 完全一致） | ✅ |
| ``--rebuild`` | **删除并重建** Collection，重新导入种子数据（可叠加 ``--file`` 一起导入） | ✅ |
| ``--dry-run`` | 只校验文件、不连 Milvus、不写入（可与 ``--file`` / ``--rebuild`` 叠加） | ❌ |

## 用法

```powershell
# 1) 看知识库现在什么状况（最常用）
E:\\Anaconde\\python.exe scripts/ingest_knowledge.py --stats

# 2) 追加自定义条目（只校验，先不写）
E:\\Anaconde\\python.exe scripts/ingest_knowledge.py --file data/my_knowledge.json --dry-run

# 3) 追加自定义条目（真写）
E:\\Anaconde\\python.exe scripts/ingest_knowledge.py --file data/my_knowledge.json

# 4) 重建整个知识库（种子 200 条 + 自定义条目一起导入）
E:\\Anaconde\\python.exe scripts/ingest_knowledge.py --rebuild --file data/my_knowledge.json
```

自定义 JSON 文件格式（与 ``seed_knowledge.json`` 一致，顶层数组）：

```json
[
  {"category": "动作要领", "title": "硬拉起始姿势", "content": "（50-2000 字符）", "source": "NSCA力量训练指南"}
]
```

> 终端里中文/图标显示成乱码时，用 ``E:\\Anaconde\\python.exe -X utf8 scripts/ingest_knowledge.py --stats``
> 强制 Python 以 UTF-8 输出即可（代码里已对老式 GBK 控制台做了兜底，不会因此崩溃）。

## 退出码

- ``0``：成功（含「全部已存在、没有新增」这种无需写入的情况）
- ``1``：校验失败 / 写入失败 / Milvus 不可用 —— 都会先打印一句人能看懂的中文原因

## 为什么复用 app 里的模块而不是自己写一遍

Embedding 与写入这两段逻辑一旦有第二份实现，就会慢慢分叉：
``insert_entries`` 里那句「必须显式 flush」、``embed_documents`` 的分批大小、
以及写向量时对维度的校验，都是踩过坑才补上的。因此本脚本只做
「读文件 → 校验 → 编排」，向量化调 ``app.embedding.get_embedding``，
写入调 ``app.milvus_client.MilvusKnowledgeStore``，重建后写清单调
``app.knowledge_init.write_manifest``（它顺带刷新健康检查的 last_updated）。

脚本**绝不打印明文 API Key**：所有要打印的文本都过一遍 ``redact_secrets``。
"""

from __future__ import annotations

import argparse
import json
import logging
import sys
from collections import Counter
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Tuple

# 让脚本能 import 到 app 包（脚本在 scripts/ 下，包在上级目录）
BASE_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(BASE_DIR))

from app.config import settings  # noqa: E402  - 必须在 sys.path 调整之后导入
from app.embedding import EmbeddingError, get_embedding  # noqa: E402
from app.knowledge_init import (  # noqa: E402
    ALLOWED_CATEGORIES,
    EXPECTED_CATEGORIES,
    INGEST_MAX_CONTENT_CHARS,
    INGEST_MAX_SOURCE_CHARS,
    INGEST_MAX_TITLE_CHARS,
    INGEST_MIN_CONTENT_CHARS,
    INGEST_MIN_TITLE_CHARS,
    SEED_FILE,
    KnowledgeInitError,
    check_embedding_consistency,
    load_seed_entries,
    read_manifest,
    summarize_entries,
    write_manifest,
)
from app.milvus_client import (  # noqa: E402
    MilvusKnowledgeStore,
    MilvusUnavailableError,
    build_store,
)
from app.utils import mask_secret  # noqa: E402

#: 知识条目的四个必备字段（规范第 235-241 行的 Collection 字段）
REQUIRED_FIELDS = ("category", "title", "content", "source")

#: 分类统计时最多取多少行。Milvus 的 query 单次默认上限是 16384，
#: 200 条规模的库远够用；真涨到上限后再改成分页统计。
MAX_QUERY_ROWS = 16384

EXIT_OK = 0
EXIT_FAIL = 1


def make_output_encoding_safe() -> None:
    """让 stdout/stderr 在编不出某个字符时用 ``?`` 顶替，而不是直接抛异常。

    为什么需要：Python 在 Windows 上默认按**控制台代码页**编码输出，
    老式 cmd / PowerShell 5.1 常是 GBK(cp936)，而 ``✅``(U+2705)、``⚠️`` 这类符号
    在 GBK 里没有对应编码 —— 一 print 就 ``UnicodeEncodeError``，
    整个脚本在**最后一步汇报结果时**崩掉（数据其实已经写好了，非常误导人）。

    ``errors="replace"`` 只影响编不出来的那几个字符：中文正文照常输出，
    编不出的图标退化成 ``?``。终端本身支持 UTF-8 时（Windows Terminal / pwsh 7）
    行为完全不变。
    """
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(errors="replace")  # type: ignore[union-attr]
        except (AttributeError, ValueError, OSError):
            # stdout 被重定向成非文本流（如测试里的 StringIO）时没有 reconfigure，忽略即可
            pass


class IngestError(RuntimeError):
    """入库过程中的可预期失败（文件读不了、格式不对等），消息直接给用户看。"""


# ======================================================================
# 脱敏
# ======================================================================

def redact_secrets(text: object,
                   secrets: Optional[Sequence[Optional[str]]] = None) -> str:
    """把文本里可能出现的明文 API Key 换成脱敏形式。

    为什么连异常信息也要过一遍：Embedding 的报错文本来自第三方 SDK / httpx，
    我们没有逐字审查过它们会不会把 Authorization 头带进异常字符串里。
    这里做一次兜底替换，保证「日志里绝不出现明文 Key」这条规范要求（第十章）
    不依赖于第三方库的实现细节。

    :param secrets: 仅用于测试注入；默认取配置里的三个 Key
    """
    output = str(text)
    candidates = list(secrets) if secrets is not None else [
        settings.deepseek_api_key,
        settings.dashscope_api_key,
        settings.siliconflow_api_key,
    ]
    for secret in candidates:
        # 太短的值（例如 "abc"）做全局替换会误伤正常文本，跳过
        if secret and len(str(secret).strip()) >= 8:
            output = output.replace(str(secret).strip(), mask_secret(str(secret).strip()))
    return output


# ======================================================================
# 校验（纯逻辑，不碰 Milvus —— 便于单测）
# ======================================================================

def escape_filter_value(value: object) -> str:
    """转义 Milvus 标量过滤表达式里的字符串字面量。

    与 ``milvus_client.search`` 里 category 过滤同样的思路（那里是防表达式注入），
    但这里必须**先转义反斜杠再转义引号**：反过来的话，引号转义产生的 ``\\"``
    会被第二次替换再次加杠，表达式直接语法错误。
    """
    return str(value).replace("\\", "\\\\").replace('"', '\\"')


def duplicate_filter(category: str, title: str) -> str:
    """构造「同一 (category, title) 是否已存在」的 Milvus 过滤表达式。

    两个字段一起用，是因为 ``title`` 在 Collection 里不是唯一键（Milvus 也没法
    对 VARCHAR 建唯一索引）：不同分类下完全可能有一条同名条目。
    """
    return (f'category == "{escape_filter_value(category)}" '
            f'and title == "{escape_filter_value(title)}"')


def validate_entry(entry: object, index: int) -> List[str]:
    """校验单条知识条目，返回问题描述列表（空列表 = 合格）。

    :param index: 该条目在文件里的**数组下标（0 基）**；
        报错文案里统一用 1 基的「第 N 条」，方便用户直接对着文件数行
    """
    label = f"第{index + 1}条"
    problems: List[str] = []

    if not isinstance(entry, dict):
        return [f"{label}：不是 JSON 对象（实际是 {type(entry).__name__}）"]

    missing = [field for field in REQUIRED_FIELDS if field not in entry]
    if missing:
        # 缺字段时后面的长度检查没有意义，直接返回，避免刷一堆连带错误
        return [f"{label}：缺少字段 {missing}（必须有 {'/'.join(REQUIRED_FIELDS)} 四个字段）"]

    # --- 类型与空值 ---
    values: Dict[str, str] = {}
    for field in REQUIRED_FIELDS:
        raw = entry[field]
        if not isinstance(raw, str):
            problems.append(
                f"{label}：字段 {field} 必须是字符串，实际是 {type(raw).__name__}"
                f"（值为 {raw!r}）"
            )
        else:
            values[field] = raw.strip()
    if problems:
        return problems

    # --- category 白名单 ---
    if values["category"] not in ALLOWED_CATEGORIES:
        problems.append(
            f"{label}：category「{values['category']}」不在白名单里，"
            f"只能是 {' / '.join(ALLOWED_CATEGORIES)}"
        )

    # --- 长度（按字符计：models.KnowledgeEntry 的 min_length/max_length 也是字符）---
    title_len = len(values["title"])
    if not (INGEST_MIN_TITLE_CHARS <= title_len <= INGEST_MAX_TITLE_CHARS):
        problems.append(
            f"{label}：title 长度 {title_len} 不在 "
            f"{INGEST_MIN_TITLE_CHARS}-{INGEST_MAX_TITLE_CHARS} 字符之间"
            f"（title={values['title'][:40]!r}）"
        )

    content_len = len(values["content"])
    if not (INGEST_MIN_CONTENT_CHARS <= content_len <= INGEST_MAX_CONTENT_CHARS):
        problems.append(
            f"{label}：content 长度 {content_len} 不在 "
            f"{INGEST_MIN_CONTENT_CHARS}-{INGEST_MAX_CONTENT_CHARS} 字符之间"
            f"（title={values['title'][:40]!r}）"
        )

    # --- source 必填 ---
    if not values["source"]:
        problems.append(f"{label}：字段 source 不能为空（要求标注参考来源，如「NSCA力量训练指南」）")
    elif len(values["source"]) > INGEST_MAX_SOURCE_CHARS:
        problems.append(
            f"{label}：source 长度 {len(values['source'])} 超过 "
            f"{INGEST_MAX_SOURCE_CHARS} 字符上限（Milvus 里 source 是 VARCHAR(1024 字节)）"
        )

    return problems


def normalize_entry(entry: dict) -> dict:
    """把条目整理成写库用的四个字段（去掉首尾空白，丢掉多余字段）。

    丢掉多余字段是刻意的：Collection schema 里 ``enable_dynamic_field=False``，
    多余的键会在 insert 时直接报错，不如提前收敛。``created_at`` 由 store 统一填。
    """
    return {field: str(entry[field]).strip() for field in REQUIRED_FIELDS}


def validate_entries(entries: Sequence[object]) -> List[str]:
    """校验整批条目：逐条校验 + 批内 (category, title) 去重。

    :return: 问题描述列表（空 = 全部合格）
    """
    problems: List[str] = []
    seen: Dict[Tuple[str, str], int] = {}

    for index, entry in enumerate(entries):
        problems.extend(validate_entry(entry, index))
        if not isinstance(entry, dict):
            continue
        category, title = entry.get("category"), entry.get("title")
        if not isinstance(category, str) or not isinstance(title, str):
            continue
        key = (category.strip(), title.strip())
        if key in seen:
            problems.append(
                f"第{index + 1}条：与第{seen[key] + 1}条重复"
                f"（category + title 完全相同：{key[0]} / {key[1]}）"
            )
        else:
            seen[key] = index

    return problems


def load_entries_file(path: Path) -> List[dict]:
    """读取用户提供的 JSON 文件（格式同 seed_knowledge.json）。

    解析失败直接抛 ``IngestError``，消息里带上文件路径与真实原因。
    """
    if not path.exists():
        raise IngestError(f"文件不存在: {path}")
    if path.is_dir():
        raise IngestError(f"这是一个目录，不是 JSON 文件: {path}")

    try:
        # utf-8-sig：兼容 Windows 编辑器写入的 BOM（否则 json.loads 会在第一个字符报错）
        raw = path.read_text(encoding="utf-8-sig")
    except (OSError, UnicodeDecodeError) as exc:
        raise IngestError(f"读取文件失败: {path} — {exc}") from exc

    try:
        entries = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise IngestError(
            f"不是合法 JSON: {path}（第 {exc.lineno} 行第 {exc.colno} 列：{exc.msg}）"
        ) from exc

    if not isinstance(entries, list):
        raise IngestError(f"JSON 顶层必须是数组，实际是 {type(entries).__name__}: {path}")
    if not entries:
        raise IngestError(f"文件里没有任何条目: {path}")
    return entries


# ======================================================================
# 与 Milvus 交互的小工具
# ======================================================================

def category_distribution(store: MilvusKnowledgeStore) -> Dict[str, int]:
    """统计 Collection 内 5 大分类的实际条数。"""
    rows = store.client().query(
        collection_name=store.collection,
        filter="",
        output_fields=["category"],
        limit=MAX_QUERY_ROWS,
    )
    return dict(Counter(str(row.get("category", "")) for row in rows or []))


def find_existing(store: MilvusKnowledgeStore, entries: Sequence[dict]) -> List[dict]:
    """找出库中已存在（category 与 title 都相同）的条目。

    :return: 与库中重复的条目列表（调用方负责跳过它们）
    """
    client = store.client()
    existing: List[dict] = []
    for entry in entries:
        rows = client.query(
            collection_name=store.collection,
            filter=duplicate_filter(entry["category"], entry["title"]),
            output_fields=["category", "title"],
            limit=1,
        )
        if rows:
            existing.append(entry)
    return existing


def collect_entries(file_path: Optional[Path],
                    *, include_seed: bool) -> Tuple[List[dict], List[str]]:
    """载入并校验待入库条目。

    :return: ``(合格条目, 问题列表)``；问题列表非空时调用方应直接返回退出码 1
    """
    entries: List[dict] = []
    problems: List[str] = []

    if include_seed:
        try:
            seed_entries = load_seed_entries()
        except KnowledgeInitError as exc:
            problems.append(f"种子文件校验未通过：{exc}")
            seed_entries = []
        entries.extend(seed_entries)
        print(f"  种子 {SEED_FILE.name:24} 载入 {len(seed_entries):3} 条")

    if file_path is not None:
        try:
            custom = load_entries_file(file_path)
        except IngestError as exc:
            # 文件本身读不了/不是 JSON，后面的条目校验没有意义
            problems.append(str(exc))
            return [], problems
        file_problems = validate_entries(custom)
        if file_problems:
            # 有问题的条目**不放进 entries**：后续的合并查重/统计只处理干净数据，
            # 避免一个缺字段的条目在下游再炸出与真实原因无关的 KeyError
            problems.extend(file_problems)
        else:
            entries.extend(normalize_entry(e) for e in custom)
        print(f"  自定义 {file_path.name:22} 载入 {len(custom):3} 条")

    # 种子 + 自定义混在一起时也要查重（自定义条目撞了种子标题同样不该入库）
    if include_seed and file_path is not None:
        problems.extend(_cross_duplicates(entries))

    return entries, problems


def _cross_duplicates(entries: Sequence[dict]) -> List[str]:
    """种子与自定义混编后的重复检查（自定义条目内部重复已在校验阶段报过）。"""
    seen: Dict[Tuple[str, str], int] = {}
    problems: List[str] = []
    for index, entry in enumerate(entries):
        if not isinstance(entry, dict) or entry.get("category") is None \
                or entry.get("title") is None:
            continue
        key = (str(entry["category"]), str(entry["title"]))
        if key in seen:
            problems.append(
                f"合并后第{index + 1}条与第{seen[key] + 1}条重复"
                f"（{key[0]} / {key[1]}）—— 自定义文件的条目与种子数据撞了，请改名或删掉"
            )
        else:
            seen[key] = index
    return problems


def print_problems(problems: Sequence[str]) -> None:
    """统一打印校验问题（最多 15 条，其余折叠）。"""
    print(f"\n❌ 校验未通过（共 {len(problems)} 处）：")
    for item in problems[:15]:
        print(f"  - {item}")
    if len(problems) > 15:
        print(f"  …另有 {len(problems) - 15} 处问题")


def print_distribution(entries_or_counts, *, compare_expected: bool = False) -> None:
    """按规范的 5 大分类打印分布。

    :param compare_expected: 是否与规范建议条数对比（``--stats`` 看真实库时才对比；
        校验模式（--dry-run）只看本次文件的分布，对比规范建议值会满屏 ⚠️ 反而是噪声）
    """
    if isinstance(entries_or_counts, dict):
        counts = entries_or_counts
    else:
        counts = summarize_entries(list(entries_or_counts))

    for category in ALLOWED_CATEGORIES:
        actual = counts.get(category, 0)
        if not compare_expected:
            print(f"    ·  {category:8} {actual:4} 条")
            continue
        expected = EXPECTED_CATEGORIES.get(category, 0)
        flag = "✅" if actual >= expected else ("⚠️ " if actual else "❌")
        print(f"    {flag} {category:8} 实际 {actual:4} 条 / 规范建议 {expected}")
    unknown = sorted(set(counts) - set(ALLOWED_CATEGORIES))
    if unknown:
        print(f"    ⚠️  出现白名单外的分类（历史遗留）: {unknown}")


# ======================================================================
# 失败出口
# ======================================================================

def fail_milvus(store: MilvusKnowledgeStore, detail: object) -> int:
    """Milvus 连不上时的统一出口：一句人话 + 可执行的下一步，绝不甩堆栈。"""
    print(f"\n❌ Milvus 未启动或地址不对（{store.uri}）")
    print(f"   详情: {redact_secrets(detail)}")
    print("   💡 排查顺序：")
    print("      1) 确认 Milvus 已启动（项目根目录 docker compose up -d milvus）")
    print("      2) 确认 MILVUS_HOST / MILVUS_PORT 与容器端口一致（默认 localhost:19530）")
    print("      3) 用 E:\\Anaconde\\python.exe scripts/smoke_test.py 复核服务整体状态")
    return EXIT_FAIL


# ======================================================================
# 模式一：--stats
# ======================================================================

def run_stats() -> int:
    """打印知识库统计（Collection、总条数、分类分布、索引、维度、last_updated）。"""
    store = build_store(settings)

    print("=" * 72)
    print("知识库统计（Milvus）")
    print("=" * 72)
    print(f"  Milvus 地址  : {store.uri}")
    print(f"  Collection   : {store.collection}")

    try:
        info = store.health()
        if not info.get("milvus_connected"):
            # health() 刻意不抛异常，把失败原因放在 error 字段里
            return fail_milvus(store, info.get("error") or "连接失败")
        exists = store.has_collection()
    except MilvusUnavailableError as exc:
        return fail_milvus(store, exc)
    except Exception as exc:  # noqa: BLE001 - CLI 只给一句人话，不给堆栈
        print(f"\n❌ 查询 Milvus 失败: {redact_secrets(exc)}")
        return EXIT_FAIL

    if not exists:
        print("\n  ⚠️  Collection 不存在 —— 知识库还没建过。")
        print("     两种办法：")
        print("       1) E:\\Anaconde\\python.exe scripts/ingest_knowledge.py --rebuild")
        print("       2) 启动 FastAPI 服务（启动时会自动导入种子知识，幂等）")
        return EXIT_FAIL

    try:
        counts = category_distribution(store)
    except Exception as exc:  # noqa: BLE001
        return fail_milvus(store, exc)

    total = info.get("total_documents")
    print(f"  总条数       : {total} 条")
    print(f"  向量维度     : {info.get('embedding_dim')}（规范要求 768，需与 Embedding 模型严格一致）")
    print(f"  索引类型     : {info.get('index_type') or '(索引信息读取失败)'}")
    print(f"  最后更新     : {info.get('last_updated') or '(暂无记录)'}"
          f"  ← 来自 data/.kb_manifest.json 的 built_at")
    print(f"  分类分布（共 {sum(counts.values())} 条）：")
    print_distribution(counts, compare_expected=True)

    if total is None or sum(counts.values()) < int(total):
        print("\n  ⚠️  分类统计条数少于总数：可能只读到部分 segment，稍后重试即可")

    # 索引参数与配置不一致（改了 MILVUS_NLIST 却没重建）是最隐蔽的坑：
    # 文档写 nlist=16、实际索引还是 128，检索质量与论文描述就对不上。
    if info.get("index_warning"):
        print(f"\n  ⚠️  {info['index_warning']}")
        print("     修复：FORCE_RELOAD_KNOWLEDGE=true 重启服务，或执行 --rebuild")

    print("\n" + "=" * 72)
    print("✅ 统计完成")
    return EXIT_OK


# ======================================================================
# 模式二：--dry-run（只校验）
# ======================================================================

def run_dry_run(args: argparse.Namespace) -> int:
    """只校验文件内容与长度约束，不连 Milvus、不写任何东西。"""
    print("=" * 72)
    print("校验模式（--dry-run：不连 Milvus、不写入）")
    print("=" * 72)

    entries, problems = collect_entries(args.file, include_seed=args.rebuild)
    if problems:
        print_problems(problems)
        return EXIT_FAIL

    print(f"\n  合计待写入: {len(entries)} 条")
    print("  分类分布：")
    print_distribution(entries)
    print("\n  ⚠️  --dry-run 跳过了「库里是否已存在」的去重检查（没连 Milvus）；")
    print("     真正入库时会按 (category, title) 自动跳过已存在的条目。")
    print("\n" + "=" * 72)
    print("✅ 校验通过（未写入任何数据）")
    return EXIT_OK


# ======================================================================
# 模式三：--rebuild
# ======================================================================

def run_rebuild(args: argparse.Namespace) -> int:
    """删除并重建 Collection，导入种子数据（+ 可选的 --file）。"""
    print("=" * 72)
    print("重建知识库（删除 Collection → 重新 Embedding → 重新导入）")
    print("=" * 72)

    entries, problems = collect_entries(args.file, include_seed=True)
    if problems:
        print_problems(problems)
        return EXIT_FAIL

    store = build_store(settings)

    # --- 先向量化，再删旧库 ---
    # 顺序很重要：Embedding 可能因为 Key 失效/网络问题失败。若先 drop 再 embed，
    # 一旦失败就落得「旧库没了、新库也没建起来」——知识库直接不可用。
    # 反过来先算好向量，失败时旧库原封不动，用户只要修好 Key 重跑即可。
    try:
        embedder = get_embedding(settings)
    except EmbeddingError as exc:
        print(f"\n❌ Embedding 初始化失败: {redact_secrets(exc)}")
        return EXIT_FAIL

    texts = [f"{e['title']}\n{e['content']}" for e in entries]
    print(f"\n  正在向量化 {len(entries)} 条（provider={embedder.provider}, "
          f"model={embedder.model}, dim={embedder.dim}）…")
    try:
        vectors = embedder.embed_documents(texts)
    except EmbeddingError as exc:
        print(f"\n❌ 向量化失败（旧知识库未被改动）: {redact_secrets(exc)}")
        return EXIT_FAIL
    except Exception as exc:  # noqa: BLE001 - 第三方 SDK 的异常类型不受我们控制
        print(f"\n❌ 向量化失败（旧知识库未被改动）: {redact_secrets(exc)}")
        return EXIT_FAIL

    if vectors and len(vectors[0]) != embedder.dim:
        print(f"\n❌ Embedding 维度不符：返回 {len(vectors[0])} 维，配置要求 {embedder.dim} 维")
        return EXIT_FAIL

    try:
        created = store.ensure_collection(recreate=True)
        inserted = store.insert_entries(entries, vectors)
        total = store.count()
    except MilvusUnavailableError as exc:
        return fail_milvus(store, exc)
    except Exception as exc:  # noqa: BLE001
        print(f"\n❌ 写入 Milvus 失败: {redact_secrets(exc)}")
        return EXIT_FAIL

    # 刷新建造清单：provider/model/dim（一致性校验用）+ built_at（健康检查的 last_updated）
    write_manifest(
        provider=embedder.provider,
        model=embedder.model,
        dim=embedder.dim,
        collection=settings.milvus_collection,
        documents=total,
    )

    counts = summarize_entries(list(entries))
    print(f"\n  Collection   : {store.collection}（{'本次新建' if created else '已重建'}）")
    print(f"  写入条数     : {inserted} 条")
    print(f"  当前总数     : {total} 条")
    print(f"  向量来源     : {embedder.provider} / {embedder.model}（{embedder.dim} 维）")
    print("  分类分布：")
    print_distribution(counts, compare_expected=True)

    print("\n" + "=" * 72)
    print("✅ 重建完成。健康检查的 last_updated 已随之刷新")
    print("   （E:\\Anaconde\\python.exe scripts/ingest_knowledge.py --stats 可复核）")
    return EXIT_OK


# ======================================================================
# 模式四：--file（增量追加）
# ======================================================================

def run_append(args: argparse.Namespace) -> int:
    """把 --file 的条目增量追加进已有知识库（同 category+title 的条目会被跳过）。"""
    print("=" * 72)
    print("增量追加知识条目")
    print("=" * 72)

    entries, problems = collect_entries(args.file, include_seed=False)
    if problems:
        print_problems(problems)
        return EXIT_FAIL

    store = build_store(settings)

    try:
        if not store.has_collection():
            print(f"\n❌ Milvus 里还没有 Collection「{store.collection}」，无法增量追加。")
            print("   💡 先建库再追加：")
            print("      E:\\Anaconde\\python.exe scripts/ingest_knowledge.py --rebuild")
            return EXIT_FAIL
    except MilvusUnavailableError as exc:
        return fail_milvus(store, exc)
    except Exception as exc:  # noqa: BLE001
        return fail_milvus(store, exc)

    try:
        embedder = get_embedding(settings)
    except EmbeddingError as exc:
        print(f"\n❌ Embedding 初始化失败: {redact_secrets(exc)}")
        return EXIT_FAIL

    # --- 一致性检查：追加的向量必须与库里已有向量处在同一语义空间 ---
    # 这是本脚本最容易被忽略的一步：用 A 模型建的库、拿 B 模型追加几条，
    # 检索不会报错，只会「成功」返回一批毫不相关的知识给大模型引用。
    mismatch = check_embedding_consistency(embedder.provider)
    if mismatch:
        print(f"\n❌ 拒绝追加：{mismatch}")
        print("   💡 直接重建即可（会把种子数据 + 本次 --file 一起重新向量化）：")
        print("      E:\\Anaconde\\python.exe scripts/ingest_knowledge.py "
              f"--rebuild --file {args.file}")
        return EXIT_FAIL
    if read_manifest() is None:
        print("  ⚠️  未找到建造清单，无法校验 Embedding 一致性，将直接追加；")
        print("     若刚换过 Embedding 模型，请改用 --rebuild 全量重建。")

    # --- 去重：同 (category, title) 不入库第二次 ---
    try:
        existing = find_existing(store, entries)
    except Exception as exc:  # noqa: BLE001
        return fail_milvus(store, exc)

    existing_keys = {(e["category"], e["title"]) for e in existing}
    new_entries = [e for e in entries if (e["category"], e["title"]) not in existing_keys]

    if existing:
        print(f"\n  跳过已在库中的 {len(existing)} 条：")
        for entry in existing[:10]:
            print(f"    - [{entry['category']}] {entry['title']}")
        if len(existing) > 10:
            print(f"    …另有 {len(existing) - 10} 条")

    if not new_entries:
        print("\n" + "=" * 72)
        print("✅ 没有需要新增的条目（文件里的条目在库中都已存在）")
        return EXIT_OK

    try:
        vectors = embedder.embed_documents(
            [f"{e['title']}\n{e['content']}" for e in new_entries]
        )
        inserted = store.insert_entries(new_entries, vectors)
        total = store.count()
    except EmbeddingError as exc:
        print(f"\n❌ 向量化失败: {redact_secrets(exc)}")
        return EXIT_FAIL
    except MilvusUnavailableError as exc:
        return fail_milvus(store, exc)
    except Exception as exc:  # noqa: BLE001
        print(f"\n❌ 入库失败: {redact_secrets(exc)}")
        return EXIT_FAIL

    # 内容变了 → 刷新建造清单（built_at 即健康检查的 last_updated）
    write_manifest(
        provider=embedder.provider,
        model=embedder.model,
        dim=embedder.dim,
        collection=settings.milvus_collection,
        documents=total,
    )

    print(f"\n  新增条数     : {inserted} 条（向量来源 {embedder.provider} / {embedder.model}）")
    print(f"  当前总数     : {total} 条")
    print("  新增明细：")
    for entry in new_entries[:10]:
        print(f"    + [{entry['category']}] {entry['title']}")
    if len(new_entries) > 10:
        print(f"    …另有 {len(new_entries) - 10} 条")

    print("\n" + "=" * 72)
    print("✅ 追加完成。健康检查的 last_updated 已刷新")
    return EXIT_OK


# ======================================================================
# 入口
# ======================================================================

def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="ingest_knowledge.py",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        description="知识库入库工具：统计 / 增量追加 / 重建（复用 app 的 Embedding 与 Milvus 写入逻辑）",
        epilog=(
            "示例：\n"
            "  E:\\Anaconde\\python.exe scripts/ingest_knowledge.py --stats\n"
            "  E:\\Anaconde\\python.exe scripts/ingest_knowledge.py --file data/my_knowledge.json --dry-run\n"
            "  E:\\Anaconde\\python.exe scripts/ingest_knowledge.py --file data/my_knowledge.json\n"
            "  E:\\Anaconde\\python.exe scripts/ingest_knowledge.py --rebuild --file data/my_knowledge.json\n"
        ),
    )
    parser.add_argument("--stats", action="store_true",
                        help="查看统计：Collection / 总条数 / 分类分布 / 索引 / 维度 / last_updated")
    parser.add_argument("--file", type=Path, metavar="PATH",
                        help="增量追加的 JSON 文件（格式与 data/seed_knowledge.json 一致）")
    parser.add_argument("--rebuild", action="store_true",
                        help="删除并重建 Collection，重新导入种子数据（可叠加 --file 一起导入）")
    parser.add_argument("--dry-run", action="store_true",
                        help="只校验文件、不连 Milvus、不写入（可与 --file / --rebuild 叠加）")
    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    make_output_encoding_safe()

    parser = build_parser()
    args = parser.parse_args(argv)

    # 让 app 模块内部的 logger 输出到控制台：导入过程的进度/告警对使用者有用
    logging.basicConfig(level=logging.INFO, format="[%(levelname)s] %(name)s - %(message)s")

    if args.stats:
        if args.file or args.rebuild or args.dry_run:
            print("❌ --stats 只读统计，不能与 --file / --rebuild / --dry-run 同时使用")
            return EXIT_FAIL
        return run_stats()

    if not args.file and not args.rebuild:
        print("❌ 请至少指定一种模式：--stats / --file <路径> / --rebuild")
        print("   例：E:\\Anaconde\\python.exe scripts/ingest_knowledge.py --stats")
        print("   详细用法：E:\\Anaconde\\python.exe scripts/ingest_knowledge.py --help")
        return EXIT_FAIL

    if args.dry_run:
        return run_dry_run(args)
    if args.rebuild:
        return run_rebuild(args)
    return run_append(args)


if __name__ == "__main__":
    sys.exit(main())

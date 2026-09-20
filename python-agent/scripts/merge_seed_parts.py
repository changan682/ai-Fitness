#!/usr/bin/env python
"""把分类种子文件合并成规范要求的 ``data/seed_knowledge.json``。

## 为什么需要它

规范（提示词.txt 第 251、293 行）强制要求知识库种子文件是**单个**
``data/seed_knowledge.json``，且至少 200 条、覆盖 5 大分类、
``content`` 长度 150-500 字符、UTF-8 无 BOM。

但 200 条内容按分类分批产出更不容易出错，因此约定：
- 各分类分别放在 ``data/seed_parts/<分类名>.json``（中间产物，便于单独修订）
- 本脚本把它们合并、校验、去重，输出最终的 ``seed_knowledge.json``

## 用法

```bash
# 默认：合并 + 校验 + 写入
E:\\Anaconde\\python.exe scripts/merge_seed_parts.py

# 只看校验结果，不写文件
E:\\Anaconde\\python.exe scripts/merge_seed_parts.py --dry-run
```
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

# 让脚本能 import 到 app 包（脚本在 scripts/ 下，包在上级目录）
BASE_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(BASE_DIR))

from app.knowledge_init import (  # noqa: E402  - 必须在 sys.path 调整之后导入
    EXPECTED_CATEGORIES,
    MAX_CONTENT_CHARS,
    MIN_CONTENT_CHARS,
)

SEED_PARTS_DIR = BASE_DIR / "data" / "seed_parts"
SEED_FILE = BASE_DIR / "data" / "seed_knowledge.json"

#: 合并时的分类顺序，保证每次生成的 JSON 顺序稳定（diff 友好）
CATEGORY_ORDER = ["动作要领", "营养饮食", "恢复与伤病", "训练计划", "补剂科普"]


def load_part(path: Path) -> list:
    raw = path.read_text(encoding="utf-8-sig")
    data = json.loads(raw)
    if not isinstance(data, list):
        raise ValueError(f"{path.name} 顶层不是数组")
    return data


def main() -> int:
    parser = argparse.ArgumentParser(description="合并分类种子文件为 seed_knowledge.json")
    parser.add_argument("--dry-run", action="store_true", help="只校验，不写文件")
    args = parser.parse_args()

    if not SEED_PARTS_DIR.exists():
        print(f"❌ 分类目录不存在: {SEED_PARTS_DIR}")
        return 1

    part_files = sorted(SEED_PARTS_DIR.glob("*.json"))
    if not part_files:
        print(f"❌ {SEED_PARTS_DIR} 下没有 JSON 文件")
        return 1

    merged: list = []
    problems: list = []

    for path in part_files:
        try:
            entries = load_part(path)
        except Exception as exc:  # noqa: BLE001 - 校验脚本，报错信息比异常类型重要
            problems.append(f"{path.name}: 解析失败 — {exc}")
            continue

        for index, entry in enumerate(entries):
            if not isinstance(entry, dict):
                problems.append(f"{path.name}[{index}]: 不是对象")
                continue
            missing = {"category", "title", "content", "source"} - set(entry.keys())
            if missing:
                problems.append(f"{path.name}[{index}]: 缺字段 {sorted(missing)}")
                continue
            length = len(str(entry["content"]))
            if not (MIN_CONTENT_CHARS <= length <= MAX_CONTENT_CHARS):
                problems.append(
                    f"{path.name}[{index}]: content 长度 {length} 越界 "
                    f"(title={entry['title']})"
                )
                continue
            merged.append(entry)

        print(f"  {path.name:16} 载入 {len(entries):3} 条 → 合格 {len(merged)} 条（累计）")

    # 按标题去重（保留首次出现）
    seen: dict = {}
    duplicates: list = []
    for entry in merged:
        title = entry["title"]
        if title in seen:
            duplicates.append(title)
            continue
        seen[title] = entry

    if duplicates:
        problems.append(f"跨文件标题重复 {len(duplicates)} 条: {duplicates[:5]}")

    # 按分类顺序整理输出
    ordered: list = []
    for category in CATEGORY_ORDER:
        ordered.extend(e for e in seen.values() if e["category"] == category)
    # 兜底：不在预设顺序里的分类也带上，避免静默丢失
    ordered.extend(e for e in seen.values() if e["category"] not in CATEGORY_ORDER)

    counts: dict = {}
    for entry in ordered:
        counts[entry["category"]] = counts.get(entry["category"], 0) + 1

    print("\n=== 分类统计 ===")
    for category, expected in EXPECTED_CATEGORIES.items():
        actual = counts.get(category, 0)
        flag = "✅" if actual >= expected else "⚠️ "
        print(f"  {flag} {category:8} 实际 {actual:3} / 规范建议 {expected}")
    unknown = set(counts) - set(EXPECTED_CATEGORIES)
    if unknown:
        print(f"  ⚠️  出现规范外的分类: {sorted(unknown)}")

    print(f"\n总条数: {len(ordered)}（规范要求 ≥200）")

    if problems:
        print(f"\n❌ 校验未通过（{len(problems)} 处）：")
        for item in problems[:15]:
            print(f"  - {item}")
        return 1

    if len(ordered) < 200:
        print("❌ 总条数不足 200，规范强制要求 ≥200 条")
        return 1

    if args.dry_run:
        print("\n✅ 校验通过（--dry-run，未写文件）")
        return 0

    SEED_FILE.parent.mkdir(parents=True, exist_ok=True)
    # ensure_ascii=False 保留中文原文；newline='\n' 避免 Windows 写出 CRLF
    SEED_FILE.write_text(
        json.dumps(ordered, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    size_kb = SEED_FILE.stat().st_size / 1024
    print(f"\n✅ 已写入 {SEED_FILE}（{len(ordered)} 条，{size_kb:.1f} KB，UTF-8 无 BOM）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

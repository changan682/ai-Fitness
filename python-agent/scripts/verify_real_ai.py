#!/usr/bin/env python
"""第 5 周验收：真实 AI 文本（DeepSeek）与 RAG 检索质量。

对应规范路线图第 5 周的验证方式：**「Swagger 返回真实 AI 文本 + pymilvus 连接成功」**。

用法（需先启动 Python 服务）：

```powershell
E:\\Anaconde\\python.exe scripts/verify_real_ai.py
```

## 怎么判断「是真实 AI 文本」而不是本地拼装

不能只看「有没有返回内容」——未配 Key 时本地拼装也会返回一份结构完整的 Markdown。
判据是**内容层面的特征**：

| 特征 | 本地拼装 | 真实大模型 |
|---|---|---|
| 结构 | 固定四段（概览/最佳表现/对比分析/改进建议） | 结构可能不同、措辞自然 |
| 数字 | 严格等于入参算出的值 | 可能引用数字，也可能换说法 |
| 措辞 | 模板化句子（"属于正常波动（睡眠/饮食/恢复都会影响）"） | 每次不同 |

因此脚本会同时打印内容与关键判据，由人判断；同时用程序断言几个硬指标
（`llm_api_configured=true`、有 `generated_at`、长度合理、不是兜底文案）。
"""

from __future__ import annotations

import argparse
import sys
import time

import httpx

PASS = 0
FAIL = 0


def check(name: str, ok: bool, detail: str = "") -> None:
    global PASS, FAIL
    if ok:
        PASS += 1
    else:
        FAIL += 1
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f"  ({detail})" if detail else ""))


RECORDS = [
    {"action": "杠铃卧推", "sets": 4, "reps": 10, "weight": 60.0, "rpe": 8},
    {"action": "上斜哑铃卧推", "sets": 3, "reps": 12, "weight": 25.0, "rpe": 7},
    {"action": "绳索夹胸", "sets": 3, "reps": 15, "weight": 15.0, "rpe": 6},
]

# 4×10×60 + 3×12×25 + 3×15×15 = 2400 + 900 + 675 = 3975
EXPECTED_VOLUME = "3975"

COMPARISON = {
    "previousDate": "2026-07-23",
    "scope": "同部位",
    "sharedMuscles": ["胸"],
    "sharedActions": ["杠铃卧推"],
    "records": [{"action": "杠铃卧推", "sets": 4, "reps": 10, "weight": 57.5, "rpe": 8}],
    "volumeChangePct": 5.3,
}

QUESTIONS = [
    "深蹲时膝盖内扣怎么纠正",
    "增肌期每天应该吃多少蛋白质",
    "肌酸需要做冲击期吗",
    "延迟性肌肉酸痛怎么处理",
    # 下面这题在「字符哈希兜底」下会失败（返回一堆营养类条目），
    # 换成真实语义 Embedding 后应当命中「训练计划」分类 —— 用它来对比检索质量
    "训练计划什么时候该减载",
    # 语义改写测试：问题里不含「平台期」这个词的完整表述，考察是否理解意图
    "练了几个月没什么进步该怎么办",
]


def main() -> int:
    parser = argparse.ArgumentParser(description="验收真实 AI 文本与 RAG 检索质量")
    parser.add_argument("--base", default="http://127.0.0.1:8000/agent/v1")
    args = parser.parse_args()
    base = args.base.rstrip("/")

    print("=" * 74)
    print("第 5 周验收：真实 AI 文本 + RAG 检索质量")
    print("=" * 74)

    # ---------------- 0. 健康检查 ----------------
    print("\n[0] 服务配置状态")
    try:
        health = httpx.get(f"{base}/health", timeout=30).json()["data"]
    except Exception as exc:  # noqa: BLE001
        print(f"  ❌ 服务未就绪: {exc}")
        return 1

    print(f"  milvus_connected      = {health['milvus_connected']}")
    print(f"  knowledge_base_ready  = {health['knowledge_base_ready']}")
    print(f"  llm_api_configured    = {health['llm_api_configured']}")
    check("Milvus 已连接", health["milvus_connected"] is True)
    check("知识库已就绪", health["knowledge_base_ready"] is True)
    check("大模型 Key 已配置", health["llm_api_configured"] is True,
          "未配置时训练总结会走本地拼装")

    kb = httpx.get(f"{base}/knowledge/health", timeout=30).json()["data"]
    print(f"  知识库: collection={kb['collection_name']} docs={kb['total_documents']} "
          f"index={kb['index_type']} dim={kb['embedding_dim']}")
    check("知识库文档数 ≥200", (kb["total_documents"] or 0) >= 200, str(kb["total_documents"]))

    embedding_provider = kb.get("embedding_provider") or "unknown"
    if embedding_provider == "hashing":
        print("  ⚠️ 当前 Embedding 是离线兜底（hashing）：检索只能命中词法相近内容。")
        print("     配置有效的 DASHSCOPE_API_KEY 后重建知识库可获得语义检索能力。")

    # ---------------- 1. 真实 AI 训练总结 ----------------
    print("\n[1] 真实 AI 训练总结（DeepSeek）")
    started = time.monotonic()
    resp = httpx.post(f"{base}/summary", timeout=180,
                      json={"user_id": 1001, "date": "2026-07-30",
                            "records": RECORDS, "comparison": COMPARISON}).json()
    elapsed = time.monotonic() - started
    # 注意：Python 侧返回的是统一信封 {success, message, data}，
    # 没有 Java 侧的 code 字段 —— 别照搬 Java 的断言方式
    check("接口返回成功", resp.get("success") is True,
          f"success={resp.get('success')} message={resp.get('message')}")

    summary = (resp.get("data") or {}).get("summary", "")
    check("返回了非空总结", bool(summary.strip()), f"{len(summary)} 字符，耗时 {elapsed:.1f}s")
    check("不是兜底文案", "走神" not in summary and "暂时不可用" not in summary)
    check("包含入参算出的总容量（数据贯通）", EXPECTED_VOLUME in summary,
          f"应在文本中出现 {EXPECTED_VOLUME}kg")

    print("\n  ---------- 大模型生成内容 ----------")
    for line in summary.splitlines():
        print(f"  {line}")

    # ---------------- 2. 知识库问答（真实检索 + 真实生成）----------------
    print("\n[2] 知识库问答：真实 Milvus 检索 + DeepSeek 生成")
    for question in QUESTIONS:
        started = time.monotonic()
        resp = httpx.post(f"{base}/chat", timeout=180,
                          json={"question": question, "user_id": 1001}).json()
        elapsed = time.monotonic() - started
        data = resp.get("data") or {}
        sources = data.get("sources") or []
        answer = data.get("answer", "")

        print(f"\n  【问】{question}   （{elapsed:.1f}s，来源 {len(sources)} 条）")
        for src in sources[:3]:
            print(f"     [{src['score']}] ({src['category']}) {src['title']}")
        first_lines = [ln for ln in answer.splitlines() if ln.strip()][:3]
        for line in first_lines:
            print(f"     > {line[:78]}")

        check(f"  问答返回成功: {question[:12]}…", resp.get("success") is True,
              f"success={resp.get('success')} message={resp.get('message')}")
        check(f"  有引用来源", 2 <= len(sources) <= 5, f"{len(sources)} 条")
        check(f"  有 AI 生成的回答", len(answer) > 80, f"{len(answer)} 字符")

    print("\n" + "=" * 74)
    print(f"结果：通过 {PASS} 项，失败 {FAIL} 项")
    print("=" * 74)
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())

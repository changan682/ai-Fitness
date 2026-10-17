"""阶段一验收：验证「检索不再是强制路径」这个核心假设。

## 这一步在验什么

常规 RAG 是「先检索、再让模型判断资料有没有用」，所以必须靠
``[[KB:USED]]`` / ``[[KB:MISS]]`` 自报标记来补救"检索到无关资料"的问题。
Agent 模式把检索变成**可选动作**，于是"用没用知识库"变成**客观事实**。

因此本脚本只验三件事，且**第一件是最关键的**：

1. **纯数据问题不该检索知识库** —— 模型应返回 ``tool_calls=[]`` / ``knowledge_used=False``。
   这一条成立，才证明"检索不再是强制路径"。
2. **纯知识问题该检索** —— ``tool_calls`` 非空、``knowledge_used=True``、
   ``sources`` 非空且 ``score_type=cosine``。
3. **两种情况的标记都能被调用方看出来** —— 这正是本项目"降级必须可见"的底线。

## 前置条件

- Python 服务已启动，且 **`AGENT_MODE_ENABLED=true`**；
- 已配置 `DEEPSEEK_API_KEY`（Agent 模式需要真实模型判断是否调工具）；
- Milvus 与知识库在线（知识题需要真实检索）。

缺任一条件时脚本会**跳过并说明原因**，不会误报失败。

用法：
    python scripts/verify_agent_stage1.py
    python scripts/verify_agent_stage1.py --base-url http://127.0.0.1:8000
"""

from __future__ import annotations

import argparse
import sys

import httpx

PASS, FAIL, SKIP = "PASS", "FAIL", "SKIP"
_results: list[tuple[str, str, str]] = []


def record(status: str, name: str, detail: str = "") -> None:
    _results.append((status, name, detail))
    icon = {PASS: "✅", FAIL: "❌", SKIP: "⏭️"}[status]
    line = f"{icon} [{status}] {name}"
    if detail:
        line += f"\n        {detail}"
    print(line)


def chat(base_url: str, question: str, **extra) -> dict:
    payload = {"question": question, **extra}
    response = httpx.post(f"{base_url}/agent/v1/chat", json=payload, timeout=120.0)
    response.raise_for_status()
    body = response.json()
    if not body.get("success"):
        raise RuntimeError(f"接口返回失败: {body.get('message')}")
    return body["data"]


def check_data_question(base_url: str) -> None:
    """① 需要用户个人数据的问题 → 不应检索知识库。"""
    data = chat(base_url, "我上周练得怎么样？容量有进步吗？")

    tool_calls = data.get("tool_calls")
    knowledge_used = data.get("knowledge_used")

    if tool_calls is None:
        record(FAIL, "数据题：返回了 Agent 证据字段", "tool_calls 为 null，说明没走 Agent 路径")
        return
    record(PASS, "数据题：返回了 Agent 证据字段", f"tool_calls={len(tool_calls)} 条")

    # 核心断言：不该为了"显得严谨"而检索知识库
    if tool_calls:
        names = [item.get("name") for item in tool_calls]
        record(FAIL, "数据题：不应调用 search_knowledge",
               f"实际调用了 {names} —— 说明模型还在无差别检索")
    else:
        record(PASS, "数据题：未调用任何工具（检索不再是强制路径）")

    if knowledge_used is False:
        record(PASS, "数据题：knowledge_used=False（诚实标记）")
    else:
        record(FAIL, "数据题：knowledge_used 应为 False", f"实际={knowledge_used!r}")

    # 不能让模型编造具体数字
    answer = str(data.get("answer") or "")
    forbidden = ["1850", "18650", "上周练了 4 天", "你的总容量是"]
    hits = [word for word in forbidden if word in answer]
    if hits:
        record(FAIL, "数据题：不应编造个人数据", f"回答里出现了 {hits}")
    else:
        record(PASS, "数据题：未发现编造的个人数字")


def check_knowledge_question(base_url: str) -> None:
    """② 纯知识问题 → 应该检索知识库。"""
    data = chat(base_url, "深蹲时膝盖可以超过脚尖吗？")

    tool_calls = data.get("tool_calls")
    knowledge_used = data.get("knowledge_used")

    if not tool_calls:
        record(FAIL, "知识题：应调用 search_knowledge",
               f"tool_calls={tool_calls!r} —— 模型没有检索，可能提示词约束过强")
        return
    record(PASS, "知识题：调用了 search_knowledge",
           f"参数={tool_calls[0].get('arguments')}")

    if not tool_calls[0].get("ok"):
        record(FAIL, "知识题：工具执行成功", f"error={tool_calls[0].get('error')}")
        return
    record(PASS, "知识题：工具执行成功",
           f"best_score={tool_calls[0].get('best_score')}")

    if knowledge_used is True:
        record(PASS, "知识题：knowledge_used=True")
    else:
        record(FAIL, "知识题：knowledge_used 应为 True", f"实际={knowledge_used!r}")

    sources = data.get("sources") or []
    if sources:
        record(PASS, "知识题：返回了知识来源", f"{len(sources)} 条，首条={sources[0].get('title')}")
        if all(item.get("score_type") == "cosine" for item in sources):
            record(PASS, "知识题：来源分数口径为 cosine（真实余弦）")
        else:
            record(FAIL, "知识题：来源分数口径异常",
                   f"{[item.get('score_type') for item in sources]}")
    else:
        record(FAIL, "知识题：来源为空",
               "knowledge_used=True 但 sources 为空，标记自相矛盾")

    if data.get("data_source") == "milvus":
        record(PASS, "知识题：data_source=milvus")
    else:
        record(FAIL, "知识题：data_source 应为 milvus", f"实际={data.get('data_source')!r}")


def check_no_marker_pollution(base_url: str) -> None:
    """③ 正文不应残留 ``[[KB:USED]]`` 标记（Agent 模式用工具记录代替它）。"""
    data = chat(base_url, "增肌期每天蛋白质吃多少合适？")
    answer = str(data.get("answer") or "")
    if "[[KB:" in answer:
        record(FAIL, "正文不应残留知识库标记", "回答里出现了 [[KB:...]]，会污染用户可见文本")
    else:
        record(PASS, "正文未残留知识库标记")


def main() -> int:
    parser = argparse.ArgumentParser(
        description="阶段一验收：Agent 模式下的工具调用与诚实标记",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--base-url", default="http://127.0.0.1:8000", help="Python 服务地址")
    args = parser.parse_args()
    base_url = args.base_url.rstrip("/")

    print(f"== 阶段一验收 @ {base_url} ==\n")

    # --- 前置检查 ---
    try:
        health = httpx.get(f"{base_url}/agent/v1/health", timeout=10.0).json()
    except Exception as exc:  # noqa: BLE001
        print(f"❌ 连不上 Python 服务：{exc}")
        print("   请先启动：E:\\Anaconde\\python.exe -m uvicorn app.main:app --port 8000")
        return 2
    data = health.get("data") or {}
    llm_ok = bool(data.get("llm_api_configured"))
    kb_ok = bool(data.get("knowledge_base_ready"))
    print(f"   健康检查: llm_api_configured={llm_ok} knowledge_base_ready={kb_ok}\n")

    if not llm_ok:
        record(SKIP, "全部用例", "未配置 DEEPSEEK_API_KEY —— Agent 模式需要真实模型判断是否调工具")
    elif not kb_ok:
        record(SKIP, "知识题用例", "知识库未就绪（Milvus 未连上或文档数为 0）")
        check_data_question(base_url)
    else:
        check_data_question(base_url)
        check_knowledge_question(base_url)
        check_no_marker_pollution(base_url)

    passed = sum(1 for status, _, _ in _results if status == PASS)
    failed = sum(1 for status, _, _ in _results if status == FAIL)
    skipped = sum(1 for status, _, _ in _results if status == SKIP)

    print(f"\n== 结果: {passed} 通过 / {failed} 失败 / {skipped} 跳过 ==")
    if failed:
        print("\n提示：若「数据题不该检索」失败，说明模型仍在无差别检索 —— "
              "检查 AGENT_SYSTEM_PROMPT 里「不要为了显得严谨而检索」那段是否生效。")
        return 1
    if skipped:
        return 0
    return 0


if __name__ == "__main__":
    sys.exit(main())

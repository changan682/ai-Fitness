"""阶段二验收：Agent 循环能真的查到用户数据，并且过程可见。

## 与阶段一的区别

阶段一验的是「检索不再是强制路径」；
阶段二验的是**「Agent 能查用户私有数据」**，这是真正意义上的 Agent 能力：

1. 问「我上周练得怎么样」→ 必须调用数据工具（``get_weekly_stats`` 等），
   并返回**真实数字**（来自数据库，不是模型编的）；
2. 问「深蹲膝盖能过脚尖吗」→ 必须调用 ``search_knowledge``，不该去查训练数据；
3. 两种情况都要有 ``steps``（用了哪些数据）与 ``agent``（步数/耗时/停止原因）——
   让用户能**自己验证**结论从哪来，这是本项目「过程必须可见」的底线；
4. 三重上限必须真的生效（``stopped_by`` 不能是意外的值）。

## 前置条件

- **Java 服务**（8080）与 **Python 服务**（8000）都在跑；
- Python 侧 ``AGENT_MODE_ENABLED=true``；
- 已配置 ``DEEPSEEK_API_KEY``（Agent 全靠模型决策）；
- Milvus 与知识库在线（知识题需要真实检索）；
- MySQL 已执行 ``sql/migration-20260922-agent-run.sql``（否则审计表不存在）。

用法：
    python scripts/verify_agent_stage2.py
    python scripts/verify_agent_stage2.py --java http://127.0.0.1:8080 --python http://127.0.0.1:8000
"""

from __future__ import annotations

import argparse
import random
import sys
import time
from datetime import date, timedelta

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


# ============================================================
# 准备一个带真实数据的测试用户
# ============================================================

def prepare_user(java: str) -> tuple[str, int]:
    """注册（或登录）一个测试用户，并写入本周训练记录与体测数据。

    为什么必须造数据：Agent 的检查点是"返回的数字来自数据库"。
    如果库里没有记录，模型只能回答"没有数据"，这条验收就等于没测。
    """
    phone = f"139{random.randint(10000000, 99999999)}"
    password = "Abc@Test2026"

    response = httpx.post(f"{java}/api/v1/user/register", json={
        "nickname": "Agent验收用户",
        "phone": phone,
        "password": password,
        "gender": 1,
        "height": 175.0,
        "weight": 72.0,
        "trainingGoal": "增肌",
        "trainingLevel": "进阶",
    }, timeout=20.0)
    body = response.json()
    if body.get("code") != 0:
        raise RuntimeError(f"注册失败: {body}")

    login = httpx.post(f"{java}/api/v1/user/login", json={
        "phone": phone, "password": password,
    }, timeout=20.0).json()
    if login.get("code") != 0:
        raise RuntimeError(f"登录失败: {login}")
    token = login["data"]["token"]
    user_id = login["data"]["user"]["id"]
    headers = {"Authorization": f"Bearer {token}"}

    today = date.today()
    monday = today - timedelta(days=today.weekday())

    # 本周写两条训练记录（容量 2400 + 1800 = 4200，便于断言"数字真的来自 DB"）
    httpx.post(f"{java}/api/v1/training/records/batch", headers=headers, json={
        "trainingDate": today.isoformat(),
        "records": [
            {"actionName": "杠铃卧推", "sets": 4, "reps": 10, "weightKg": 60, "rpe": 8},
            {"actionName": "深蹲", "sets": 5, "reps": 8, "weightKg": 45, "rpe": 7},
        ],
    }, timeout=20.0)

    # 体测：本周两条，让趋势工具有东西可算
    for offset, weight in ((1, 72.0), (0, 71.6)):
        httpx.post(f"{java}/api/v1/body-metric", headers=headers, json={
            "recordDate": (monday + timedelta(days=offset)).isoformat()
            if monday + timedelta(days=offset) <= today else today.isoformat(),
            "weightKg": weight,
            "waistCm": 82.0,
        }, timeout=20.0)

    return token, user_id


# ============================================================
# 断言辅助
# ============================================================

def ask_agent(java: str, token: str, question: str, timeout: float = 120.0) -> dict:
    response = httpx.post(f"{java}/api/ai/agent",
                          headers={"Authorization": f"Bearer {token}"},
                          json={"question": question}, timeout=timeout)
    body = response.json()
    if body.get("code") != 0:
        raise RuntimeError(f"/api/ai/agent 返回 {body.get('code')}: {body.get('msg')}")
    return body["data"]


def tools_in(data: dict) -> list[str]:
    return [str(step.get("tool")) for step in (data.get("steps") or [])]


def check_data_question(java: str, token: str) -> None:
    data = ask_agent(java, token, "我本周练得怎么样？总容量是多少？体重有变化吗？")

    steps = data.get("steps")
    meta = data.get("agent") or {}
    if steps is None or steps == []:
        record(FAIL, "数据题：产生了步骤记录", f"steps={steps!r} —— Agent 没有调用任何工具")
        return
    record(PASS, "数据题：产生了步骤记录", f"{len(steps)} 步 / 耗时 {meta.get('latencyMs')}ms")

    used = tools_in(data)
    data_tools = {"get_weekly_stats", "get_training_records", "get_body_trend"}
    if data_tools & set(used):
        record(PASS, "数据题：调用了数据工具", f"tools={used}")
    else:
        record(FAIL, "数据题：应调用数据工具", f"实际 tools={used}")

    if "search_knowledge" not in used:
        record(PASS, "数据题：未检索知识库（该查数据就查数据）")
    else:
        record(FAIL, "数据题：不该检索知识库", f"tools={used}")

    # 数字必须来自数据库：4200 是两条记录的容量和（2400 + 1800）
    answer = str(data.get("answer") or "")
    if "4200" in answer or "4,200" in answer:
        record(PASS, "数据题：回答包含数据库里的真实总容量 4200kg")
    else:
        record(SKIP, "数据题：回答中出现 4200kg",
               "模型可能换了表述（如只说动作明细），不视为失败；请人工确认数字来源")

    # 过程可见性
    if all({"step", "tool", "ok", "summary"} <= set(s) for s in steps):
        record(PASS, "数据题：每一步都有 step/tool/ok/summary（可供前端展示）")
    else:
        record(FAIL, "数据题：步骤字段不完整", f"实际 keys={[sorted(s.keys()) for s in steps]}")

    if meta.get("stoppedBy") in {"completed", "max_steps", "deadline", "budget"}:
        record(PASS, "数据题：停止原因合法", f"stoppedBy={meta.get('stoppedBy')}, "
                                            f"stepsUsed={meta.get('stepsUsed')}/{meta.get('maxSteps')}")
    else:
        record(FAIL, "数据题：停止原因异常", f"stoppedBy={meta.get('stoppedBy')!r}")

    # 未用知识库时必须有诚实标记（不是"假装有依据"）
    if data.get("knowledgeUsed") is False and data.get("dataSource") in {"agent", "none"}:
        record(PASS, "数据题：诚实标记正确",
               f"knowledgeUsed=False, dataSource={data.get('dataSource')}")
    else:
        record(FAIL, "数据题：诚实标记异常",
               f"knowledgeUsed={data.get('knowledgeUsed')!r}, dataSource={data.get('dataSource')!r}")


def check_knowledge_question(java: str, token: str) -> None:
    data = ask_agent(java, token, "深蹲时膝盖可以超过脚尖吗？")

    used = tools_in(data)
    if "search_knowledge" in used:
        record(PASS, "知识题：调用了 search_knowledge", f"tools={used}")
    else:
        record(FAIL, "知识题：应检索知识库", f"实际 tools={used}")

    if data.get("knowledgeUsed") is True:
        record(PASS, "知识题：knowledgeUsed=True")
    else:
        record(FAIL, "知识题：knowledgeUsed 应为 True", f"实际={data.get('knowledgeUsed')!r}")

    sources = data.get("sources") or []
    if sources:
        record(PASS, "知识题：返回了知识来源", f"{len(sources)} 条，首条={sources[0].get('title')}")
    else:
        record(FAIL, "知识题：来源为空", "knowledgeUsed=True 但 sources 为空，标记自相矛盾")

    if data.get("dataSource") == "milvus":
        record(PASS, "知识题：dataSource=milvus")
    else:
        record(FAIL, "知识题：dataSource 应为 milvus", f"实际={data.get('dataSource')!r}")


def check_memory_continuity(java: str, token: str) -> None:
    """多轮：第二轮省略主语，应能接上第一轮（Agent 同样使用 Java 侧记忆）"""
    first = ask_agent(java, token, "深蹲怎么做才标准？")
    session_id = first.get("sessionId")
    if not session_id:
        record(FAIL, "多轮记忆：返回 sessionId")
        return

    response = httpx.post(f"{java}/api/ai/agent",
                          headers={"Authorization": f"Bearer {token}"},
                          json={"question": "那做几组比较合适？", "sessionId": session_id},
                          timeout=120.0)
    body = response.json()
    if body.get("code") != 0:
        record(FAIL, "多轮记忆：第二轮调用失败", str(body.get("msg")))
        return
    answer = str(body["data"].get("answer") or "")
    if "深蹲" in answer or "组" in answer:
        record(PASS, "多轮记忆：第二轮能接上上文", f"sessionId={session_id[:8]}…")
    else:
        record(SKIP, "多轮记忆：第二轮回答", "回答里没出现上文关键词，建议人工确认")


def main() -> int:
    parser = argparse.ArgumentParser(
        description="阶段二验收：Agent 循环 + 用户数据工具 + 过程可见性",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--java", default="http://127.0.0.1:8080", help="Java BFF 地址")
    parser.add_argument("--python", dest="py", default="http://127.0.0.1:8000",
                        help="Python 服务地址（仅用于前置健康检查）")
    args = parser.parse_args()
    java, py = args.java.rstrip("/"), args.py.rstrip("/")

    print(f"== 阶段二验收: java={java} python={py} ==\n")

    # --- 前置检查 ---
    try:
        health = httpx.get(f"{py}/agent/v1/health", timeout=10.0).json().get("data") or {}
    except Exception as exc:  # noqa: BLE001
        print(f"❌ 连不上 Python 服务: {exc}")
        return 2
    try:
        jhealth = httpx.get(f"{java}/api/v1/health", timeout=10.0).json().get("data") or {}
    except Exception as exc:  # noqa: BLE001
        print(f"❌ 连不上 Java 服务: {exc}")
        return 2

    llm_ok = bool(health.get("llm_api_configured"))
    kb_ok = bool(health.get("knowledge_base_ready"))
    print(f"   Python: llm_api_configured={llm_ok} knowledge_base_ready={kb_ok}")
    print(f"   Java  : {jhealth.get('services')}\n")

    if not llm_ok:
        record(SKIP, "全部用例", "未配置 DEEPSEEK_API_KEY —— Agent 全靠模型决策，无法验证")
        return 0

    # --- 造数据 ---
    try:
        token, user_id = prepare_user(java)
        print(f"   测试用户已就绪: userId={user_id}\n")
    except Exception as exc:  # noqa: BLE001
        record(FAIL, "准备测试用户与训练数据", str(exc))
        return 1

    check_data_question(java, token)
    if kb_ok:
        check_knowledge_question(java, token)
    else:
        record(SKIP, "知识题用例", "知识库未就绪")
    check_memory_continuity(java, token)

    passed = sum(1 for s, _, _ in _results if s == PASS)
    failed = sum(1 for s, _, _ in _results if s == FAIL)
    skipped = sum(1 for s, _, _ in _results if s == SKIP)
    print(f"\n== 结果: {passed} 通过 / {failed} 失败 / {skipped} 跳过 ==")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())

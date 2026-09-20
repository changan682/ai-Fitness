"""第 4 周验收脚本：逐个调用 6 个接口并校验协议与 Mock 数据的正确性。

用法（先用 uvicorn 把服务跑起来）：
    E:\\Anaconde\\python.exe scripts/smoke_test.py                    # 默认 http://127.0.0.1:8000
    E:\\Anaconde\\python.exe scripts/smoke_test.py --base-url http://127.0.0.1:8000

脚本会真实断言：
1. /health 返回 success=true，且 data 含 5 个字段；
2. /summary 的 summary 文本里算出的总容量 == 入参 4×10×60 + 3×12×25 + 3×15×15；
3. 每个响应头都有 X-Trace-Id，且传入自定义 X-Trace-Id 时被原样回写；
4. /recommend 返回 count 条，且都属于目标肌群的真实动作；
5. /pose-evaluate 的 score_level 与 score 自洽（优秀>=90/良好70-89/一般50-69/需改进<50）；
6. /chat 返回 2-3 条带 score 的来源 + Markdown 回答；
7. /knowledge/health 字段齐全；
8. 错误路径：question 超长 → 422、无训练记录 → 400、未知路径 → 404，
   且三者都是 {success:false, message, data:null} 信封（不是 FastAPI 默认的 detail 格式）。
"""

from __future__ import annotations

import argparse
import json
import re
import sys

import httpx

# Windows 控制台默认 GBK，输出中文/Emoji 会炸，这里强制 UTF-8
try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:  # pragma: no cover
    pass

BASE = "/agent/v1"
PASSED: list[str] = []
FAILED: list[str] = []


def check(name: str, condition: bool, detail: str = "") -> None:
    if condition:
        PASSED.append(name)
        print(f"  [PASS] {name}" + (f" -> {detail}" if detail else ""))
    else:
        FAILED.append(name)
        print(f"  [FAIL] {name}" + (f" -> {detail}" if detail else ""))


def show(label: str, value) -> None:
    text = value if isinstance(value, str) else json.dumps(value, ensure_ascii=False)
    if len(text) > 300:
        text = text[:300] + f"...(共{len(text)}字)"
    print(f"    {label}: {text}")


def envelope_ok(body: dict) -> bool:
    return set(body.keys()) == {"success", "message", "data"} and body["success"] is True


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8000")
    parser.add_argument("--trace-id", default="20260730-143000-a1b2c3")
    args = parser.parse_args()
    base = args.base_url.rstrip("/")

    print(f"=== 第4周 Python Agent 冒烟测试 @ {base} ===")
    with httpx.Client(base_url=base, timeout=30.0) as client:
        # ---------------- 1. 健康检查 ----------------
        print("\n[1] GET /agent/v1/health")
        r = client.get(f"{BASE}/health", headers={"X-Trace-Id": args.trace_id})
        body = r.json()
        show("HTTP", r.status_code)
        show("X-Trace-Id", r.headers.get("X-Trace-Id"))
        show("data", body.get("data"))
        check("health: HTTP 200", r.status_code == 200, str(r.status_code))
        check("health: 统一信封 success=true", envelope_ok(body), body.get("message"))
        check(
            "health: data 含 5 个约定字段",
            set((body.get("data") or {}).keys())
            == {
                "status",
                "milvus_connected",
                "llm_api_configured",
                "knowledge_base_ready",
                "timestamp",
            },
            str(list((body.get("data") or {}).keys())),
        )
        check(
            "health: X-Trace-Id 原样回写",
            r.headers.get("X-Trace-Id") == args.trace_id,
            str(r.headers.get("X-Trace-Id")),
        )

        # 第 5 周起 DEEPSEEK_API_KEY 配好就是「真实大模型」在写总结，
        # 文案是模型自由发挥的，不能再按本地模板的固定措辞断言（否则误报）。
        # 未配置 key 时走本地确定性拼装，仍然按固定措辞严格校验。
        llm_configured = bool((body.get("data") or {}).get("llm_api_configured"))
        print(f"\n    [模式] llm_api_configured={llm_configured}"
              + ("  → 总结由真实大模型生成，采取宽松校验" if llm_configured
                 else "  → 本地模板拼装，采取严格校验"))

        # ---------------- 2. 训练总结 ----------------
        print("\n[2] POST /agent/v1/summary")
        records = [
            {"action": "杠铃卧推", "sets": 4, "reps": 10, "weight": 60, "rpe": 8},
            {"action": "上斜哑铃卧推", "sets": 3, "reps": 12, "weight": 25, "rpe": 7},
            {"action": "绳索夹胸", "sets": 3, "reps": 15, "weight": 15, "rpe": 6},
        ]
        expected_volume = 4 * 10 * 60 + 3 * 12 * 25 + 3 * 15 * 15
        r = client.post(
            f"{BASE}/summary",
            json={
                "user_id": 1001,
                "date": "2026-07-30",
                "records": records,
                "comparison": {
                    "previousDate": "2026-07-23",
                    "records": [
                        {"action": "杠铃卧推", "sets": 4, "reps": 10, "weight": 55, "rpe": 8},
                        {"action": "上斜哑铃卧推", "sets": 3, "reps": 12, "weight": 25, "rpe": 7},
                    ],
                },
            },
        )
        body = r.json()
        show("HTTP", r.status_code)
        show("X-Trace-Id", r.headers.get("X-Trace-Id"))
        summary_text = (body.get("data") or {}).get("summary", "")
        print("    ---- summary (Markdown) ----")
        for line in summary_text.splitlines():
            print("    | " + line)
        print("    ---------------------------")
        show("data.generated_at", (body.get("data") or {}).get("generated_at"))
        check("summary: HTTP 200", r.status_code == 200, str(r.status_code))
        check("summary: 统一信封 success=true", envelope_ok(body), body.get("message"))
        found = re.search(r"总容量 \*\*([\d.]+)kg\*\*", summary_text)
        # 大模型可能写成「3,975kg」或不用加粗，所以宽松模式只要求数字出现且一致
        loose_volume = re.search(r"3,?975", summary_text)
        check(
            f"summary: 总容量与入参一致 (期望 {expected_volume})",
            (found is not None and float(found.group(1)) == float(expected_volume))
            if not llm_configured
            else loose_volume is not None,
            f"summary 中解析到 {found.group(1) if found else '未匹配'}",
        )
        check(
            "summary: 含 4 个规范段落",
            all(
                section in summary_text
                for section in ("### 📊 训练概览", "### ⭐ 最佳表现", "### 📈 对比分析", "### 💡 改进建议")
            )
            if not llm_configured
            else all(
                label in summary_text
                for label in ("训练概览", "最佳表现", "对比分析", "改进建议")
            )
            and ("###" in summary_text or "**" in summary_text or "|" in summary_text),
            "" if not llm_configured else "宽松模式：只要求 4 个段落语义都在且是 Markdown",
        )
        check(
            "summary: 最佳动作为容量最大的杠铃卧推(2400kg)",
            "**杠铃卧推**" in summary_text and "2400kg" in summary_text
            if not llm_configured
            else "杠铃卧推" in summary_text and re.search(r"2,?400", summary_text) is not None,
        )
        check(
            "summary: generated_at 为 ISO8601",
            bool(re.match(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}", str((body.get("data") or {}).get("generated_at")))),
            str((body.get("data") or {}).get("generated_at")),
        )

        # ---------------- 3. 动作推荐 ----------------
        print("\n[3] POST /agent/v1/recommend")
        r = client.post(
            f"{BASE}/recommend",
            json={"target_muscle": "胸", "equipment": ["哑铃", "杠铃"], "count": 3},
        )
        body = r.json()
        recs = (body.get("data") or {}).get("recommendations", [])
        show("HTTP", r.status_code)
        show("X-Trace-Id", r.headers.get("X-Trace-Id"))
        for item in recs:
            show("  -", f"{item.get('action_name')} | {item.get('focus_area')} | {item.get('recommended_sets')}×{item.get('recommended_reps')} | {item.get('difficulty')} | {item.get('equipment')}")
        check("recommend: HTTP 200", r.status_code == 200, str(r.status_code))
        check("recommend: 统一信封 success=true", envelope_ok(body), body.get("message"))
        check("recommend: count=3 生效", len(recs) == 3, f"实际 {len(recs)} 条")
        check(
            "recommend: 全部为胸肌群动作",
            all(item.get("target_muscle") == "胸" for item in recs),
        )
        check(
            "recommend: 字段齐全",
            all(
                set(item.keys())
                == {
                    "action_name",
                    "target_muscle",
                    "focus_area",
                    "recommended_sets",
                    "recommended_reps",
                    "difficulty",
                    "notes",
                    "equipment",
                }
                for item in recs
            ),
        )

        # ---------------- 4. 姿态评估 ----------------
        print("\n[4] POST /agent/v1/pose-evaluate")
        r = client.post(
            f"{BASE}/pose-evaluate",
            json={"image_base64": "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg==", "action_name": "深蹲"},
        )
        body = r.json()
        data = body.get("data") or {}
        show("HTTP", r.status_code)
        show("X-Trace-Id", r.headers.get("X-Trace-Id"))
        show("data", data)
        score = data.get("score", -1)
        expected_level = (
            "优秀" if score >= 90 else "良好" if score >= 70 else "一般" if score >= 50 else "需改进"
        )
        check("pose: HTTP 200", r.status_code == 200, str(r.status_code))
        check("pose: 统一信封 success=true", envelope_ok(body), body.get("message"))
        check(
            f"pose: score_level 与 score 自洽 (score={score})",
            data.get("score_level") == expected_level,
            f"{data.get('score_level')} vs 期望 {expected_level}",
        )
        check(
            "pose: issues/suggestions/good_points 非空",
            bool(data.get("issues")) and bool(data.get("suggestions")) and bool(data.get("good_points")),
        )

        # ---------------- 5. RAG 问答 ----------------
        print("\n[5] POST /agent/v1/chat")
        r = client.post(
            f"{BASE}/chat",
            json={"question": "深蹲时膝盖到底能不能超过脚尖？", "category": None, "user_id": 1001},
        )
        body = r.json()
        data = body.get("data") or {}
        sources = data.get("sources", [])
        show("HTTP", r.status_code)
        show("X-Trace-Id", r.headers.get("X-Trace-Id"))
        print("    ---- answer (Markdown) ----")
        for line in str(data.get("answer", "")).splitlines():
            print("    | " + line)
        print("    ---------------------------")
        for src in sources:
            show("  source", f"[{src.get('score')}] {src.get('category')} / {src.get('title')}")
        check("chat: HTTP 200", r.status_code == 200, str(r.status_code))
        check("chat: 统一信封 success=true", envelope_ok(body), body.get("message"))
        check("chat: question 回显一致", data.get("question") == "深蹲时膝盖到底能不能超过脚尖？")
        # 第 5 周起 /chat 走真实 Milvus 检索，返回 Top-5（规范第 226 行要求检索 Top-5）。
        # 第 4 周的 Mock 只返回 3 条，故把上界放宽到 5 —— 规范 7.4 的响应示例只画了 3 条，
        # 但「RAG 处理流程」与 Milvus 章节都明确写 Top-5，以强制要求为准。
        check("chat: 来源数量在 2-5 条之间", 2 <= len(sources) <= 5, f"实际 {len(sources)} 条")
        check(
            "chat: 来源字段齐全且分数降序",
            all(set(s.keys()) == {"category", "title", "content", "score"} for s in sources)
            and all(
                sources[i]["score"] >= sources[i + 1]["score"]
                for i in range(len(sources) - 1)
            ),
        )

        # ---------------- 6. 知识库健康 ----------------
        print("\n[6] GET /agent/v1/knowledge/health")
        r = client.get(f"{BASE}/knowledge/health")
        body = r.json()
        data = body.get("data") or {}
        show("HTTP", r.status_code)
        show("X-Trace-Id", r.headers.get("X-Trace-Id"))
        show("data", data)
        check("knowledge/health: HTTP 200", r.status_code == 200, str(r.status_code))
        check("knowledge/health: 统一信封 success=true", envelope_ok(body), body.get("message"))
        check(
            "knowledge/health: 6 个字段齐全",
            set(data.keys())
            == {
                "milvus_connected",
                "collection_name",
                "total_documents",
                "last_updated",
                "index_type",
                "embedding_dim",
            },
            str(list(data.keys())),
        )

        # ---------------- 7. 错误路径 ----------------
        print("\n[7] 错误路径（统一错误信封）")
        r = client.post(f"{BASE}/chat", json={"question": "深" * 501})
        body = r.json()
        show("422 超长 question ->", f"HTTP {r.status_code} {json.dumps(body, ensure_ascii=False)[:200]}")
        check(
            "错误: question 超长 → 422 且是统一信封",
            r.status_code == 422 and body.get("success") is False and body.get("data") is None,
            str(r.status_code),
        )

        r = client.post(
            f"{BASE}/summary", json={"user_id": 1001, "date": "2026-07-30", "records": []}
        )
        body = r.json()
        show("400 空 records ->", f"HTTP {r.status_code} {json.dumps(body, ensure_ascii=False)[:200]}")
        check(
            "错误: 无训练记录 → 400 且是统一信封",
            r.status_code == 400 and body.get("success") is False and body.get("data") is None,
            str(r.status_code),
        )

        r = client.post(f"{BASE}/recommend", json={"target_muscle": "外星肌", "equipment": ["哑铃"]})
        body = r.json()
        show("400 未知肌群 ->", f"HTTP {r.status_code} {json.dumps(body, ensure_ascii=False)[:200]}")
        check(
            "错误: 未识别肌群 → 400 且是统一信封",
            r.status_code == 400 and body.get("success") is False,
            str(r.status_code),
        )

        r = client.get("/agent/v1/not-exist")
        body = r.json()
        show("404 未知路径 ->", f"HTTP {r.status_code} {json.dumps(body, ensure_ascii=False)[:200]}")
        check(
            "错误: 未知路径 → 404 且是统一信封（非 FastAPI 默认 detail）",
            r.status_code == 404 and body.get("success") is False and "detail" not in body,
            str(r.status_code),
        )

    print("\n=== 结果 ===")
    print(f"通过 {len(PASSED)} 项，失败 {len(FAILED)} 项")
    if FAILED:
        for name in FAILED:
            print(f"  失败: {name}")
        return 1
    print("全部通过 ✅")
    return 0


if __name__ == "__main__":
    sys.exit(main())

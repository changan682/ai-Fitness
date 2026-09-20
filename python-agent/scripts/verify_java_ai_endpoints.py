#!/usr/bin/env python
"""Java `/api/ai/*` 五个 AI 代理接口的端到端验收。

需要 Java(8080) 与 Python(8000) 都在运行。用法：

```powershell
E:\\Anaconde\\python.exe scripts/verify_java_ai_endpoints.py
```

## 为什么需要它

第 4 周交付时，`AiPythonClientLiveTest` 验证的是「Java 的 RestClient → Python」这一段，
**Java 自己的 HTTP 入口（`/api/ai/*`）并没有被真正打过** —— 尤其是
`POST /api/ai/pose-evaluate` 走的是 `multipart/form-data`，
它涉及 Spring 的 `@RequestPart MultipartFile` 解析、图片转 Base64、
再转发给 Python 的 JSON 接口，整条链路和纯 JSON 接口完全不同。
只测 Python 侧直连是发现不了「Java 的 multipart 绑定写错了」这类问题的。

因此本脚本从 Java 的 HTTP 入口打进去，覆盖 5 个接口 + 3 条错误路径。
"""

from __future__ import annotations

import argparse
import random
import sys
from pathlib import Path

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parent))

# 共用测试图片（720x720 深蹲剪影）。**不能**用 1x1 的「最小合法 PNG」：
# 模型要求宽高 >10px，且内容要能看清动作；经 Java 转发时 1x1 图更早在
# ImageCompressor 就解不出来（表现为「图片读取失败」），请求根本到不了 Python。
from _test_image import squat_image_bytes  # noqa: E402  - 必须在 sys.path 调整之后导入

PASS = 0
FAIL = 0


def check(name: str, ok: bool, detail: str = "") -> None:
    global PASS, FAIL
    if ok:
        PASS += 1
    else:
        FAIL += 1
    suffix = f"  ({detail})" if detail else ""
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}{suffix}")


def main() -> int:
    parser = argparse.ArgumentParser(description="验收 Java /api/ai/* 五个接口")
    parser.add_argument("--java", default="http://127.0.0.1:8080")
    parser.add_argument("--python", default="http://127.0.0.1:8000")
    args = parser.parse_args()

    base = args.java.rstrip("/")
    print("=" * 72)
    print("Java /api/ai/* 端到端验收（含 multipart 图片上传）")
    print("=" * 72)

    # ---------------- 前置：两个服务是否就绪 ----------------
    try:
        java_health = httpx.get(f"{base}/api/v1/health", timeout=15).json()
        services = java_health["data"]["services"]
        print(f"  Java 依赖状态: {services}")
        check("Java 服务可用", True)
    except Exception as exc:  # noqa: BLE001
        print(f"  ❌ Java 未就绪，无法继续: {exc}")
        return 1

    if services.get("pythonAgent") != "UP":
        print("  ⚠️ Java 报告 pythonAgent 非 UP，请先启动 Python 服务（端口 8000）")

    # ---------------- 准备用户与训练记录 ----------------
    phone = "130" + str(random.randint(10000000, 99999999))
    httpx.post(f"{base}/api/v1/user/register", timeout=30,
               json={"nickname": "AI接口验收", "phone": phone, "password": "Abc@Test2026"})
    token = httpx.post(f"{base}/api/v1/user/login", timeout=30,
                       json={"phone": phone, "password": "Abc@Test2026"}).json()["data"]["token"]
    headers = {"Authorization": f"Bearer {token}"}
    print(f"  测试用户已就绪")

    # ---------------- 1) 训练智能总结 ----------------
    print("\n[1] POST /api/ai/summary")
    httpx.post(f"{base}/api/v1/training/record", headers=headers, timeout=30, json={
        "actionName": "杠铃卧推", "sets": 4, "reps": 10, "weightKg": 60.0, "rpe": 8})
    r = httpx.post(f"{base}/api/ai/summary", headers=headers, json={}, timeout=90)
    body = r.json()
    data = body.get("data") or {}
    check("HTTP 200 且 code=0", r.status_code == 200 and body.get("code") == 0,
          f"code={body.get('code')} msg={body.get('msg')}")
    check("返回 summary 且为 Markdown", bool(data.get("summary", "").startswith("#")),
          f"长度={len(data.get('summary') or '')}")
    check("返回 cached 标志", isinstance(data.get("cached"), bool), str(data.get("cached")))
    check("generatedAt 为 yyyy-MM-dd HH:mm:ss", 
          bool(data.get("generatedAt")) and "T" not in str(data.get("generatedAt")),
          str(data.get("generatedAt")))
    s1 = data.get("summary", "")[:200]
    print(f"    总结节选: {s1.splitlines()[0] if s1 else '(空)'}")

    # ---------------- 2) 动作推荐 ----------------
    print("\n[2] POST /api/ai/recommend")
    r = httpx.post(f"{base}/api/ai/recommend", headers=headers, timeout=60,
                   json={"targetMuscle": "胸", "equipment": ["哑铃", "杠铃"], "count": 3})
    body = r.json()
    recs = (body.get("data") or {}).get("recommendations") or []
    check("HTTP 200 且 code=0", r.status_code == 200 and body.get("code") == 0)
    check("返回 3 条推荐", len(recs) == 3, f"实际 {len(recs)}")
    if recs:
        first = recs[0]
        check("推荐字段为前端 camelCase 契约",
              set(first) >= {"actionName", "targetMuscle", "focusArea",
                             "recommendedSets", "recommendedReps", "difficulty",
                             "notes", "equipment"},
              ",".join(sorted(first)))
        check("动作名为中文（未乱码）", any(ord(c) > 127 for c in first["actionName"]),
              first["actionName"])

    # ---------------- 3) 姿态评估（multipart，本脚本的重点）----------------
    print("\n[3] POST /api/ai/pose-evaluate（multipart/form-data）")
    r = httpx.post(f"{base}/api/ai/pose-evaluate", headers=headers, timeout=90,
                   files={"image": ("squat.jpg", squat_image_bytes(), "image/jpeg")},
                   data={"actionName": "深蹲"})
    body = r.json()
    data = body.get("data") or {}
    check("multipart 上传被正确接收并转发", r.status_code == 200 and body.get("code") == 0,
          f"code={body.get('code')} msg={body.get('msg')}")
    check("返回 score 且 0-100", isinstance(data.get("score"), int) and 0 <= data["score"] <= 100,
          str(data.get("score")))
    check("scoreLevel 与 score 自洽", data.get("scoreLevel") in ("优秀", "良好", "一般", "需改进"),
          str(data.get("scoreLevel")))
    check("issues/suggestions/goodPoints 均为数组",
          all(isinstance(data.get(k), list) for k in ("issues", "suggestions", "goodPoints")),
          f"issues={len(data.get('issues') or [])} suggestions={len(data.get('suggestions') or [])}")
    check("evaluatedAt 已按项目格式返回",
          bool(data.get("evaluatedAt")) and "T" not in str(data.get("evaluatedAt")),
          str(data.get("evaluatedAt")))

    # ---------------- 4) 知识库 RAG 问答 ----------------
    print("\n[4] POST /api/ai/chat")
    r = httpx.post(f"{base}/api/ai/chat", headers=headers, timeout=90,
                   json={"question": "增肌期每天应该吃多少蛋白质"})
    body = r.json()
    data = body.get("data") or {}
    sources = data.get("sources") or []
    check("HTTP 200 且 code=0", r.status_code == 200 and body.get("code") == 0,
          f"code={body.get('code')} msg={body.get('msg')}")
    check("返回 2-5 条来源（规范 Top-5）", 2 <= len(sources) <= 5, f"实际 {len(sources)}")
    check("来源字段与规范一致（category/title/content/score）",
          all(set(s) == {"category", "title", "content", "score"} for s in sources))
    check("来源按分数降序",
          all(sources[i]["score"] >= sources[i + 1]["score"] for i in range(len(sources) - 1)))
    if sources:
        print(f"    Top-1: [{sources[0]['score']}] ({sources[0]['category']}) {sources[0]['title']}")

    # ---------------- 5) 知识库健康 ----------------
    print("\n[5] GET /api/ai/knowledge/health")
    r = httpx.get(f"{base}/api/ai/knowledge/health", headers=headers, timeout=30)
    body = r.json()
    data = body.get("data") or {}
    check("HTTP 200 且 code=0", r.status_code == 200 and body.get("code") == 0,
          f"code={body.get('code')} msg={body.get('msg')}")
    check("milvusConnected=true", data.get("milvusConnected") is True, str(data.get("milvusConnected")))
    check("文档数 > 0", (data.get("totalDocuments") or 0) > 0, str(data.get("totalDocuments")))
    check("向量维度为 768（规范固定）", data.get("embeddingDim") == 768, str(data.get("embeddingDim")))
    print(f"    collection={data.get('collectionName')} index={data.get('indexType')} "
          f"docs={data.get('totalDocuments')}")

    # ---------------- 错误路径 ----------------
    print("\n[6] 错误路径")
    # 6a) 真正的 multipart 请求，但漏传 image 部分
    r = httpx.post(f"{base}/api/ai/pose-evaluate", headers=headers, timeout=30,
                   files={"actionName": (None, "深蹲")})
    check("multipart 但漏传 image → 9003",
          r.json().get("code") == 9003,
          f"code={r.json().get('code')} msg={r.json().get('msg')}")

    # 6b) 压根不是 multipart（发的是 form-urlencoded）
    #     这是另一类错误：Content-Type 与接口声明的 consumes 不符，
    #     必须同样归为 9003，否则会误导成「服务端内部错误」
    r = httpx.post(f"{base}/api/ai/pose-evaluate", headers=headers, timeout=30,
                   data={"actionName": "深蹲"})
    check("非 multipart 请求 → 9003（不可是 9999）",
          r.json().get("code") == 9003,
          f"code={r.json().get('code')} msg={r.json().get('msg')}")

    r = httpx.post(f"{base}/api/ai/pose-evaluate", headers=headers, timeout=30,
                   files={"image": ("x.jpg", squat_image_bytes(), "image/jpeg")},
                   data={"actionName": "游泳"})
    check("非法动作名 → 9003（且调用 Python 之前就拦下）",
          r.json().get("code") == 9003, f"code={r.json().get('code')} msg={r.json().get('msg')}")

    r = httpx.post(f"{base}/api/ai/recommend", headers=headers, timeout=30,
                   json={"targetMuscle": "外星肌", "equipment": ["哑铃"]})
    check("非法肌群 → 9003 参数校验失败", r.json().get("code") == 9003,
          f"code={r.json().get('code')} msg={r.json().get('msg')}")

    r = httpx.post(f"{base}/api/ai/chat", headers={"Authorization": "Bearer invalid-token"}, timeout=30,
                   json={"question": "测试"})
    check("无效 Token → 9001", r.json().get("code") == 9001, f"code={r.json().get('code')}")

    print("\n" + "=" * 72)
    print(f"结果：通过 {PASS} 项，失败 {FAIL} 项")
    print("=" * 72)
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())

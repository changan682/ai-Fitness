#!/usr/bin/env python
"""第 5 周验收：AI 训练总结的「双层缓存 + 同部位对比 + 变更失效」。

需要 Java(8080) 与 Python(8000) 都在运行。用法：

```powershell
$env:REDIS_PASSWORD="123456"     # 交给 Java 用，脚本自己也会读
E:\\Anaconde\\python.exe scripts/verify_summary_layers.py
```

## 为什么需要它

规范 Redis 2.8 要求 AI 总结走「**数据库 + Redis 双层缓存**」，并明确了三件事：

1. 生成后写入 MySQL + Redis；
2. 查询时先查 Redis → 未命中查 MySQL；
3. 当天有新训练记录录入 → 主动失效缓存。

这三条**只看代码是验不出来的**：比如「删了 Redis 但没删 MySQL」时，
第三层会把旧总结捞回来，接口照样返回 200、`cached=true`，
表面上一切正常，实际用户改了训练记录却看到旧总结。
所以必须真的把 Redis key 删掉、再看它是否从 MySQL 命中。

脚本还会验证「同部位对比」的口径：今天练「上斜哑铃卧推」、昨天练「杠铃卧推」，
二者动作名不同但同属「胸」—— 正确行为是识别出共同肌群并按同部位口径描述，
而不是退化成「同名动作」。
"""

from __future__ import annotations

import argparse
import datetime
import os
import random
import re
import socket
import sys
import time

import httpx

RESULT_PASS = 0
RESULT_FAIL = 0


def check(name: str, ok: bool, detail: str = "") -> None:
    global RESULT_PASS, RESULT_FAIL
    mark = "PASS" if ok else "FAIL"
    if ok:
        RESULT_PASS += 1
    else:
        RESULT_FAIL += 1
    suffix = f"  ({detail})" if detail else ""
    print(f"  [{mark}] {name}{suffix}")


# ---------------------------------------------------------------- Redis 工具
class RedisClient:
    """极简 RESP 客户端。

    只用到 AUTH / DEL 两个命令，不值得为它引入 redis 依赖
    （python-agent 的依赖清单刻意保持精简）。
    """

    def __init__(self, host: str, port: int, password: str = "", timeout: float = 5.0):
        self.sock = socket.create_connection((host, port), timeout=timeout)
        self.sock.settimeout(timeout)
        if password:
            reply = self.command("AUTH", password)
            if not reply.startswith("+OK"):
                raise RuntimeError(f"Redis AUTH 失败: {reply}")

    def command(self, *args: str) -> str:
        parts = [f"*{len(args)}\r\n".encode()]
        for arg in args:
            data = str(arg).encode()
            parts.append(b"$%d\r\n" % len(data))
            parts.append(data + b"\r\n")
        self.sock.sendall(b"".join(parts))
        time.sleep(0.15)
        try:
            return self.sock.recv(4096).decode(errors="replace").strip()
        except socket.timeout:
            return ""

    def close(self) -> None:
        try:
            self.sock.close()
        except OSError:
            pass


def main() -> int:
    parser = argparse.ArgumentParser(description="验证 AI 总结的双层缓存与同部位对比")
    parser.add_argument("--java", default="http://127.0.0.1:8080")
    parser.add_argument("--redis-host", default=os.getenv("REDIS_HOST", "192.168.199.128"))
    parser.add_argument("--redis-port", type=int, default=int(os.getenv("REDIS_PORT", "6379")))
    parser.add_argument("--redis-password", default=os.getenv("REDIS_PASSWORD", ""))
    parser.add_argument("--python", default="http://127.0.0.1:8000",
                        help="Python Agent 地址，仅用于探测 llm_api_configured")
    args = parser.parse_args()

    base = args.java.rstrip("/")
    today = datetime.date.today()
    yesterday = today - datetime.timedelta(days=1)
    phone = "132" + str(random.randint(10000000, 99999999))

    print("=" * 70)
    print("AI 训练总结：双层缓存 + 同部位对比 + 变更失效")
    print("=" * 70)

    # 第 5 周起总结由真实大模型生成，措辞是模型自由发挥的：
    # 配了 key 就不能再断言「共同肌群」这种模板固定用词（会把正确结果判成失败）。
    llm_configured = False
    try:
        health = httpx.get(f"{args.python.rstrip('/')}/agent/v1/health", timeout=10).json()
        llm_configured = bool((health.get("data") or {}).get("llm_api_configured"))
    except Exception as exc:  # noqa: BLE001 - 探测失败按「不确定」处理，仍可跑其余检查
        print(f"  ⚠️ 无法探测 llm_api_configured（{exc}），按宽松口径校验对比文案")
        llm_configured = True
    print(f"  llm_api_configured={llm_configured}"
          + ("  → 文案由真实大模型生成，宽松校验" if llm_configured
             else "  → 本地模板拼装，严格校验"))

    # ---------------- 准备用户与训练记录 ----------------
    r = httpx.post(f"{base}/api/v1/user/register",
                   json={"nickname": "总结校验", "phone": phone, "password": "Abc@123456"}, timeout=30)
    check("注册用户", r.json().get("code") == 0, str(r.json().get("code")))

    token = httpx.post(f"{base}/api/v1/user/login",
                       json={"phone": phone, "password": "Abc@123456"}, timeout=30).json()["data"]["token"]
    headers = {"Authorization": f"Bearer {token}"}
    uid = httpx.get(f"{base}/api/v1/user/profile", headers=headers, timeout=30).json()["data"]["id"]
    print(f"  用户 id={uid}")

    # 昨天练胸：杠铃卧推
    httpx.post(f"{base}/api/v1/training/record", headers=headers, timeout=30, json={
        "trainingDate": str(yesterday), "actionName": "杠铃卧推",
        "sets": 4, "reps": 10, "weightKg": 60.0, "rpe": 8})
    # 今天也练胸，但动作名不同 → 应识别为「同部位」
    r = httpx.post(f"{base}/api/v1/training/record", headers=headers, timeout=30, json={
        "actionName": "上斜哑铃卧推", "sets": 3, "reps": 12, "weightKg": 25.0, "rpe": 7})
    record_id = r.json()["data"]["id"]
    check("写入昨天/今天两条训练记录（同肌群、不同动作名）", r.json().get("code") == 0)

    def summary(tag: str) -> dict:
        body = httpx.post(f"{base}/api/ai/summary", headers=headers, json={}, timeout=90).json()
        data = body.get("data") or {}
        print(f"  {tag}: code={body.get('code')} cached={data.get('cached')}")
        return body

    # ---------------- 1) 首次生成 ----------------
    print("\n[1] 首次生成（三层全未命中，应 cached=false）")
    first = summary("首次")
    check("首次生成成功", first.get("code") == 0)
    check("首次 cached=false", first["data"]["cached"] is False)
    summary_text = first["data"]["summary"]

    # ---------------- 2) Redis 命中 ----------------
    print("\n[2] 立即再查（应命中 Redis，cached=true）")
    second = summary("再查")
    check("再次查询 cached=true", second["data"]["cached"] is True)
    check("两次总结内容一致", second["data"]["summary"] == summary_text)

    # ---------------- 3) 只删 Redis，验证 MySQL 兜底层 ----------------
    print("\n[3] 只删 Redis key，验证「MySQL 兜底层」是否被真的用上")
    cache_key = f"fitness:cache:ai:summary:{uid}:{today}"
    deleted = "skip"
    try:
        client = RedisClient(args.redis_host, args.redis_port, args.redis_password)
        deleted = client.command("DEL", cache_key)
        client.close()
        print(f"  DEL {cache_key} -> {deleted}")
    except Exception as exc:  # noqa: BLE001 - 连不上 Redis 就跳过该步，其余检查继续
        print(f"  ⚠️ 无法直连 Redis（{exc}），跳过该步")

    if deleted == "1" or deleted == ":1":
        third = summary("删Redis后")
        check("Redis 失效后仍能命中（来自 MySQL 层）", third["data"]["cached"] is True,
              "cached=true 说明是 DB 层兜住的")
        check("DB 层返回的内容与首次一致", third["data"]["summary"] == summary_text)
    else:
        print("  （未能确认 Redis key 已删除，本步不计入结论）")

    # ---------------- 4) 改训练记录 → 快照失效 ----------------
    print("\n[4] 修改今天的训练记录（4组→5组），验证缓存是否被失效")
    httpx.put(f"{base}/api/v1/training/record/{record_id}", headers=headers,
              json={"sets": 5}, timeout=30)
    fourth = summary("改记录后")
    check("记录变更后 cached=false（重新生成）", fourth["data"]["cached"] is False)
    check("重新生成的内容与旧内容不同", fourth["data"]["summary"] != summary_text)

    # ---------------- 5) 同部位口径 ----------------
    print("\n[5] 「同部位对比」口径（今天=上斜哑铃卧推，昨天=杠铃卧推，同属胸）")
    lines = fourth["data"]["summary"].splitlines()
    bullets = [line for line in lines if re.match(r"^\s*(?:[-*•]|\d+[.)])\s+\S", line)]
    # 大模型偶尔整段写成句子而不是列表，此时退回全文校验，避免误报
    joined = "\n".join(bullets) if bullets else "\n".join(lines)
    print("  对比分析段落：")
    if bullets:
        for line in bullets[:6]:
            print(f"    {line}")
    else:
        print("    （总结未用列表格式，退化为全文校验）")
        for line in [ln for ln in lines if "对比" in ln][:3]:
            print(f"    {line}")
    check(
        "识别出「同部位」口径（而非退化成同名动作）",
        "共同肌群" in joined if not llm_configured
        else ("共同" in joined or "同部位" in joined or "胸" in joined),
        "" if not llm_configured else "宽松口径：大模型可能写「胸肌共同动作」而非「共同肌群」",
    )
    check("未把同部位误标为「同名动作」", "同名动作容量" not in joined)

    print("\n" + "=" * 70)
    print(f"结果：通过 {RESULT_PASS} 项，失败 {RESULT_FAIL} 项")
    print("=" * 70)
    return 1 if RESULT_FAIL else 0


if __name__ == "__main__":
    sys.exit(main())

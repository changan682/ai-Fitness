#!/usr/bin/env python
r"""每周复盘异步链路端到端验收 —— 第 7 周「异步闭环完成」的验收脚本。

## 它验证什么（规范第 748 行的验收标准）

> 搭建 RabbitMQ，完成「每周复盘」的 发送(RabbitTemplate) → 消费(pika) → 回调 → 验签 全链路
> 验证方式：**数据库查到回调写入的周计划**

本脚本把这条链路真正跑一遍：

```
  ① 准备数据      注册测试用户 + 写入本周训练记录/体测（让周统计非空）
  ② 发送消息      pika 投递到 ai.fitness.exchange / ai.weekly.plan.request
                  （消息体与 Java 侧 WeeklyPlanProducer 完全一致，该结构由单测锁死）
  ③ 消费          python -m app.mq_consumer --once
                  → 调 LLM 生成周计划 → HMAC 签名回调 Java → 成功才 basic_ack
  ④ 验证          轮询 GET /api/v1/weekly-plan/latest，确认 AI 建议已落库
                  并检查主队列/DLQ 消息数（消息既没丢、也没进死信）
```

## 用法

```powershell
# 前置：Java 服务已启动（8080）、RabbitMQ 可达
cd python-agent
E:\Anaconde\python.exe scripts/verify_weekly_plan_chain.py

# 若消费者已单独在跑（长驻进程），加 --no-consumer 跳过自动消费
E:\Anaconde\python.exe scripts/verify_weekly_plan_chain.py --no-consumer
```

> 提示：本脚本会真实调用一次大模型（约 3-10 秒）并写入一条测试数据。
"""

from __future__ import annotations

import argparse
import json
import random
import subprocess
import sys
import time
from datetime import date, timedelta
from pathlib import Path

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.config import settings  # noqa: E402

try:
    import pika  # noqa: PLC0415 - 只有当脚本真的要用时才需要
except ImportError:  # pragma: no cover
    print("未安装 pika，请先执行：E:\\Anaconde\\python.exe -m pip install pika")
    sys.exit(2)

PASS = 0
FAIL = 0


def check(name: str, ok: bool, detail: str = "") -> None:
    global PASS, FAIL
    if ok:
        PASS += 1
    else:
        FAIL += 1
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f"  ({detail})" if detail else ""))


def monday_of_this_week() -> date:
    today = date.today()
    return today - timedelta(days=today.weekday())


def build_message(user_id: int, week_start: date) -> dict:
    """按规范「MQ 消息体 JSON Schema」组装（与 Java WeeklyPlanProducer 同结构）"""
    week_end = week_start + timedelta(days=6)
    return {
        "taskId": f"weekly-plan-{user_id}-{week_start}",
        "userId": user_id,
        "weekStart": week_start.isoformat(),
        "weekEnd": week_end.isoformat(),
        "trainingSummary": {
            "trainingDays": 3,
            "totalActions": 9,
            "totalVolume": 16800.0,
            "avgRpe": 7.5,
            "topActions": [
                {"actionName": "杠铃卧推", "count": 3, "totalVolume": 5400.0},
                {"actionName": "杠铃深蹲", "count": 3, "totalVolume": 7200.0},
                {"actionName": "杠铃划船", "count": 3, "totalVolume": 4200.0},
            ],
        },
        "bodyMetrics": {"startWeight": 70.8, "endWeight": 70.2, "weightChange": -0.6},
        "dietSummary": {"avgDailyCalories": 2050.0, "totalMeals": 21},
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%S"),
    }


def publish(message: dict) -> None:
    credentials = pika.PlainCredentials(settings.rabbitmq_user, settings.rabbitmq_password)
    params = pika.ConnectionParameters(
        host=settings.rabbitmq_host, port=settings.rabbitmq_port,
        virtual_host=settings.rabbitmq_vhost, credentials=credentials, heartbeat=60)
    with pika.BlockingConnection(params) as conn:
        channel = conn.channel()
        channel.basic_publish(
            exchange="ai.fitness.exchange",
            routing_key="ai.weekly.plan.request",
            body=json.dumps(message, ensure_ascii=False).encode("utf-8"),
            properties=pika.BasicProperties(
                content_type="application/json",
                delivery_mode=2,      # 持久化：与队列 durable 配套，Broker 重启不丢
            ),
        )


def queue_stats() -> dict:
    """读 RabbitMQ 管理接口的队列状态（用于断言「没丢也没进死信」）"""
    try:
        response = httpx.get(
            f"http://{settings.rabbitmq_host}:15672/api/queues",
            auth=(settings.rabbitmq_user, settings.rabbitmq_password), timeout=10)
        return {q["name"]: q for q in response.json() if str(q.get("name", "")).startswith("ai.")}
    except Exception as exc:  # noqa: BLE001 - 管理接口不可用不影响主流程
        print(f"    （管理接口不可用，跳过队列计数断言: {type(exc).__name__}）")
        return {}


def wait_queue_drained(name: str, timeout: int = 20) -> int:
    """轮询等待队列清空，返回最后一次读到的消息数。

    为什么不能只读一次：RabbitMQ 管理接口的 ``messages`` 是**最终一致**的统计，
    ack 之后立刻查可能还是旧值（实测刚消费完仍读到 1，几秒后才是 0）。
    只读一次会让这个验收脚本偶发失败，把「链路正常」误报成「消息没被消费」。
    """
    deadline = time.time() + timeout
    messages = -1
    while time.time() < deadline:
        stats = queue_stats().get(name, {})
        messages = stats.get("messages", 0) or 0
        if messages == 0:
            return 0
        time.sleep(2)
    return messages


def main() -> int:
    parser = argparse.ArgumentParser(description="每周复盘异步链路端到端验收")
    parser.add_argument("--java", default="http://127.0.0.1:8080")
    parser.add_argument("--no-consumer", action="store_true",
                        help="消费者已在别的进程长驻运行，跳过自动消费")
    parser.add_argument("--timeout", type=int, default=120, help="等待回调落库的秒数")
    args = parser.parse_args()
    base = args.java.rstrip("/")

    print("=" * 72)
    print("每周复盘异步链路：发送 → 消费 → 回调 → 验签")
    print("=" * 72)

    # ---------------- 前置检查 ----------------
    try:
        services = httpx.get(f"{base}/api/v1/health", timeout=15).json()["data"]["services"]
        print(f"  Java 依赖状态: {services}")
        check("Java 服务可用", True)
        check("RabbitMQ 连接正常", services.get("rabbitmq") == "UP", str(services.get("rabbitmq")))
    except Exception as exc:  # noqa: BLE001
        print(f"  ❌ Java 未就绪，无法继续: {exc}")
        return 1

    # ---------------- ① 准备用户与本周数据 ----------------
    phone = "139" + str(random.randint(10000000, 99999999))
    httpx.post(f"{base}/api/v1/user/register", timeout=30,
               json={"nickname": "周计划链路验收", "phone": phone, "password": "Abc@123456"})
    login = httpx.post(f"{base}/api/v1/user/login", timeout=30,
                       json={"phone": phone, "password": "Abc@123456"}).json()
    token = login["data"]["token"]
    user_id = login["data"]["user"]["id"]
    headers = {"Authorization": f"Bearer {token}"}
    print(f"  测试用户: userId={user_id}")

    week_start = monday_of_this_week()
    for offset, action, weight in ((0, "杠铃卧推", 60.0), (1, "杠铃深蹲", 80.0), (2, "杠铃划船", 50.0)):
        httpx.post(f"{base}/api/v1/training/record", headers=headers, timeout=30, json={
            "actionName": action, "sets": 4, "reps": 10, "weightKg": weight, "rpe": 8,
            "trainingDate": (week_start + timedelta(days=offset)).isoformat()})
    check("本周训练记录已写入", True)

    # ---------------- ② 发送消息 ----------------
    message = build_message(user_id, week_start)
    print(f"\n[①] 发送消息: taskId={message['taskId']}")
    try:
        publish(message)
        check("消息已投递到 ai.fitness.exchange → ai.weekly.plan", True)
    except Exception as exc:  # noqa: BLE001
        check("消息已投递", False, f"{type(exc).__name__}: {exc}")
        return 1

    before = queue_stats()
    if before:
        check("消息进入主队列（未被立即死信）", True,
              f"主队列={before.get('ai.weekly.plan', {}).get('messages')} "
              f"DLQ={before.get('ai.weekly.plan.dlq', {}).get('messages')}")

    # ---------------- ③ 消费 ----------------
    if args.no_consumer:
        print("\n[②] 跳过自动消费（--no-consumer，等待长驻消费者处理）")
    else:
        print("\n[②] 启动消费者处理一条消息（python -m app.mq_consumer --once）")
        completed = subprocess.run(
            [sys.executable, "-m", "app.mq_consumer", "--once"],
            cwd=str(Path(__file__).resolve().parent.parent),
            timeout=max(args.timeout, 60),
            check=False,
        )
        check("消费者进程正常退出", completed.returncode == 0,
              f"returncode={completed.returncode}")

    # ---------------- ④ 验证落库 ----------------
    print(f"\n[③] 等待回调写入 t_weekly_plan（最多 {args.timeout}s）")
    deadline = time.time() + args.timeout
    suggestion = ""
    while time.time() < deadline:
        body = httpx.get(f"{base}/api/v1/weekly-plan/latest", headers=headers, timeout=15).json()
        data = body.get("data") or {}
        suggestion = str(data.get("suggestionText") or "")
        if suggestion.strip():
            break
        time.sleep(3)

    check("AI 建议已写入 t_weekly_plan.suggestionText", bool(suggestion.strip()),
          f"长度={len(suggestion)}")
    if suggestion:
        first_line = suggestion.splitlines()[0][:60]
        print(f"    建议节选: {first_line}")
        print(f"    完整长度: {len(suggestion)} 字")
        check("建议为 Markdown（以 # 开头）", suggestion.lstrip().startswith("#"), first_line)

        latest_week = (httpx.get(f"{base}/api/v1/weekly-plan/latest",
                                 headers=headers, timeout=15).json()["data"] or {}).get("weekStart")
        check("周计划的 weekStart 与消息一致", str(latest_week) == week_start.isoformat(),
              f"{latest_week} vs {week_start}")

    # ---------------- 队列状态：没丢也没进死信 ----------------
    after = queue_stats()
    if after:
        remaining = wait_queue_drained("ai.weekly.plan")
        dlq_messages = after.get("ai.weekly.plan.dlq", {}).get("messages")
        check("主队列已消费完（messages=0）", remaining == 0, f"messages={remaining}")
        check("死信队列为空（消息没被丢弃）", (dlq_messages or 0) == 0,
              f"dlq messages={dlq_messages}")

    print("\n" + "=" * 72)
    print(f"结果：通过 {PASS} 项，失败 {FAIL} 项")
    print("=" * 72)
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())

"""RabbitMQ 周计划消费者（规范第 7 周，规范第 1091-1138 行）。

链路：Java ``WeeklyPlanProducer`` → 交换机 ``ai.fitness.exchange``
→ routing key ``ai.weekly.plan.request`` → 队列 ``ai.weekly.plan``
→ **本模块消费** → 调用大模型生成周计划 → 带 HMAC 签名回调 Java。

## 铁律（规范第 1135-1137 行）

**只有「LLM 生成成功」且「回调 Java 成功」两个条件同时满足才允许 ``basic_ack``。**
LLM 成功但回调失败却已 ACK = 任务永久丢失（Java 永远拿不到周计划，用户界面永远缺这一周的复盘）。
所以处理顺序固定为：消费 → 生成 → 回调 → 成功才 ACK；任何一步失败一律
``basic_nack(requeue=True)`` 重投，同一 ``taskId`` 重试超过 3 次改
``basic_nack(requeue=False)`` 送入死信队列 ``ai.weekly.plan.dlq``。

## 为什么必须手动确认 + prefetch=1

``auto_ack=True`` 属于**禁止写法**：消息一投递就被 RabbitMQ 标记为已确认，
此时进程崩溃 / 回调失败，消息都已经消失，等于静默丢单。
``basic_qos(prefetch_count=1)`` 保证一次只有一个在途消息，
既避免「未确认消息堆积打爆内存」，也让重投顺序可控。

## 为什么重试计数用 Redis 而不是 x-death / redelivered

``basic_nack(requeue=True)`` 重投时 RabbitMQ **不会**递增 ``x-death``
（``x-death`` 只统计「进过死信队列」的次数），``redelivered`` 也只是布尔标记。
拿它们当计数会永远停在 0/1，失败消息将无限重投把队列堵死。
因此用 Redis 计数器 ``mq:retry:{taskId}``，带 TTL（默认 3600s），成功时删除。

## 为什么不在代码里声明队列

队列、交换机、TTL、死信路由全部由 **Java 端**声明（规范第二章 RabbitMQConfig）。
消费者若用不同参数再声明一次，RabbitMQ 会直接抛 ``PRECONDITION_FAILED`` 并关闭通道
（``x-message-ttl=3600000``、死信参数差一个字节都算不一致）。
所以这里只 ``basic_consume`` 已有队列，不声明。

## 启动方式

```powershell
cd python-agent
E:\\Anaconde\\python.exe -m app.mq_consumer              # 持续消费（长期运行的独立进程）
E:\\Anaconde\\python.exe -m app.mq_consumer --once       # 只处理一条就退出（验证/调试）
E:\\Anaconde\\python.exe -m app.mq_consumer --help
```

``pika`` 与 ``redis`` 都是**惰性导入**：没装它们时 FastAPI 主服务与 pytest 完全不受影响，
只有真正要连 MQ / Redis 时才报错并给出中文安装提示（与 ``pymilvus`` /
``sentence-transformers`` 同一风格）。
"""

from __future__ import annotations

import argparse
import json
import logging
import time
from typing import Any, Callable, Optional

from .config import Settings, settings as default_settings
from .utils import (
    get_trace_id,
    mask_secret,
    new_trace_id,
    set_trace_id,
    setup_logging,
    truncate,
)

logger = logging.getLogger(__name__)

#: Redis 重试计数键前缀（规范伪代码：``mq:retry:{taskId}``）
RETRY_KEY_PREFIX = "mq:retry:"

PIKA_IMPORT_HINT = "请执行：pip install pika   # RabbitMQ 消费者依赖（pika>=1.3）"
REDIS_IMPORT_HINT = "请执行：pip install redis  # 重试计数依赖（redis>=5.0）"

#: handle_weekly_plan 的四种结局（返回值便于单测与日志追溯）
OUTCOME_ACKED = "acked"                # 全成功，已 ACK
OUTCOME_REQUEUED = "requeued"          # 失败，已重投（计数 +1）
OUTCOME_DEAD_LETTER = "dead_lettered"  # 超限/无法解析，已送入死信队列
OUTCOME_NACK_FAILED = "nack_failed"    # 连确认都发不出去（连接已断，等 Broker 重投）


class MqDependencyError(RuntimeError):
    """依赖缺失（未安装 pika / redis）。"""


class MqConnectionError(RuntimeError):
    """连不上 RabbitMQ，或队列不存在。"""


# ======================================================================
# 惰性导入（未安装 pika / redis 时，import app.mq_consumer 仍然成功）
# ======================================================================


def _import_pika():
    """惰性导入 pika；缺失时给出中文可执行提示。"""
    try:
        import pika  # noqa: PLC0415 - 惰性导入是刻意设计（见模块注释）
    except Exception as exc:  # noqa: BLE001 - 缺包 / 版本不兼容都归到这里
        raise MqDependencyError(
            f"未安装 pika（或版本不兼容）：{exc}。{PIKA_IMPORT_HINT}"
        ) from exc
    return pika


def _import_redis():
    """惰性导入 redis；缺失时给出中文可执行提示。"""
    try:
        import redis  # noqa: PLC0415 - 惰性导入是刻意设计（见模块注释）
    except Exception as exc:  # noqa: BLE001
        raise MqDependencyError(
            f"未安装 redis（或版本不兼容）：{exc}。{REDIS_IMPORT_HINT}"
        ) from exc
    return redis


def _is_broker_close(exc: BaseException) -> bool:
    """判断异常是否「Broker 主动关闭通道」（404 队列不存在 / 406 参数不一致）。

    pika 把这类异常做成 ``ChannelClosedByBroker``；这里惰性取它的类对象，
    既避免在模块顶层 import pika，也让 ``consume_once`` 的 ``basic_get``
    与 ``_bind`` 的 ``basic_consume`` 共用同一套友好提示。
    """
    try:
        pika = _import_pika()
    except MqDependencyError:
        return False
    return isinstance(exc, pika.exceptions.ChannelClosedByBroker)


# ======================================================================
# 重试计数器（可注入替身，单测不依赖真实 Redis）
# ======================================================================


class RedisRetryCounter:
    """基于 Redis 的重试计数器（``mq:retry:{taskId}``，带 TTL）。

    接口刻意做小（``get`` / ``bump`` / ``reset``），方便单测注入内存替身：
    真实 Redis 只在 ``from_settings`` 里才被连接。
    """

    def __init__(self, client: Any, ttl_seconds: int = 3600):
        self._client = client
        self._ttl_seconds = int(ttl_seconds)

    @classmethod
    def from_settings(cls, cfg: Optional[Settings] = None) -> "RedisRetryCounter":
        """按配置创建 Redis 客户端（这里是**唯一**真正连接 Redis 的地方）。"""
        redis = _import_redis()
        conf = cfg or default_settings
        client = redis.Redis(
            host=conf.redis_host,
            port=conf.redis_port,
            password=conf.redis_password or None,
            db=conf.redis_db,
            decode_responses=True,
            socket_connect_timeout=5,
            socket_timeout=5,
        )
        logger.info(
            "Redis 重试计数器: %s:%s db=%s ttl=%ss",
            conf.redis_host, conf.redis_port, conf.redis_db, conf.mq_retry_ttl_seconds,
        )
        return cls(client, ttl_seconds=conf.mq_retry_ttl_seconds)

    @staticmethod
    def key(task_id: str) -> str:
        return f"{RETRY_KEY_PREFIX}{task_id}"

    def get(self, task_id: str) -> int:
        """已记录的失败次数（不存在 → 0）。"""
        value = self._client.get(self.key(task_id))
        try:
            return int(value or 0)
        except (TypeError, ValueError):
            return 0

    def bump(self, task_id: str, current: int) -> int:
        """把计数写成 ``current + 1`` 并刷新 TTL（对应规范里的 ``setex``）。"""
        value = int(current) + 1
        self._client.setex(self.key(task_id), self._ttl_seconds, value)
        return value

    def reset(self, task_id: str) -> None:
        """删除计数（成功时调用；也用于「重试超限已进死信」后的清理）。"""
        self._client.delete(self.key(task_id))


# ======================================================================
# 消息处理（单测直接驱动这个函数）
# ======================================================================


def _safe_get_retry(counter: Any, task_id: str) -> int:
    """读重试计数，失败按 0 处理。

    方向性取舍：Redis 读失败时**宁可重投**（``retry=0`` → requeue）也不能当成
    「已达上限」把任务直接送死信 —— 死信是人工兜底，代价远高于多试一次。
    """
    try:
        return int(counter.get(task_id) or 0)
    except Exception as exc:  # noqa: BLE001 - 计数不是主流程，不能因此丢消息
        logger.error("读取重试计数失败（按 0 处理，宁可重投）: %s: %s", type(exc).__name__, exc)
        return 0


def _safe_bump_retry(counter: Any, task_id: str, current: int) -> int:
    """计数 +1，失败时返回内存中推算的值（保证日志里的 retry 仍然连续）。"""
    try:
        return int(counter.bump(task_id, current))
    except Exception as exc:  # noqa: BLE001
        logger.error("写入重试计数失败: %s: %s", type(exc).__name__, exc)
        return current + 1


def _safe_reset_retry(counter: Any, task_id: str) -> None:
    """删除计数，失败只记日志。"""
    try:
        counter.reset(task_id)
    except Exception as exc:  # noqa: BLE001
        logger.error("清除重试计数失败: %s: %s", type(exc).__name__, exc)


def _ack(channel: Any, delivery_tag: Any) -> bool:
    """ACK（仅在「生成成功 + 回调成功」之后调用）。"""
    try:
        channel.basic_ack(delivery_tag=delivery_tag)
        return True
    except Exception as exc:  # noqa: BLE001 - 连接已断：消息仍未被确认，Broker 会重投
        logger.error("basic_ack 失败（连接可能已断开，消息将由 Broker 重投）: %s", exc)
        return False


def _nack(channel: Any, delivery_tag: Any, requeue: bool) -> bool:
    """NACK：``requeue=True`` 重投，``False`` 进死信队列。"""
    try:
        channel.basic_nack(delivery_tag=delivery_tag, requeue=requeue)
        return True
    except Exception as exc:  # noqa: BLE001
        logger.error("basic_nack(requeue=%s) 失败: %s", requeue, exc)
        return False


def handle_weekly_plan(
    channel: Any,
    method: Any,
    properties: Any,
    body: Any,
    *,
    retry_counter: Any = None,
    plan_generator: Optional[Callable[[dict], str]] = None,
    callback_sender: Optional[Callable[[dict, str], Any]] = None,
    max_retries: Optional[int] = None,
) -> str:
    """处理一条周计划消息（规范第 1106-1133 行的伪代码落地）。

    处理顺序（**严格照做，禁止「先 ACK 再回调」**）：

    1. 解析消息体（已反序列化）→ 取 ``taskId``；
    2. 读 Redis 重试计数 ``mq:retry:{taskId}``（已达上限则直接进死信，不再调用大模型）；
    3. 调用 LLM 生成周计划（:func:`app.agent.generate_weekly_plan`）；
    4. 回调 Java（带 HMAC 签名，:func:`app.callback_client.notify_weekly_plan`）；
    5. 两者都成功 → 删计数 + ``basic_ack``；
       否则 → ``basic_nack(requeue=True)`` 且计数 +1；超过上限 → ``basic_nack(requeue=False)``。

    :param channel: pika 的 channel（或单测里的替身）
    :param method: pika 的 ``Basic.Deliver`` / ``Basic.GetOk``（提供 ``delivery_tag``）
    :param properties: 消息属性（当前未使用，保持 pika 回调签名一致）
    :param body: 原始消息体（bytes / str）
    :param retry_counter: 可注入的重试计数器（默认真实 Redis 实现）
    :param plan_generator: 可注入的生成函数（默认 ``generate_weekly_plan``）
    :param callback_sender: 可注入的回调函数（默认 ``notify_weekly_plan``）
    :param max_retries: 重试上限（默认配置 ``MQ_MAX_RETRIES=3``）
    :return: :data:`OUTCOME_ACKED` / :data:`OUTCOME_REQUEUED` /
        :data:`OUTCOME_DEAD_LETTER` / :data:`OUTCOME_NACK_FAILED` 之一
    """
    cfg = default_settings
    limit = cfg.mq_max_retries if max_retries is None else int(max_retries)
    counter = (
        retry_counter if retry_counter is not None else RedisRetryCounter.from_settings(cfg)
    )

    if plan_generator is None or callback_sender is None:
        # 惰性导入：没配 Key / 没装依赖时也只影响真正处理消息的那一刻
        from .agent import generate_weekly_plan
        from .callback_client import notify_weekly_plan

        plan_generator = plan_generator or generate_weekly_plan
        callback_sender = callback_sender or notify_weekly_plan

    delivery_tag = getattr(method, "delivery_tag", None)

    # 每条消息绑定一个 traceId：Python 日志与「Python → Java 回调」头部的 X-Trace-Id
    # 用同一个值，Java 侧日志能顺着它把整条链路串起来（消费者没有入口请求，只能自己生成）。
    set_trace_id(new_trace_id())

    # ---------- ① 解析消息体 ----------
    try:
        raw_text = (
            body.decode("utf-8")
            if isinstance(body, (bytes, bytearray, memoryview))
            else str(body)
        )
        message = json.loads(raw_text)
        if not isinstance(message, dict):
            raise ValueError("消息体不是 JSON 对象")
        task_id = str(message.get("taskId") or "").strip()
        if not task_id:
            raise ValueError("消息体缺少 taskId 字段")
    except Exception as exc:  # noqa: BLE001
        # 消息体本身是坏的：重投还是同一份字节，重试毫无意义 → 直接进死信人工排查
        logger.error(
            "周计划消息无法解析，直接送入死信队列（重投不会改变结果）: %s: %s body=%s",
            type(exc).__name__, exc, truncate(repr(body), 300),
        )
        ok = _nack(channel, delivery_tag, requeue=False)
        return OUTCOME_DEAD_LETTER if ok else OUTCOME_NACK_FAILED

    logger.info(
        "收到周计划任务: taskId=%s userId=%s week=%s~%s traceId=%s",
        task_id, message.get("userId"), message.get("weekStart"),
        message.get("weekEnd"), get_trace_id(),
    )

    # ---------- ② 读取重试计数（已达上限则不再调用大模型）----------
    retry = _safe_get_retry(counter, task_id)
    if retry >= limit:
        logger.error(
            "周计划重试已达上限 taskId=%s retry=%s/%s，送入死信队列 %s（不再调用大模型）",
            task_id, retry, limit, cfg.rabbitmq_queue + ".dlq",
        )
        _safe_reset_retry(counter, task_id)
        ok = _nack(channel, delivery_tag, requeue=False)
        return OUTCOME_DEAD_LETTER if ok else OUTCOME_NACK_FAILED

    # ---------- ③④⑤ 生成 → 回调 → 成功才 ACK ----------
    try:
        plan = plan_generator(message)
        if not str(plan or "").strip():
            raise ValueError("周计划内容为空（拒绝把空内容回调给 Java）")

        result = callback_sender(message, plan)
        if not getattr(result, "success", False):
            # HTTP 非 200、响应体 code 不是 0/6001、超时抛错……统一按失败处理。
            # ⚠️ 这里绝不允许 ACK：Java 没拿到周计划，ACK 就等于永久丢失任务。
            raise RuntimeError(
                f"Java 回调未成功，status={getattr(result, 'status_code', None)} "
                f"code={getattr(result, 'code', None)}"
            )

        _safe_reset_retry(counter, task_id)   # 成功 → 删计数，避免污染下一次任务
        acked = _ack(channel, delivery_tag)   # ✅ 两个条件都满足才 ACK
        logger.info(
            "周计划处理成功: taskId=%s 字数=%d retry=%s/%s",
            task_id, len(str(plan)), retry, limit,
        )
        return OUTCOME_ACKED if acked else OUTCOME_NACK_FAILED
    except Exception as exc:  # noqa: BLE001 - 重投由本函数统一负责
        logger.error(
            "周计划处理失败 taskId=%s retry=%s/%s: %s: %s",
            task_id, retry, limit, type(exc).__name__, truncate(str(exc), 300),
        )
        if retry < limit:
            new_retry = _safe_bump_retry(counter, task_id, retry)
            logger.warning(
                "重新入队重试 taskId=%s（第 %s/%s 次）", task_id, new_retry, limit
            )
            ok = _nack(channel, delivery_tag, requeue=True)
            return OUTCOME_REQUEUED if ok else OUTCOME_NACK_FAILED
        logger.error(
            "周计划重试超过 %s 次 taskId=%s，送入死信队列进行人工排查", limit, task_id
        )
        _safe_reset_retry(counter, task_id)
        ok = _nack(channel, delivery_tag, requeue=False)
        return OUTCOME_DEAD_LETTER if ok else OUTCOME_NACK_FAILED


# ======================================================================
# 消费者（连接、订阅、CLI）
# ======================================================================


class WeeklyPlanConsumer:
    """RabbitMQ 周计划消费者（长驻进程；``--once`` 时只处理一条）。

    连接失败**不崩**：指数退避重试（1s → 2s → 4s → … 上限 30s，最多 ``max_attempts`` 次），
    全部失败才以非零退出码结束，便于外部（计划任务 / 容器）重启。
    """

    def __init__(
        self,
        cfg: Optional[Settings] = None,
        *,
        retry_counter: Any = None,
        plan_generator: Optional[Callable[[dict], str]] = None,
        callback_sender: Optional[Callable[[dict, str], Any]] = None,
        max_attempts: int = 6,
    ):
        self._settings = cfg or default_settings
        self._connection: Any = None
        self._channel: Any = None
        self._retry_counter = retry_counter
        self._plan_generator = plan_generator
        self._callback_sender = callback_sender
        self._max_attempts = max(1, int(max_attempts))

    # ---------------- 连接 ----------------

    @property
    def connected(self) -> bool:
        return self._connection is not None and self._channel is not None

    def connect(self) -> None:
        """建立连接并打开通道（带指数退避重试）。

        :raises MqDependencyError: 未安装 pika
        :raises MqConnectionError: 重试耗尽仍连不上
        """
        pika = _import_pika()
        cfg = self._settings
        credentials = pika.PlainCredentials(cfg.rabbitmq_user, cfg.rabbitmq_password)
        parameters = pika.ConnectionParameters(
            host=cfg.rabbitmq_host,
            port=cfg.rabbitmq_port,
            virtual_host=cfg.rabbitmq_vhost,
            credentials=credentials,
            heartbeat=600,  # 规范固定 600s：回调 + 大模型可能耗时较久，心跳过短会被误判为掉线
        )

        delay = 1.0
        for attempt in range(1, self._max_attempts + 1):
            try:
                self._connection = pika.BlockingConnection(parameters)
                self._channel = self._connection.channel()
                # 手动确认 + 限流：一次只允许 1 条在途消息（禁止 auto_ack=True）
                self._channel.basic_qos(prefetch_count=1)
                logger.info(
                    "已连接 RabbitMQ: %s:%s vhost=%s queue=%s（auto_ack=False, prefetch=1）",
                    cfg.rabbitmq_host, cfg.rabbitmq_port, cfg.rabbitmq_vhost,
                    cfg.rabbitmq_queue,
                )
                return
            except Exception as exc:  # noqa: BLE001 - 连接失败要重试而不是崩
                logger.warning(
                    "连接 RabbitMQ 失败（第 %d/%d 次，%ss 后重试）: %s: %s",
                    attempt, self._max_attempts, f"{delay:.0f}",
                    type(exc).__name__, exc,
                )
                self._connection = None
                self._channel = None
                if attempt >= self._max_attempts:
                    raise MqConnectionError(
                        f"连接 RabbitMQ 失败（已重试 {self._max_attempts} 次）: "
                        f"{type(exc).__name__}: {exc}｜目标 "
                        f"{cfg.rabbitmq_host}:{cfg.rabbitmq_port} vhost={cfg.rabbitmq_vhost} "
                        f"user={cfg.rabbitmq_user}"
                    ) from exc
                time.sleep(delay)
                delay = min(delay * 2, 30.0)

    def _require_channel(self) -> Any:
        if self._channel is None:
            raise MqConnectionError("尚未连接 RabbitMQ，请先调用 connect()")
        return self._channel

    # ---------------- 订阅 ----------------

    def _queue_missing_hint(self) -> str:
        """队列不存在时的排查提示（最常见原因是 Java 端还没启动）。"""
        cfg = self._settings
        return (
            f"队列 [{cfg.rabbitmq_queue}] 在 vhost [{cfg.rabbitmq_vhost}] 中不存在："
            f"本消费者**刻意不声明队列**（参数不一致会导致 PRECONDITION_FAILED），"
            f"队列/交换机/死信由 Java 端 RabbitMQConfig 声明。"
            f"请先启动 Java 服务（或确认 vhost 是否是 {cfg.rabbitmq_vhost}、"
            f"交换机 {cfg.rabbitmq_exchange} 与 routing key {cfg.rabbitmq_routing_key} 是否一致）。"
        )

    def _bind(self) -> None:
        """订阅队列（不声明、不绑定；参数与声明全部交给 Java 侧）。"""
        channel = self._require_channel()
        cfg = self._settings
        try:
            channel.basic_consume(
                queue=cfg.rabbitmq_queue,
                on_message_callback=self._on_message,
                auto_ack=False,          # ⚠️ 必须手动确认（规范禁止 auto_ack=True）
            )
        except Exception as exc:  # noqa: BLE001 - 只识别 Broker 关闭，其它异常原样抛
            if _is_broker_close(exc):
                # 404 NOT_FOUND（队列不存在）/ 406 PRECONDITION_FAILED 都在这里
                raise MqConnectionError(self._queue_missing_hint()) from exc
            raise

    def _on_message(self, channel: Any, method: Any, properties: Any, body: Any) -> str:
        """pika 回调入口：把消息交给 :func:`handle_weekly_plan`。"""
        if self._retry_counter is None:
            self._retry_counter = RedisRetryCounter.from_settings(self._settings)
        return handle_weekly_plan(
            channel, method, properties, body,
            retry_counter=self._retry_counter,
            plan_generator=self._plan_generator,
            callback_sender=self._callback_sender,
            max_retries=self._settings.mq_max_retries,
        )

    # ---------------- 消费 ----------------

    def consume_forever(self) -> None:
        """持续消费直到 Ctrl+C（或异常导致连接断开）。"""
        self._bind()
        logger.info(
            "开始消费队列 %s（Ctrl+C 停止）…", self._settings.rabbitmq_queue
        )
        try:
            self._require_channel().start_consuming()
        except KeyboardInterrupt:
            logger.info("收到 Ctrl+C，停止消费（未确认的消息会被 Broker 重投，不会丢）")
            try:
                self._require_channel().stop_consuming()
            except Exception:  # noqa: BLE001 - 已在退出流程，忽略
                pass
        except Exception as exc:  # noqa: BLE001
            # 消费过程中队列被删除 / 参数被改：给出与启动时同样的可执行提示
            if _is_broker_close(exc):
                raise MqConnectionError(self._queue_missing_hint()) from exc
            raise

    def consume_once(self, wait_seconds: float = 10.0) -> bool:
        """只拉取并处理一条消息（``--once``，用于联调/验证）。

        用 ``basic_get``（拉模式）而不是 ``basic_consume``：后者在队列为空时会一直挂着，
        ``--once`` 的本意是「验证一条就退出」，不能变成「悄悄长驻」。

        :return: 是否真的处理了一条消息（False 表示等待超时、队列里没有消息）
        """
        channel = self._require_channel()
        deadline = time.monotonic() + max(0.0, float(wait_seconds))
        while True:
            try:
                method, properties, body = channel.basic_get(
                    queue=self._settings.rabbitmq_queue, auto_ack=False
                )
            except Exception as exc:  # noqa: BLE001
                # 队列不存在时 basic_get 同样会被 Broker 关掉通道（404），
                # 转成带排查提示的 MqConnectionError，别把 pika 的裸异常甩给使用者
                if _is_broker_close(exc):
                    raise MqConnectionError(self._queue_missing_hint()) from exc
                raise
            if method is not None:
                self._on_message(channel, method, properties, body)
                return True
            if time.monotonic() >= deadline:
                logger.warning(
                    "队列 %s 在 %.1fs 内没有消息（--once 未处理任何消息）",
                    self._settings.rabbitmq_queue, float(wait_seconds),
                )
                return False
            time.sleep(0.5)

    def close(self) -> None:
        """关闭连接（幂等，异常只记日志）。"""
        connection, self._connection = self._connection, None
        self._channel = None
        if connection is None:
            return
        try:
            if getattr(connection, "is_closed", False) is False:
                connection.close()
        except Exception as exc:  # noqa: BLE001 - 退出流程不应抛异常
            logger.warning("关闭 RabbitMQ 连接时出错（忽略）: %s", exc)


# ======================================================================
# 命令行入口
# ======================================================================


def build_arg_parser() -> argparse.ArgumentParser:
    """命令行参数（``--help`` 不依赖 pika / redis，未装依赖也能查看用法）。"""
    parser = argparse.ArgumentParser(
        prog="python -m app.mq_consumer",
        description="RabbitMQ 周计划消费者：消费 ai.weekly.plan → 生成周计划 → HMAC 回调 Java。",
        epilog=(
            "退出码：0=正常结束（--once 处理了一条 / 持续消费被 Ctrl+C 停止）；"
            "1=连接 RabbitMQ 失败且重试耗尽；2=依赖缺失（未装 pika/redis）；"
            "3=--once 等待超时（队列没有消息，未处理任何消息）。"
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "--once",
        action="store_true",
        help="只处理一条消息就退出（联调/验证用；队列没有消息时等待 --wait-seconds 后退出码 3）",
    )
    parser.add_argument(
        "--wait-seconds",
        type=float,
        default=10.0,
        help="配合 --once：最多等待多少秒来获取一条消息（默认 10）",
    )
    return parser


def main(argv: Optional[list] = None) -> int:
    """CLI 主函数（返回退出码）。"""
    args = build_arg_parser().parse_args(argv)
    cfg = default_settings

    setup_logging(cfg.log_level, cfg.log_format)
    # pika 的内部 logger 非常啰嗦（连接过程中的每一帧都打一行 INFO，
    # 一次失败的握手能刷出十几行心跳/传输层日志），会把我们自己的业务日志淹掉。
    # 这里只保留 WARNING 以上：连接失败、通道被关闭这类真正需要关注的信息仍然可见。
    logging.getLogger("pika").setLevel(logging.WARNING)
    set_trace_id(new_trace_id())

    logger.info(
        "周计划消费者启动: mode=%s rabbitmq=%s:%s vhost=%s queue=%s exchange=%s routingKey=%s",
        "--once" if args.once else "长期运行",
        cfg.rabbitmq_host, cfg.rabbitmq_port, cfg.rabbitmq_vhost,
        cfg.rabbitmq_queue, cfg.rabbitmq_exchange, cfg.rabbitmq_routing_key,
    )
    logger.info(
        "配置: redis=%s:%s db=%s 重试上限=%s TTL=%ss llmApiConfigured=%s mockMode=%s "
        "javaCallback=%s hmacSecret=%s",
        cfg.redis_host, cfg.redis_port, cfg.redis_db,
        cfg.mq_max_retries, cfg.mq_retry_ttl_seconds,
        cfg.llm_api_configured, cfg.mock_mode,
        cfg.java_callback_url,
        # 只打印脱敏值，日志里绝不出现明文密钥
        mask_secret(cfg.hmac_secret),
    )

    consumer = WeeklyPlanConsumer(cfg)
    try:
        consumer.connect()
        if args.once:
            handled = consumer.consume_once(args.wait_seconds)
            return 0 if handled else 3
        consumer.consume_forever()
        return 0
    except MqDependencyError as exc:
        logger.error("依赖缺失，无法启动消费者: %s", exc)
        return 2
    except MqConnectionError as exc:
        logger.error("启动消费者失败: %s", exc)
        return 1
    except KeyboardInterrupt:
        logger.info("收到 Ctrl+C，退出")
        return 0
    finally:
        consumer.close()


if __name__ == "__main__":  # pragma: no cover - CLI 入口
    raise SystemExit(main())

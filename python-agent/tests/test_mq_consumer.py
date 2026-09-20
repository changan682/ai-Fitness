"""MQ 消费者单测（第 7 周）。

**纯逻辑，不连真实 RabbitMQ / Redis / Java**：channel、method、重试计数器、
大模型生成函数、回调函数全部用替身注入，逐条锁住规范第 1106-1137 行的处理顺序。

## 为什么这些断言值得写

第 7 周最危险的缺陷不是「功能没实现」，而是**消息丢失**：

- ``auto_ack=True`` → 消息一投递就被确认，回调失败也找不回来；
- 先 ACK 再回调 → 回调失败时消息已经消失，用户的周计划永久缺失；
- 用 ``x-death`` / ``redelivered`` 当重试计数 → 重投时它们不递增，
  失败消息无限重投把队列堵死。

所以这里断言的重点是**调用顺序**与**「绝不在失败路径上 ACK」**，
而不是「函数有没有被调用过」。
"""

from __future__ import annotations

import builtins
import importlib
import json
import sys
import types

import pytest
import httpx

from app import mq_consumer
from app.callback_client import CallbackError, CallbackResult

# ======================================================================
# 替身
# ======================================================================

TASK_ID = "weekly-plan-1001-2026-08-03"


def make_message(**overrides) -> dict:
    """规范第 1059-1085 行的 MQ 消息体。"""
    message = {
        "taskId": TASK_ID,
        "userId": 1001,
        "weekStart": "2026-08-03",
        "weekEnd": "2026-08-09",
        "trainingSummary": {
            "trainingDays": 5,
            "totalActions": 18,
            "totalVolume": 18500.5,
            "avgRpe": 7.5,
            "topActions": [
                {"actionName": "杠铃卧推", "count": 2, "totalVolume": 5200.0}
            ],
        },
        "bodyMetrics": {"startWeight": 70.8, "endWeight": 70.5, "weightChange": -0.3},
        "dietSummary": {"avgDailyCalories": 2100.5, "totalMeals": 28},
        "timestamp": "2026-08-03T21:00:00",
    }
    message.update(overrides)
    return message


def make_body(**overrides) -> bytes:
    return json.dumps(make_message(**overrides), ensure_ascii=False).encode("utf-8")


class FakeChannel:
    """只记录``basic_ack`` / ``basic_nack`` 的 channel 替身。

    ``events`` 可与生成/回调共用同一个列表，从而断言**调用顺序**
    （「回调发生在 ack 之前」必须靠顺序证明，而不是「两个都调用过」）。
    """

    def __init__(self, events: list | None = None):
        self.calls: list[tuple] = []
        self.events = events if events is not None else []

    def basic_ack(self, delivery_tag=None) -> None:
        self.calls.append(("ack", delivery_tag))
        self.events.append("ack")

    def basic_nack(self, delivery_tag=None, requeue: bool = True) -> None:
        self.calls.append(("nack", delivery_tag, requeue))
        self.events.append(f"nack:{requeue}")

    # --- 断言辅助 ---
    @property
    def acks(self) -> list:
        return [c for c in self.calls if c[0] == "ack"]

    @property
    def nacks(self) -> list:
        return [c for c in self.calls if c[0] == "nack"]


class FakeMethod:
    """pika 的 ``Basic.Deliver`` / ``Basic.GetOk`` 替身。"""

    def __init__(self, delivery_tag: int = 7):
        self.delivery_tag = delivery_tag


class FakeRetryCounter:
    """重试计数器替身（**不依赖真实 Redis**）。"""

    def __init__(self, initial: int = 0):
        self.value = initial
        self.gets: list[str] = []
        self.bumps: list[tuple] = []
        self.resets: list[str] = []

    def get(self, task_id: str) -> int:
        self.gets.append(task_id)
        return self.value

    def bump(self, task_id: str, current: int) -> int:
        self.bumps.append((task_id, current))
        self.value = current + 1
        return self.value

    def reset(self, task_id: str) -> None:
        self.resets.append(task_id)
        self.value = 0


def run_handler(
    *,
    body: bytes | None = None,
    channel: FakeChannel | None = None,
    counter: FakeRetryCounter | None = None,
    plan_generator=None,
    callback_sender=None,
    max_retries: int = 3,
):
    """驱动 :func:`mq_consumer.handle_weekly_plan` 并返回 (结局, channel, counter, 生成器调用次数)。"""
    channel = channel or FakeChannel()
    counter = counter or FakeRetryCounter()
    calls = {"generate": 0, "callback": 0}

    def _generate(message):
        calls["generate"] += 1
        return "## 📅 周计划\n- 内容来自替身"

    def _callback(message, plan):
        calls["callback"] += 1
        return CallbackResult(status_code=200, code=0)

    outcome = mq_consumer.handle_weekly_plan(
        channel,
        FakeMethod(),
        types.SimpleNamespace(),
        make_body() if body is None else body,
        retry_counter=counter,
        plan_generator=plan_generator or _generate,
        callback_sender=callback_sender or _callback,
        max_retries=max_retries,
    )
    return outcome, channel, counter, calls


# ======================================================================
# ① 全成功 → 恰好一次 ACK，且 ACK 在回调之后
# ======================================================================


class TestSuccessPath:

    def test_success_acks_exactly_once(self):
        outcome, channel, counter, calls = run_handler()

        assert outcome == mq_consumer.OUTCOME_ACKED
        assert channel.acks == [("ack", 7)]
        assert channel.nacks == []
        assert calls["generate"] == 1 and calls["callback"] == 1

    def test_callback_happens_before_ack(self):
        """**核心断言**：顺序必须是「回调成功 → ACK」，不能反过来。

        只看「ack 被调用过」是不够的：先 ACK 再回调的实现同样能通过，
        但一旦回调失败，消息已经消失（用户永久拿不到周计划）。
        """
        events: list[str] = []
        channel = FakeChannel(events)

        def _generate(message):
            events.append("generate")
            return "## 📅 周计划"

        def _callback(message, plan):
            events.append("callback")
            return CallbackResult(status_code=200, code=0)

        outcome, channel, _, _ = run_handler(
            channel=channel, plan_generator=_generate, callback_sender=_callback
        )

        assert outcome == mq_consumer.OUTCOME_ACKED
        assert events == ["generate", "callback", "ack"]
        assert events.index("callback") < events.index("ack")

    def test_success_resets_retry_counter(self):
        """成功要删掉 ``mq:retry:{taskId}``，避免污染下一周的同名任务。"""
        _, _, counter, _ = run_handler(counter=FakeRetryCounter(initial=2))

        assert counter.resets == [TASK_ID]
        assert counter.value == 0

    def test_idempotent_code_6001_is_treated_as_success(self):
        """``code=6001``（任务已处理，幂等返回）也是成功：上一次回调其实已落库。"""
        def _callback(message, plan):
            return CallbackResult(status_code=200, code=6001)

        outcome, channel, counter, _ = run_handler(callback_sender=_callback)

        assert outcome == mq_consumer.OUTCOME_ACKED
        assert channel.acks == [("ack", 7)]
        assert channel.nacks == []
        assert counter.resets == [TASK_ID]


# ======================================================================
# ② 回调失败 → 重投，绝不 ACK
# ======================================================================


class TestCallbackFailure:

    @pytest.mark.parametrize(
        "result, label",
        [
            (CallbackResult(status_code=500, code=None), "HTTP 500（Java 崩了）"),
            (CallbackResult(status_code=200, code=9002), "HTTP 200 但签名校验失败 9002"),
            (CallbackResult(status_code=200, code=None), "HTTP 200 但响应体不是预期 JSON"),
            (CallbackResult(status_code=404, code=None), "接口不存在 404"),
        ],
    )
    def test_callback_not_success_requeues_and_never_acks(self, result, label):
        def _callback(message, plan):
            return result

        outcome, channel, counter, calls = run_handler(callback_sender=_callback)

        assert outcome == mq_consumer.OUTCOME_REQUEUED, label
        assert channel.acks == [], f"{label}：回调失败却 ACK = 任务永久丢失"
        assert channel.nacks == [("nack", 7, True)]
        assert calls["generate"] == 1, "大模型已经生成成功，不该重复花钱再调一次"
        assert counter.value == 1, "重试计数要 +1"

    @pytest.mark.parametrize(
        "exc",
        [
            CallbackError("回调 Java 超时（10.0s）"),
            CallbackError("回调 Java 网络异常: ConnectError"),
            TimeoutError("socket timeout"),
        ],
    )
    def test_callback_exception_same_path_as_failure(self, exc):
        """回调抛异常（网络/超时）与「返回非成功」走同一条路径。"""
        def _callback(message, plan):
            raise exc

        outcome, channel, counter, _ = run_handler(callback_sender=_callback)

        assert outcome == mq_consumer.OUTCOME_REQUEUED
        assert channel.acks == []
        assert channel.nacks == [("nack", 7, True)]
        assert counter.bumps == [(TASK_ID, 0)]

    def test_retry_count_increases_then_dead_letters(self):
        """连续失败：计数 0→1→2→3 每次重投；计数达上限后进死信且不再调用大模型。"""

        def _callback(message, plan):
            return CallbackResult(status_code=500, code=None)

        counter = FakeRetryCounter()
        for expected_retry in (1, 2, 3):
            outcome, channel, _, _ = run_handler(
                counter=counter, callback_sender=_callback
            )
            assert outcome == mq_consumer.OUTCOME_REQUEUED
            assert channel.nacks == [("nack", 7, True)]
            assert channel.acks == []
            assert counter.value == expected_retry

        # 第 4 次投递：计数已达上限（3）→ 不再调用大模型，直接送死信
        calls = {"generate": 0}

        def _generate(message):
            calls["generate"] += 1
            return "## 不该被调用"

        outcome, channel, _, _ = run_handler(
            counter=counter, plan_generator=_generate, callback_sender=_callback
        )
        assert outcome == mq_consumer.OUTCOME_DEAD_LETTER
        assert channel.nacks == [("nack", 7, False)]
        assert channel.acks == []
        assert calls["generate"] == 0, "已达重试上限，不该再调用大模型（否则只是浪费额度）"


# ======================================================================
# ③ 重试已达上限 → 直接进死信，不再调用 LLM
# ======================================================================


class TestRetryLimit:

    @pytest.mark.parametrize("retry", [3, 4, 10])
    def test_retry_exhausted_dead_letters_without_llm(self, retry):
        counter = FakeRetryCounter(initial=retry)

        def _generate(message):
            raise AssertionError("重试已达上限时不允许再调用大模型")

        def _callback(message, plan):
            raise AssertionError("重试已达上限时不允许再回调 Java")

        outcome, channel, counter, _ = run_handler(
            counter=counter, plan_generator=_generate, callback_sender=_callback
        )

        assert outcome == mq_consumer.OUTCOME_DEAD_LETTER
        assert channel.nacks == [("nack", 7, False)]
        assert channel.acks == []
        assert counter.value == 0, "进死信后计数要清掉，避免同名任务被永久判死"

    def test_retry_limit_is_configurable(self):
        """重试上限跟随 ``MQ_MAX_RETRIES``（这里注入 1，允许一次重试）。"""
        counter = FakeRetryCounter(initial=1)

        def _generate(message):
            raise AssertionError("retry=1 已达上限 1，不该调用大模型")

        outcome, channel, _, _ = run_handler(
            counter=counter, plan_generator=_generate, max_retries=1
        )

        assert outcome == mq_consumer.OUTCOME_DEAD_LETTER
        assert channel.nacks == [("nack", 7, False)]


# ======================================================================
# ④ LLM 失败 → 重投，不 ACK
# ======================================================================


class TestLlmFailure:

    def test_llm_exception_requeues(self):
        def _generate(message):
            raise RuntimeError("大模型 429 限流")

        outcome, channel, counter, calls = run_handler(plan_generator=_generate)

        assert outcome == mq_consumer.OUTCOME_REQUEUED
        assert channel.acks == []
        assert channel.nacks == [("nack", 7, True)]
        assert counter.bumps == [(TASK_ID, 0)]
        assert calls["callback"] == 0, "没有周计划就不该回调 Java"

    @pytest.mark.parametrize("empty", ["", "   ", None])
    def test_empty_plan_is_failure(self, empty):
        """大模型返回空串也要当失败：空内容回调给 Java 等于让用户看到空白面板。"""
        def _generate(message):
            return empty

        outcome, channel, _, _ = run_handler(plan_generator=_generate)

        assert outcome == mq_consumer.OUTCOME_REQUEUED
        assert channel.acks == []
        assert channel.nacks == [("nack", 7, True)]

    def test_llm_failure_then_success_acks(self):
        """失败一次后重投成功：第二轮必须 ACK 且删掉计数。"""
        counter = FakeRetryCounter()
        state = {"fail": True}

        def _generate(message):
            if state["fail"]:
                state["fail"] = False
                raise RuntimeError("第一次失败")
            return "## 📅 周计划"

        outcome, channel, _, _ = run_handler(counter=counter, plan_generator=_generate)
        assert outcome == mq_consumer.OUTCOME_REQUEUED
        assert channel.nacks == [("nack", 7, True)]

        outcome, channel2, _, _ = run_handler(counter=counter, plan_generator=_generate)
        assert outcome == mq_consumer.OUTCOME_ACKED
        assert channel2.acks == [("ack", 7)]
        assert counter.value == 0


# ======================================================================
# ⑤ 坏消息体 → 直接进死信（重投不会改变结果）
# ======================================================================


class TestBadMessage:

    @pytest.mark.parametrize(
        "body",
        [b"not-json", b"[]", b"{}", b'{"userId": 1001}', "".encode("utf-8")],
    )
    def test_unparseable_body_dead_letters_without_processing(self, body):
        def _generate(message):
            raise AssertionError("消息体不可用时不该调用大模型")

        outcome, channel, _, _ = run_handler(body=body, plan_generator=_generate)

        assert outcome == mq_consumer.OUTCOME_DEAD_LETTER
        assert channel.nacks == [("nack", 7, False)]
        assert channel.acks == []

    def test_accepts_str_body_too(self):
        """pika 也会给 ``str``（decode 过的）body，两种都要能处理。"""
        outcome, channel, _, _ = run_handler(body=json.dumps(make_message(), ensure_ascii=False))

        assert outcome == mq_consumer.OUTCOME_ACKED


# ======================================================================
# ⑥ 计数器异常时不能丢消息
# ======================================================================


class TestRetryCounterRobustness:

    def test_counter_read_failure_requeues_instead_of_dead_letter(self):
        class BrokenCounter(FakeRetryCounter):
            def get(self, task_id):
                raise ConnectionError("Redis 连不上")

        def _generate(message):
            raise RuntimeError("大模型挂")

        outcome, channel, _, _ = run_handler(
            counter=BrokenCounter(), plan_generator=_generate
        )

        # 读不到计数时按 0 处理 → 重投（宁可多试一次，也不能误送死信）
        assert outcome == mq_consumer.OUTCOME_REQUEUED
        assert channel.nacks == [("nack", 7, True)]

    def test_counter_write_failure_still_requeues(self):
        class BrokenCounter(FakeRetryCounter):
            def bump(self, task_id, current):
                raise ConnectionError("Redis 写失败")

        def _callback(message, plan):
            return CallbackResult(status_code=500, code=None)

        outcome, channel, _, _ = run_handler(
            counter=BrokenCounter(), callback_sender=_callback
        )

        assert outcome == mq_consumer.OUTCOME_REQUEUED
        assert channel.nacks == [("nack", 7, True)]


# ======================================================================
# ⑦ Redis 计数器的键名 / TTL（用假 client，不连真实 Redis）
# ======================================================================


class TestRedisRetryCounter:

    class _FakeRedis:
        def __init__(self):
            self.store: dict[str, object] = {}
            self.expirations: list[tuple] = []

        def get(self, key):
            return self.store.get(key)

        def setex(self, key, ttl, value):
            self.store[key] = value
            self.expirations.append((key, ttl, value))

        def delete(self, key):
            self.store.pop(key, None)

    def test_key_format_matches_spec(self):
        assert mq_consumer.RedisRetryCounter.key(TASK_ID) == f"mq:retry:{TASK_ID}"
        assert mq_consumer.RETRY_KEY_PREFIX == "mq:retry:"

    def test_bump_uses_setex_with_ttl_3600(self):
        client = self._FakeRedis()
        counter = mq_consumer.RedisRetryCounter(client, ttl_seconds=3600)

        assert counter.get(TASK_ID) == 0
        assert counter.bump(TASK_ID, 0) == 1
        assert counter.bump(TASK_ID, 1) == 2
        assert client.expirations == [
            (f"mq:retry:{TASK_ID}", 3600, 1),
            (f"mq:retry:{TASK_ID}", 3600, 2),
        ]

        counter.reset(TASK_ID)
        assert counter.get(TASK_ID) == 0

    def test_default_ttl_matches_config_default(self, make_settings):
        """配置默认 TTL = 3600s（与规范伪代码一致）。"""
        assert make_settings().mq_retry_ttl_seconds == 3600
        assert make_settings().mq_max_retries == 3

    def test_broken_value_is_treated_as_zero(self):
        client = self._FakeRedis()
        client.store[f"mq:retry:{TASK_ID}"] = "not-a-number"
        assert mq_consumer.RedisRetryCounter(client).get(TASK_ID) == 0


# ======================================================================
# ⑧ 连接参数 / 手动确认（不连真实 MQ，用假 pika 模块）
# ======================================================================


class FakePikaModule:
    """最小可用的 pika 替身：锁住连接参数与 ``auto_ack=False`` / ``prefetch=1``。"""

    class PlainCredentials:
        def __init__(self, username, password):
            self.username = username
            self.password = password

    class ConnectionParameters:
        def __init__(self, **kwargs):
            self.kwargs = kwargs

    class FakeChannel:
        def __init__(self):
            self.qos: list = []
            self.consume_kwargs: dict = {}

        def basic_qos(self, prefetch_count=None):
            self.qos.append(prefetch_count)

        def basic_consume(self, **kwargs):
            self.consume_kwargs = kwargs

    class BlockingConnection:
        def __init__(self, parameters):
            self.parameters = parameters
            self.channel_obj = FakePikaModule.FakeChannel()

        def channel(self):
            return self.channel_obj

        @property
        def is_closed(self):
            return False

        def close(self):
            pass

    class _Exceptions:
        class ChannelClosedByBroker(Exception):
            pass

    exceptions = _Exceptions


class TestConnectionWiring:

    def test_connect_uses_spec_parameters_and_prefetch_one(self, monkeypatch, make_settings):
        """连接参数与规范第 1095-1102 行一致；``prefetch_count=1`` 限流。"""
        settings = make_settings(
            rabbitmq_host="192.168.199.128",
            rabbitmq_port=5672,
            rabbitmq_vhost="/agent1",
            rabbitmq_user="agent1",
            rabbitmq_password="123456",
        )
        monkeypatch.setattr(mq_consumer, "_import_pika", lambda: FakePikaModule)

        consumer = mq_consumer.WeeklyPlanConsumer(settings)
        consumer.connect()

        params = consumer._connection.parameters
        assert params.kwargs["host"] == "192.168.199.128"
        assert params.kwargs["port"] == 5672
        assert params.kwargs["virtual_host"] == "/agent1"
        assert params.kwargs["heartbeat"] == 600
        assert params.kwargs["credentials"].username == "agent1"
        assert consumer._connection.channel_obj.qos == [1]

    def test_bind_uses_manual_ack_and_does_not_declare_queue(self, monkeypatch, make_settings):
        """``basic_consume(auto_ack=False)`` 是硬要求；不声明队列（交给 Java 侧）。"""
        settings = make_settings(rabbitmq_queue="ai.weekly.plan", rabbitmq_vhost="/agent1")
        monkeypatch.setattr(mq_consumer, "_import_pika", lambda: FakePikaModule)

        consumer = mq_consumer.WeeklyPlanConsumer(settings)
        consumer._channel = FakePikaModule.FakeChannel()
        consumer._bind()

        kwargs = consumer._channel.consume_kwargs
        assert kwargs["queue"] == "ai.weekly.plan"
        assert kwargs["auto_ack"] is False, "auto_ack=True 属于规范禁止写法"
        assert "exchange" not in kwargs and "arguments" not in kwargs
        assert consumer._channel.qos == [], "subscribe 阶段不该重复声明队列/参数"

    def test_missing_queue_gives_actionable_chinese_hint(self, monkeypatch, make_settings):
        """队列不存在（Java 端还没声明）时要给出可执行的排查提示，而不是裸异常。"""
        class Channel(FakePikaModule.FakeChannel):
            def basic_consume(self, **kwargs):
                raise FakePikaModule.exceptions.ChannelClosedByBroker(
                    404, "NOT_FOUND - no queue 'ai.weekly.plan'"
                )

        monkeypatch.setattr(mq_consumer, "_import_pika", lambda: FakePikaModule)
        consumer = mq_consumer.WeeklyPlanConsumer(make_settings())
        consumer._channel = Channel()

        with pytest.raises(mq_consumer.MqConnectionError) as excinfo:
            consumer._bind()

        hint = str(excinfo.value)
        assert "ai.weekly.plan" in hint
        assert "Java" in hint


# ======================================================================
# ⑨ 依赖惰性导入：没装 pika / redis 也不能让模块 import 失败
# ======================================================================


class TestLazyImports:

    def test_module_import_succeeds_without_pika_and_redis(self, monkeypatch):
        """模拟「pika / redis 未安装」重新导入 app.mq_consumer：必须成功。

        这正是本模块把 pika/redis 做成惰性导入的目的：
        未装依赖时 FastAPI 主服务与 pytest 都不受影响，只有真要连 MQ 时才报错。
        """
        real_import = builtins.__import__
        original = sys.modules.get("app.mq_consumer")

        def _blocked(name, *args, **kwargs):
            if name.split(".")[0] in ("pika", "redis"):
                raise ImportError(f"No module named '{name}'")
            return real_import(name, *args, **kwargs)

        monkeypatch.setattr(builtins, "__import__", _blocked)
        sys.modules.pop("app.mq_consumer", None)
        try:
            module = importlib.import_module("app.mq_consumer")
            assert module is not None

            with pytest.raises(module.MqDependencyError) as pika_exc:
                module._import_pika()
            assert "pip install pika" in str(pika_exc.value)

            with pytest.raises(module.MqDependencyError) as redis_exc:
                module._import_redis()
            assert "pip install redis" in str(redis_exc.value)
        finally:
            # 还原 sys.modules，避免影响其它测试（monkeypatch 会自动还原 __import__）
            if original is not None:
                sys.modules["app.mq_consumer"] = original

    def test_import_does_not_pull_pika_or_redis(self):
        """导入模块本身不触发 pika / redis 的导入（惰性导入的直接证据）。"""
        assert "pika" not in sys.modules
        assert "redis" not in sys.modules

    def test_cli_help_works_without_dependencies(self, capsys):
        """``--help`` 必须在没装依赖、没连 MQ 的情况下也能用。"""
        with pytest.raises(SystemExit) as excinfo:
            mq_consumer.main(["--help"])

        assert excinfo.value.code == 0
        output = capsys.readouterr().out
        assert "--once" in output
        assert "退出码" in output


# ======================================================================
# ⑩ 回调契约（不连 Java：注入假的 httpx client）
# ======================================================================


class FakeHttpResponse:
    def __init__(self, status_code: int, payload):
        self.status_code = status_code
        self._payload = payload
        self.text = json.dumps(payload, ensure_ascii=False) if payload is not None else "oops"

    def json(self):
        if self._payload is None:
            raise ValueError("not json")
        return self._payload


class FakeHttpClient:
    """记录「真正发出去的字节与请求头」的 httpx.Client 替身。"""

    def __init__(self, response):
        self._response = response
        self.calls: list[dict] = []

    def post(self, url, content=None, headers=None, timeout=None):
        self.calls.append(
            {"url": url, "content": content, "headers": headers, "timeout": timeout}
        )
        if isinstance(self._response, Exception):
            raise self._response
        return self._response


class TestCallbackContract:

    def test_signature_covers_the_exact_bytes_sent(self, make_settings):
        """**核心断言**：签名用的字节必须就是发出去的那份字节。

        如果实现里「先 dumps 发出去、再拿 dict 重新 dumps 算签名」，
        中文/字段顺序差异会让 Java 侧验签随机失败（9002），
        而这里用 Java 侧的校验函数复算一遍就能立刻发现。
        """
        from app.callback_client import WEEKLY_PLAN_CALLBACK_PATH, notify_weekly_plan
        from app.utils import (
            SIGNATURE_HEADER,
            TIMESTAMP_HEADER,
            TRACE_ID_HEADER,
            verify_hmac_signature,
        )

        settings = make_settings(
            java_callback_url="http://127.0.0.1:8080", hmac_secret="unit-test-secret"
        )
        client = FakeHttpClient(FakeHttpResponse(200, {"code": 0, "msg": "周计划已接收"}))

        result = notify_weekly_plan(
            make_message(), "## 📅 第32周训练复盘与下周计划", settings=settings, client=client
        )

        assert result.success is True
        call = client.calls[0]
        assert call["url"] == f"http://127.0.0.1:8080{WEEKLY_PLAN_CALLBACK_PATH}"
        headers = call["headers"]
        assert TRACE_ID_HEADER in headers
        raw_text = call["content"].decode("utf-8")
        assert "第32周训练复盘与下周计划" in raw_text, "中文必须按 UTF-8 原样发送（ensure_ascii=False）"
        assert verify_hmac_signature(
            "POST",
            WEEKLY_PLAN_CALLBACK_PATH,
            headers[TIMESTAMP_HEADER],
            call["content"],
            headers[SIGNATURE_HEADER],
            "unit-test-secret",
        ), "Java 侧用同样算法必须验签通过（用发出去的原始字节）"

    def test_body_matches_spec_8_1_with_flat_week_summary(self, make_settings):
        """请求体字段与规范 8.1 一致；``weekSummary`` 是**扁平**结构。"""
        from app.callback_client import notify_weekly_plan

        settings = make_settings(hmac_secret="s")
        client = FakeHttpClient(FakeHttpResponse(200, {"code": 0}))
        notify_weekly_plan(make_message(), "建议正文", settings=settings, client=client)

        body = json.loads(client.calls[0]["content"].decode("utf-8"))
        assert set(body) == {"taskId", "userId", "weekStart", "suggestionText", "weekSummary"}
        assert body["taskId"] == TASK_ID
        assert body["userId"] == 1001
        assert body["weekStart"] == "2026-08-03"
        assert body["suggestionText"] == "建议正文"
        assert body["weekSummary"] == {
            "trainingDays": 5,
            "totalVolume": 18500.5,
            "avgRpe": 7.5,
            "weightChange": -0.3,
            "avgCalories": 2100.5,
        }

    def test_code_6001_counts_as_success(self, make_settings):
        from app.callback_client import notify_weekly_plan

        client = FakeHttpClient(
            FakeHttpResponse(200, {"code": 6001, "msg": "任务已处理（幂等返回）"})
        )
        result = notify_weekly_plan(
            make_message(), "正文", settings=make_settings(hmac_secret="s"), client=client
        )

        assert result.status_code == 200 and result.code == 6001
        assert result.success is True

    @pytest.mark.parametrize(
        "response",
        [
            FakeHttpResponse(200, {"code": 9002, "msg": "签名校验失败"}),
            FakeHttpResponse(500, {"code": 6002, "msg": "服务器内部错误"}),
            FakeHttpResponse(200, None),
        ],
    )
    def test_non_success_codes_are_failures(self, make_settings, response):
        from app.callback_client import notify_weekly_plan

        result = notify_weekly_plan(
            make_message(), "正文",
            settings=make_settings(hmac_secret="s"),
            client=FakeHttpClient(response),
        )

        assert result.success is False

    @pytest.mark.parametrize(
        "exc",
        [httpx.TimeoutException("timed out"), httpx.ConnectError("refused")],
    )
    def test_transport_errors_raise_callback_error(self, make_settings, exc):
        """超时/网络异常统一抛 CallbackError（消费者按失败重投处理）。"""
        from app.callback_client import notify_weekly_plan

        with pytest.raises(CallbackError):
            notify_weekly_plan(
                make_message(), "正文",
                settings=make_settings(hmac_secret="s"),
                client=FakeHttpClient(exc),
            )

    def test_blank_hmac_secret_fails_fast(self, make_settings):
        from app.callback_client import notify_weekly_plan

        with pytest.raises(CallbackError) as excinfo:
            notify_weekly_plan(
                make_message(), "正文",
                settings=make_settings(hmac_secret="   "),
                client=FakeHttpClient(FakeHttpResponse(200, {"code": 0})),
            )
        assert "HMAC_SECRET" in str(excinfo.value)

    def test_missing_message_fields_do_not_crash(self, make_settings):
        """消息字段缺失时统计按 0 兜底，仍要把周计划送出去（周计划本身已生成好）。"""
        from app.callback_client import notify_weekly_plan

        client = FakeHttpClient(FakeHttpResponse(200, {"code": 0}))
        notify_weekly_plan(
            {"taskId": TASK_ID}, "正文", settings=make_settings(hmac_secret="s"), client=client
        )

        body = json.loads(client.calls[0]["content"].decode("utf-8"))
        assert body["weekSummary"] == {
            "trainingDays": 0,
            "totalVolume": 0.0,
            "avgRpe": 0.0,
            "weightChange": 0.0,
            "avgCalories": 0.0,
        }

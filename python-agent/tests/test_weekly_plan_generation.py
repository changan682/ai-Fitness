"""周计划生成单测（第 7 周，``app.agent.generate_weekly_plan``）。

**不联网、不依赖 API Key**：以「未配 Key / LLM 打桩 / LLM 打桩报错」三种情况覆盖。

## 为什么这个函数的测试重点是「降级」而不是「调模型成功」

它跑在 MQ 消费者里，抛异常 = 消息被重投，重投 3 次后进死信队列 ——
用户**永远拿不到本周周计划**。所以：

- 未配 Key / ``MOCK_MODE=true`` → 必须回落到本地拼装，且**数字全部来自入参**；
- 大模型报错 / 返回空 → 同样回落，绝不能返回空串；
- 消息字段缺失 → 不崩（消费者拿到的是 Java 发的 JSON，字段少一个不该炸整条链路）。
"""

from __future__ import annotations

import json

import pytest

from app import agent
from app.agent import generate_weekly_plan

# ======================================================================
# 入参：规范第 1059-1085 行的 MQ 消息体
# ======================================================================


def make_message(**overrides) -> dict:
    message = {
        "taskId": "weekly-plan-1001-2026-08-03",
        "userId": 1001,
        "weekStart": "2026-08-03",
        "weekEnd": "2026-08-09",
        "trainingSummary": {
            "trainingDays": 5,
            "totalActions": 18,
            "totalVolume": 18500.5,
            "avgRpe": 7.5,
            "topActions": [
                {"actionName": "杠铃卧推", "count": 2, "totalVolume": 5200.0},
                {"actionName": "杠铃深蹲", "count": 1, "totalVolume": 4100.0},
            ],
        },
        "bodyMetrics": {"startWeight": 70.8, "endWeight": 70.5, "weightChange": -0.3},
        "dietSummary": {"avgDailyCalories": 2100.5, "totalMeals": 28},
        "timestamp": "2026-08-03T21:00:00",
    }
    message.update(overrides)
    return message


class FakeLlm:
    """打桩的大模型客户端：记录 messages，返回固定文本（绝不联网）。"""

    def __init__(self, text: str = "### 🎯 桩大模型生成的周计划\n- 来自桩服务", error: Exception | None = None):
        self.model = "stub-model"
        self._text = text
        self._error = error
        self.calls: list[dict] = []

    @property
    def configured(self) -> bool:
        return True

    def chat_with_system(self, system_prompt, user_prompt, **kwargs) -> str:
        self.calls.append(
            {"system": system_prompt, "user": user_prompt, "kwargs": kwargs}
        )
        if self._error is not None:
            raise self._error
        return self._text


@pytest.fixture
def llm_env(monkeypatch):
    """把配置切成「配了 Key 且非 Mock」，并允许注入打桩客户端。"""

    def _setup(fake: FakeLlm | None = None, *, api_key: str | None = "sk-unit-test"):
        monkeypatch.setattr(agent.settings, "deepseek_api_key", api_key)
        monkeypatch.setattr(agent.settings, "mock_mode", False)
        if fake is not None:
            monkeypatch.setattr("app.llm.get_llm", lambda *a, **k: fake)
        return fake

    return _setup


def offline_env(monkeypatch):
    """未配置 Key（离线兜底路径）。"""
    monkeypatch.setattr(agent.settings, "deepseek_api_key", None)
    monkeypatch.setattr(agent.settings, "mock_mode", False)


# ======================================================================
# ① LLM 路径：用内置 Prompt 模板调用
# ======================================================================


class TestLlmPath:

    def test_uses_builtin_prompt_templates(self, llm_env):
        fake = FakeLlm()
        llm_env(fake)

        plan = generate_weekly_plan(make_message())

        assert plan == fake._text
        assert len(fake.calls) == 1
        call = fake.calls[0]
        assert call["system"] == agent.WEEKLY_PLAN_SYSTEM_PROMPT
        assert call["user"].startswith("本周数据（JSON）：")
        assert "请生成下周训练建议。" in call["user"]

    def test_prompt_carries_real_weekly_numbers(self, llm_env):
        """大模型要「只归纳不算术」，所以入参 JSON 必须原样带过去。"""
        fake = FakeLlm()
        llm_env(fake)

        generate_weekly_plan(make_message())

        payload = fake.calls[0]["user"]
        for token in ("18500.5", "7.5", "2100.5", "杠铃卧推", "2026-08-03"):
            assert token in payload
        # 中文不能被转义成 \uXXXX（否则大模型看到的是一堆转义码）
        assert "\\u" not in payload
        assert json.loads(payload.split("：", 1)[1].split("\n\n")[0])["taskId"] == (
            "weekly-plan-1001-2026-08-03"
        )

    def test_low_temperature_and_token_budget(self, llm_env):
        """周计划要基于给定数据，温度必须调低（与训练总结同一套口径）。"""
        fake = FakeLlm()
        llm_env(fake)

        generate_weekly_plan(make_message())

        assert fake.calls[0]["kwargs"]["temperature"] <= 0.5
        assert fake.calls[0]["kwargs"]["max_tokens"] >= 800


# ======================================================================
# ② 降级：未配 Key → 本地拼装，且数字来自入参
# ======================================================================


class TestOfflineFallback:

    def test_no_api_key_still_returns_markdown_with_input_numbers(self, monkeypatch):
        offline_env(monkeypatch)

        plan = generate_weekly_plan(make_message())

        assert isinstance(plan, str) and plan.strip(), "绝不能返回空串（会让用户看到空白面板）"
        assert plan.startswith("## 📅")
        assert "### 📊 本周数据复盘" in plan
        assert "### 🎯 下周训练调整建议" in plan
        assert "### ⚠️ 风险提示" in plan

        # 关键数字必须来自入参（写死数字的实现在这里会失败）
        for token in ("18500.5", "7.5", "2100.5", "-0.3", "70.8", "70.5", "杠铃卧推"):
            assert token in plan, f"本地拼装文案缺少入参数字 {token}"

    def test_week_number_comes_from_week_start(self, monkeypatch):
        """标题周次由 ``weekStart`` 真实推算（2026-08-03 是第 32 周，与规范示例一致）。"""
        offline_env(monkeypatch)

        plan = generate_weekly_plan(make_message())

        assert "第32周" in plan
        assert "2026-08-03 ~ 2026-08-09" in plan

    def test_mock_mode_skips_llm_entirely(self, monkeypatch):
        """``MOCK_MODE=true`` 时不调用大模型（哪怕 Key 配了）。"""
        fake = FakeLlm(error=AssertionError("MOCK_MODE=true 时不该调用大模型"))
        monkeypatch.setattr(agent.settings, "deepseek_api_key", "sk-unit-test")
        monkeypatch.setattr(agent.settings, "mock_mode", True)
        monkeypatch.setattr("app.llm.get_llm", lambda *a, **k: fake)

        plan = generate_weekly_plan(make_message())

        assert plan.strip()
        assert fake.calls == []

    def test_llm_error_falls_back(self, monkeypatch, llm_env):
        """大模型抛异常（限流/超时/网络）→ 本地拼装，不抛给消费者。"""
        llm_env(FakeLlm(error=RuntimeError("429 限流")))

        plan = generate_weekly_plan(make_message())

        assert plan.strip()
        assert "18500.5" in plan

    def test_empty_llm_reply_falls_back(self, llm_env):
        """大模型返回空串也按失败处理（空内容回调给 Java 等于让用户看空白）。"""
        llm_env(FakeLlm(text="   "))

        plan = generate_weekly_plan(make_message())

        assert plan.strip()
        assert "### 📊 本周数据复盘" in plan

    def test_offline_copy_reflects_high_intensity(self, monkeypatch):
        """数字变化要反映到文案上：高 RPE + 高频训练 → 提示减载（而不是套模板）。"""
        offline_env(monkeypatch)

        plan = generate_weekly_plan(
            make_message(
                trainingSummary={
                    "trainingDays": 6,
                    "totalActions": 24,
                    "totalVolume": 30000.0,
                    "avgRpe": 9.0,
                    "topActions": [{"actionName": "硬拉", "count": 3, "totalVolume": 9000.0}],
                },
                bodyMetrics={"startWeight": 72.0, "endWeight": 70.5, "weightChange": -1.5},
            )
        )

        assert "减载" in plan
        assert "30000" in plan and "9" in plan and "-1.5" in plan


# ======================================================================
# ③ 空数据 / 字段缺失：必须给合理文案，不能崩
# ======================================================================


class TestRobustness:

    def test_empty_training_data_gives_reasonable_copy(self, monkeypatch):
        """本周没有任何训练数据时也要有一份合理文案。"""
        offline_env(monkeypatch)

        plan = generate_weekly_plan(
            make_message(
                trainingSummary={
                    "trainingDays": 0,
                    "totalActions": 0,
                    "totalVolume": 0,
                    "avgRpe": 0,
                    "topActions": [],
                },
                bodyMetrics={},
                dietSummary={},
            )
        )

        assert plan.strip()
        assert "没有可用的训练记录" in plan
        assert "未记录体重数据" in plan
        assert "### ⚠️ 风险提示" in plan

    @pytest.mark.parametrize(
        "message",
        [
            {},
            {"taskId": "t", "userId": 1001},
            {"trainingSummary": None, "bodyMetrics": None, "dietSummary": None},
            {"trainingSummary": {}, "bodyMetrics": {}, "dietSummary": {}},
            {"taskId": "t", "weekStart": "not-a-date", "weekEnd": None},
            {"trainingSummary": {"topActions": ["不是对象", None, {"count": 1}]}},
            None,
            [],
            123,
        ],
    )
    def test_missing_or_wrong_fields_do_not_crash(self, monkeypatch, message):
        """字段缺失/类型不对/整个 message 不是 dict：都只降级，绝不抛异常。"""
        offline_env(monkeypatch)

        plan = generate_weekly_plan(message)

        assert isinstance(plan, str)
        assert plan.strip(), "任何入参都必须返回非空 Markdown"
        assert "### ⚠️ 风险提示" in plan

    def test_non_numeric_values_are_tolerated(self, monkeypatch):
        """Java 传了字符串数字或非法值：按 0 兜底，不崩。"""
        offline_env(monkeypatch)

        plan = generate_weekly_plan(
            {
                "taskId": "t",
                "userId": "1001",
                "weekStart": "2026-08-03",
                "weekEnd": "2026-08-09",
                "trainingSummary": {
                    "trainingDays": "5",
                    "totalActions": "18",
                    "totalVolume": "18500.5",
                    "avgRpe": "七",
                },
                "bodyMetrics": {"weightChange": None},
                "dietSummary": {"avgDailyCalories": "abc"},
            }
        )

        assert plan.strip()
        assert "18500.5" in plan

    def test_no_api_key_path_never_raises_on_llm_wiring_failure(self, monkeypatch):
        """连 ``get_llm`` 都取不到（依赖缺失）时同样降级。"""
        monkeypatch.setattr(agent.settings, "deepseek_api_key", "sk-unit-test")
        monkeypatch.setattr(agent.settings, "mock_mode", False)

        def _boom(*args, **kwargs):
            raise ImportError("httpx 缺失")

        monkeypatch.setattr("app.llm.get_llm", _boom)

        plan = generate_weekly_plan(make_message())

        assert plan.strip()
        assert "18500.5" in plan

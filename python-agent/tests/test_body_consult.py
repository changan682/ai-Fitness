"""身体状态主动问询（批次 D）的规则引擎与降级测试。

<h3>为什么规则兜底值得单独测</h3>
这个功能的卖点是"AI 主动关心你"，但它**必须在大模型不可用时也能用** ——
否则没配 Key 就等于没做。规则版只用快照里的真实数字判断，因此每条规则都能被
精确断言；反过来说，规则写错（比如把"7 天没练"判成练得多）会让 AI 给出方向性错误的建议，
比不回答更糟。

另一个重点是**标记**：规则生成必须自报 ``data_source="rule_based"``，
前端据此标注「规则生成（未使用大模型）」—— 与本项目其它降级路径同一条原则。
"""

from __future__ import annotations

import json

import pytest

from app import agent
from app import llm as llm_module


# ============================================================
# 测试素材
# ============================================================

def snapshot(**overrides) -> dict:
    """一份"普通人练了一周"的快照，各用例按需覆写字段。"""
    base = {
        "user_id": 1001,
        "profile": {
            "gender": 1, "height": 175.0, "weight": 70.0,
            "training_goal": "增肌", "training_level": "新手", "injury_record": [],
        },
        "latest_metric": {
            "record_date": "2026-09-22", "weight_kg": 70.5, "waist_cm": 80.0,
            "arm_cm": 36.0, "leg_cm": 56.0, "body_fat_pct": 18.0,
        },
        "prev_metric": {
            "record_date": "2026-09-15", "weight_kg": 70.0, "waist_cm": 79.5,
        },
        "trend7d": {"weight_delta": 0.5, "waist_delta": 0.5, "weight_avg7d": 70.2, "samples": 2},
        "training7d": {"sessions": 3, "total_volume": 5400.0, "avg_rpe": 7.5, "muscles": ["胸", "腿"]},
        "diet_days_recorded": 5,
    }
    base.update(overrides)
    return base


@pytest.fixture
def no_llm(monkeypatch):
    """模拟"没有大模型 Key"——这是规则路径最典型的触发方式。"""

    class _Unconfigured:
        configured = False
        model = "none"

        def chat_with_system(self, *args, **kwargs):  # pragma: no cover - 不该被调用
            raise AssertionError("未配置 Key 时不应该调用大模型")

    monkeypatch.setattr(llm_module, "get_llm", lambda settings=None: _Unconfigured())
    monkeypatch.setattr(agent.settings, "mock_mode", False)
    return _Unconfigured()


# ============================================================
# 1. 规则兜底：每条规则一个用例
# ============================================================

class TestRuleEngine:
    def test_rule_path_is_marked(self, no_llm):
        """规则生成必须自报家门（前端据此显示「规则生成（未使用大模型）」）。"""
        result = agent.body_consult(snapshot())

        assert result.data_source == "rule_based"
        assert result.degraded is True
        assert result.degradation_reason
        assert result.assessment, "即使走规则也要给出整体判断"

    def test_no_metric_short_circuits_without_calling_llm(self, monkeypatch):
        """一次体测都没有时不调大模型（省 token），只提示先记录。"""

        class _Boom:
            configured = True
            model = "should-not-be-called"

            def chat_with_system(self, *args, **kwargs):  # pragma: no cover
                raise AssertionError("没有体测数据时不该调大模型")

        monkeypatch.setattr(llm_module, "get_llm", lambda settings=None: _Boom())
        result = agent.body_consult(snapshot(latest_metric=None, prev_metric=None, trend7d={"samples": 0}))

        assert result.data_source == "rule_based"
        assert "还没有" in result.assessment or "记录" in result.assessment
        assert result.questions, "要引导用户先记录数据"

    def test_weight_jump_over_one_percent_is_questioned(self, no_llm):
        """单次体重变化 >1% → 追问是水分还是刻意调整。"""
        result = agent.body_consult(snapshot(
            latest_metric={"record_date": "2026-09-22", "weight_kg": 72.0, "waist_cm": 80.0,
                           "body_fat_pct": 18.0},
            prev_metric={"record_date": "2026-09-15", "weight_kg": 70.0, "waist_cm": 79.5},
        ))

        assert any("体重变化" in q.text for q in result.questions), \
            f"应针对 2kg（2.9%）的变化追问，实际追问: {[q.text for q in result.questions]}"
        assert any(f.level == "info" and "体重变化" in f.text for f in result.risk_flags)

    def test_waist_increase_is_flagged_as_warn(self, no_llm):
        """腰围上升 → warn 级别提示 + 追问饮食执行。"""
        result = agent.body_consult(snapshot(
            latest_metric={"record_date": "2026-09-22", "weight_kg": 70.5, "waist_cm": 82.0,
                           "body_fat_pct": 18.0},
            prev_metric={"record_date": "2026-09-15", "weight_kg": 70.4, "waist_cm": 80.0},
        ))

        assert any(f.level == "warn" and "腰围" in f.text for f in result.risk_flags)
        assert any("饮食" in q.text for q in result.questions)

    def test_zero_training_is_flagged(self, no_llm):
        """近 7 天零训练 → warn + 追问原因。"""
        result = agent.body_consult(snapshot(
            training7d={"sessions": 0, "total_volume": 0.0, "avg_rpe": None, "muscles": []}))

        assert any(f.level == "warn" and "没有训练记录" in f.text for f in result.risk_flags)
        assert any("没练" in q.text for q in result.questions)

    def test_high_rpe_triggers_recovery_advice(self, no_llm):
        """平均 RPE ≥ 9 → high 级预警 + 下调强度建议。"""
        result = agent.body_consult(snapshot(
            training7d={"sessions": 5, "total_volume": 9000.0, "avg_rpe": 9.4, "muscles": ["腿"]}))

        assert any(f.level == "high" and "RPE" in f.text for f in result.risk_flags)
        assert any("强度" in s.title for s in result.suggestions)
        assert any("睡眠" in q.text or "恢复" in q.text for q in result.questions)

    def test_missing_body_fat_is_asked(self, no_llm):
        result = agent.body_consult(snapshot(
            latest_metric={"record_date": "2026-09-22", "weight_kg": 70.5, "waist_cm": 80.0}))

        assert any("体脂率" in q.text for q in result.questions)

    def test_insufficient_samples_does_not_fake_trend(self, no_llm):
        """体测样本 < 2 时不硬编趋势，只提示多记几次。"""
        result = agent.body_consult(snapshot(
            trend7d={"samples": 1, "weight_delta": 0.0},
            prev_metric=None,
        ))

        assert "样本" in result.trend_summary or "不足" in result.trend_summary
        assert any("记录" in s.detail for s in result.suggestions)

    def test_output_limits_are_respected(self, no_llm):
        """追问/建议最多 3 条（前端只展示 3 条，多了反而干扰）。"""
        result = agent.body_consult(snapshot(
            latest_metric={"record_date": "2026-09-22", "weight_kg": 73.0, "waist_cm": 83.0},
            prev_metric={"record_date": "2026-09-15", "weight_kg": 70.0, "waist_cm": 79.0},
            training7d={"sessions": 0, "total_volume": 0.0, "avg_rpe": 9.5, "muscles": []},
            trend7d={"samples": 1, "weight_delta": 3.0},
        ))

        assert len(result.questions) <= 3
        assert len(result.suggestions) <= 3
        assert len(result.risk_flags) <= 3

    def test_medical_keyword_forces_high_flag(self, no_llm):
        """涉及伤病时必须给出 high 级就医提示（不由模型决定，规则兜底也要有）。"""
        result = agent.body_consult(snapshot(
            profile={"gender": 1, "height": 175.0, "training_goal": "增肌",
                     "training_level": "新手", "injury_record": ["左膝半月板损伤"]},
            latest_metric={"record_date": "2026-09-22", "weight_kg": 70.5, "waist_cm": 80.0,
                           "body_fat_pct": 18.0, "note": "膝盖疼"},
        ))

        # 快照文本里出现"伤病"关键词时，规则层必须补一条 high
        assert any(f.level == "high" for f in result.risk_flags), \
            f"实际 risk_flags: {result.risk_flags}"


# ============================================================
# 2. 大模型路径
# ============================================================

class TestLlmPath:
    class _StubLlm:
        configured = True
        model = "deepseek-stub"

        def __init__(self, payload):
            self._payload = payload

        def chat_with_system(self, *args, **kwargs):  # noqa: ARG002
            return self._payload

    def test_llm_json_is_parsed_and_marked_llm(self, monkeypatch):
        payload = json.dumps({
            "assessment": "体重上升 0.5kg，腰围上升 0.5cm。",
            "trend_summary": "近一周体重 +0.5kg、腰围 +0.5cm",
            "questions": [{"id": "q1", "text": "这周饮食有变化吗？", "why": "腰围在涨"}],
            "suggestions": [{"title": "微调热量", "detail": "主食减 10%，蛋白质不变"}],
            "risk_flags": [{"level": "warn", "text": "腰围上升，注意热量盈余"}],
        }, ensure_ascii=False)
        monkeypatch.setattr(llm_module, "get_llm", lambda settings=None: self._StubLlm(payload))
        monkeypatch.setattr(agent.settings, "mock_mode", False)

        result = agent.body_consult(snapshot())

        assert result.data_source == "llm"
        assert result.degraded is False
        assert result.degradation_reason is None
        assert result.questions[0].text == "这周饮食有变化吗？"
        assert result.suggestions[0].title == "微调热量"
        assert result.risk_flags[0].level == "warn"

    def test_unparsable_llm_output_falls_back_to_rules(self, monkeypatch):
        """模型返回一段散文（没 JSON）时不能把整段话当结论，必须退回规则。"""
        monkeypatch.setattr(llm_module, "get_llm",
                            lambda settings=None: self._StubLlm("抱歉，我无法给出建议。"))
        monkeypatch.setattr(agent.settings, "mock_mode", False)

        result = agent.body_consult(snapshot())

        assert result.data_source == "rule_based"
        assert result.degraded is True
        assert "规则" in (result.degradation_reason or "")

    def test_llm_exception_falls_back_to_rules(self, monkeypatch):
        class _Boom:
            configured = True
            model = "boom"

            def chat_with_system(self, *args, **kwargs):  # noqa: ARG002
                raise RuntimeError("网络超时")

        monkeypatch.setattr(llm_module, "get_llm", lambda settings=None: _Boom())
        monkeypatch.setattr(agent.settings, "mock_mode", False)

        result = agent.body_consult(snapshot())

        assert result.data_source == "rule_based"
        assert "RuntimeError" in (result.degradation_reason or "")

    def test_llm_empty_assessment_is_rejected(self, monkeypatch):
        """assessment 为空视为结构不可用（避免前端展示一片空白）。"""
        payload = json.dumps({"assessment": "", "trend_summary": "x", "questions": []},
                             ensure_ascii=False)
        monkeypatch.setattr(llm_module, "get_llm", lambda settings=None: self._StubLlm(payload))
        monkeypatch.setattr(agent.settings, "mock_mode", False)

        result = agent.body_consult(snapshot())

        assert result.data_source == "rule_based"


# ============================================================
# 3. HTTP 契约
# ============================================================

class TestHttpContract:
    def test_endpoint_returns_snake_case_data(self, no_llm):
        from fastapi.testclient import TestClient

        from app.main import app

        client = TestClient(app)
        resp = client.post("/agent/v1/body-consult", json=snapshot())

        assert resp.status_code == 200
        body = resp.json()
        assert body["success"] is True
        data = body["data"]
        # Java 侧 PyBodyConsultData 逐字段声明，字段名必须是 snake_case
        for key in ("assessment", "trend_summary", "questions", "suggestions",
                    "risk_flags", "data_source", "degraded", "generated_at"):
            assert key in data, f"响应缺少字段 {key}（Java DTO 会静默丢弃未声明字段）"

    def test_empty_body_is_accepted(self, no_llm):
        """Java 可能传一个只有 user_id 的快照，不能因为缺字段就 422。"""
        from fastapi.testclient import TestClient

        from app.main import app

        client = TestClient(app)
        resp = client.post("/agent/v1/body-consult", json={"user_id": 1})

        assert resp.status_code == 200
        assert resp.json()["data"]["data_source"] == "rule_based"

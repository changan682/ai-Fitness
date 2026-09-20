"""动作推荐接 LLM 后的行为测试（第 6 周）。

第 6 周把 ``recommend_actions`` 从「纯规则引擎」升级为「LLM 生成 + 规则引擎兜底」。
这次升级最大的风险不是 LLM 写得好不好，而是**动作名可能变成训练记录里查不到的野生值**：
前端要用动作名去匹配训练记录、动作库与姿态评估素材，
一旦模型自由发挥造出「仰卧哑铃飞鸟（变式）」这种库里没有的名字，
后续所有按动作名关联的功能都会静默失效。

因此本文件的核心断言是**白名单**：LLM 只能从候选集里挑，
越界的条目必须被丢弃；全部越界或调用失败则整体回退到规则引擎。

这里把 LLM 客户端整体打桩，因此既不联网也不需要 Key。
"""

from __future__ import annotations

import json

import pytest

from app import agent
from app.llm import LLMError


class StubLLM:
    """打桩的大模型客户端：记录收到的 Prompt，返回预设文本。"""

    model = "deepseek-chat-stub"

    def __init__(self, reply: str | None = None, error: Exception | None = None, configured: bool = True):
        self.configured = configured
        self._reply = reply
        self._error = error
        self.calls: list[tuple[str, str, dict]] = []

    def chat_with_system(self, system_prompt, user_prompt, **kwargs):
        self.calls.append((system_prompt, user_prompt, kwargs))
        if self._error:
            raise self._error
        return self._reply or ""

    def chat(self, messages, **kwargs):     # pragma: no cover - 本文件不使用
        raise AssertionError("本测试只使用 chat_with_system")


def _recommend_json(*action_names: str, **overrides) -> str:
    """按 Prompt 约定的 schema 拼一个模型回复。"""
    rows = []
    for name in action_names:
        row = {
            "actionName": name,
            "targetMuscle": "胸",
            "focusArea": "上胸",
            "recommendedSets": "3-4组",
            "recommendedReps": "8-12次",
            "difficulty": "进阶",
            "notes": "肩胛骨收紧，落点控制在胸骨中下部",
            "equipment": ["哑铃", "卧推凳"],
        }
        row.update(overrides)
        rows.append(row)
    return json.dumps({"recommendations": rows}, ensure_ascii=False)


@pytest.fixture
def stub_llm(monkeypatch):
    """默认：真实链路（mock_mode=False）+ 可替换的打桩 LLM。"""
    import app.llm as llm_module

    monkeypatch.setattr(agent.settings, "mock_mode", False)

    holder = {"client": StubLLM(_recommend_json("上斜哑铃卧推"))}

    def _fake_get_llm(settings=None):
        return holder["client"]

    monkeypatch.setattr(llm_module, "get_llm", _fake_get_llm)
    return holder


# ============================================================
# 1. 白名单：这次升级最关键的护栏
# ============================================================

class TestActionNameWhitelist:

    def test_out_of_library_names_are_dropped(self, stub_llm):
        """模型编造的、动作库里没有的动作名必须被丢弃，只保留合法条目。"""
        stub_llm["client"]._reply = _recommend_json("上斜哑铃卧推", "仰卧飞鸟（内旋变式）")

        result = agent.recommend_actions("胸", ["哑铃", "卧推凳"])

        names = [item.action_name for item in result.recommendations]
        assert "上斜哑铃卧推" in names
        assert "仰卧飞鸟（内旋变式）" not in names, "野生动作名必须被丢弃"

    def test_all_names_out_of_library_falls_back_to_rule_engine(self, stub_llm):
        """全部越界 → 抛 LLMError → 回退规则引擎（接口不能因此失败）。"""
        stub_llm["client"]._reply = _recommend_json("虚构动作A", "虚构动作B")

        result = agent.recommend_actions("胸", ["哑铃", "卧推凳"])

        names = [item.action_name for item in result.recommendations]
        assert names, "必须回退到规则引擎并给出结果"
        assert "虚构动作A" not in names
        # 规则引擎的结果一定来自动作库
        library_names = {item["action_name"] for item in agent.ACTION_LIBRARY["胸"]}
        assert set(names) <= library_names

    def test_duplicate_names_are_deduplicated(self, stub_llm):
        stub_llm["client"]._reply = _recommend_json("上斜哑铃卧推", "上斜哑铃卧推", "平板哑铃卧推")

        result = agent.recommend_actions("胸", ["哑铃", "卧推凳"])

        names = [item.action_name for item in result.recommendations]
        assert len(names) == len(set(names)), "同一动作不应重复出现"

    def test_only_names_the_user_can_actually_do_are_accepted(self, stub_llm):
        """候选集已按器械过滤，白名单必须用**候选集**而不是整个动作库。

        「胸」的动作库里「上斜哑铃卧推」需要哑铃；用户只有杠铃时它不在候选集中，
        即使模型推荐了也必须被丢弃 —— 否则前端会给用户推一个他做不了的动作。
        """
        stub_llm["client"]._reply = _recommend_json("平板杠铃卧推", "上斜哑铃卧推")

        result = agent.recommend_actions("胸", ["杠铃"], count=2)

        names = [item.action_name for item in result.recommendations]
        assert "平板杠铃卧推" in names, "候选集内的动作应被保留"
        assert "上斜哑铃卧推" not in names, "用户没有哑铃，该动作不在候选集内，必须丢弃"


# ============================================================
# 2. Prompt 与候选集
# ============================================================

class TestPromptWiring:

    def test_user_prompt_lists_candidates_and_count(self, stub_llm):
        agent.recommend_actions("胸", ["哑铃"], count=3)

        _, user_prompt, _ = stub_llm["client"].calls[0]
        assert "候选动作库" in user_prompt, "必须把候选集塞进 Prompt 才能真正约束模型"
        assert "哑铃" in user_prompt
        assert "3" in user_prompt, "请求条数要传给模型"

    def test_system_prompt_braces_are_unescaped(self, stub_llm):
        """RECOMMEND_SYSTEM_PROMPT 里的 {{ }} 是规范原文的转义写法，必须 format 还原。"""
        agent.recommend_actions("胸", ["哑铃"])

        system_prompt, _, _ = stub_llm["client"].calls[0]
        assert "{{" not in system_prompt and "}}" not in system_prompt
        assert '"recommendations"' in system_prompt

    def test_low_temperature_for_deterministic_output(self, stub_llm):
        agent.recommend_actions("胸", ["哑铃"])

        _, _, kwargs = stub_llm["client"].calls[0]
        assert kwargs.get("temperature", 1.0) <= 0.5


# ============================================================
# 3. 字段健壮性：模型漏字段 / 给非法值
# ============================================================

class TestFieldFallback:

    def test_missing_fields_fall_back_to_library_values(self, stub_llm):
        """模型只给动作名时，其余字段用动作库里的策划值补全，不能返回 null。"""
        stub_llm["client"]._reply = json.dumps(
            {"recommendations": [{"actionName": "上斜哑铃卧推"}]}, ensure_ascii=False)

        item = agent.recommend_actions("胸", ["哑铃", "卧推凳"]).recommendations[0]

        assert item.focus_area and item.recommended_sets and item.recommended_reps
        assert item.notes and item.equipment
        assert item.difficulty in ("新手", "进阶", "高级")

    def test_illegal_difficulty_falls_back(self, stub_llm):
        stub_llm["client"]._reply = _recommend_json("上斜哑铃卧推", difficulty="大师级")

        item = agent.recommend_actions("胸", ["哑铃", "卧推凳"]).recommendations[0]

        assert item.difficulty in ("新手", "进阶", "高级"), "非法难度必须被替换成动作库的值"

    def test_oversized_notes_are_truncated(self, stub_llm):
        stub_llm["client"]._reply = _recommend_json("上斜哑铃卧推", notes="诊" * 500)

        item = agent.recommend_actions("胸", ["哑铃", "卧推凳"]).recommendations[0]

        assert len(item.notes) <= 200, "超长文案要截断，避免把响应撑爆"

    def test_equipment_accepts_string_list_only(self, stub_llm):
        stub_llm["client"]._reply = _recommend_json("上斜哑铃卧推", equipment="哑铃")

        item = agent.recommend_actions("胸", ["哑铃", "卧推凳"]).recommendations[0]

        assert isinstance(item.equipment, list) and item.equipment


# ============================================================
# 4. 降级：LLM 不可用时接口必须照常可用
# ============================================================

class TestDegradation:

    def test_unconfigured_llm_falls_back(self, stub_llm):
        stub_llm["client"].configured = False

        result = agent.recommend_actions("胸", ["哑铃"])
        assert result.recommendations, "没有 Key 也必须能返回推荐（走规则引擎）"

    def test_llm_exception_falls_back(self, stub_llm):
        stub_llm["client"]._error = LLMError("HTTP 429 限流")

        result = agent.recommend_actions("胸", ["哑铃"])
        assert result.recommendations

    def test_non_json_reply_falls_back(self, stub_llm):
        stub_llm["client"]._reply = "我建议你练胸，多做卧推就好了。"

        result = agent.recommend_actions("胸", ["哑铃"])
        assert result.recommendations

    def test_mock_mode_never_calls_llm(self, monkeypatch):
        """MOCK_MODE=true 时直接走规则引擎，一次模型调用都不应发生。"""
        import app.llm as llm_module

        monkeypatch.setattr(agent.settings, "mock_mode", True)
        client = StubLLM(_recommend_json("上斜哑铃卧推"))
        monkeypatch.setattr(llm_module, "get_llm", lambda settings=None: client)

        result = agent.recommend_actions("胸", ["哑铃"])

        assert result.recommendations
        assert client.calls == [], "MOCK_MODE 下不应调用 LLM"

    def test_unknown_muscle_still_rejected_as_input_error(self, stub_llm):
        """降级不等于放弃入参校验：非法肌群仍必须是 400 参数错误。"""
        from app.utils import AgentInputError

        with pytest.raises(AgentInputError):
            agent.recommend_actions("不存在的肌群", ["哑铃"])

    def test_empty_equipment_still_rejected(self, stub_llm):
        from app.utils import AgentInputError

        with pytest.raises(AgentInputError):
            agent.recommend_actions("胸", [])

"""Agent 循环（阶段二）的单元测试。

## 为什么必须测这层，而不是测"模型会不会调工具"

Agent 的行为是非确定性的。把它当黑盒测（"问 X 应该得到 Y"）必然不稳定，
而且失败时看不出是模型的问题还是编排的问题。

这里把两层拆开：

- **模型决策** → 用脚本化假 LLM 精确喂给它（``_ScriptedLLM`` 返回预设的
  ``LLMReply`` 序列），于是这一层变成完全确定的；
- **编排逻辑** → 断言循环的行为：三重上限、消息协议、标记、收口。

真实"模型会不会调工具"由 ``scripts/verify_agent_stage2.py`` 端到端验证。
"""

from __future__ import annotations

import json
from typing import Any, Dict, List, Optional

import pytest

from app import agent, agent_tools
from app.agent_tools import ToolOutcome
from app.llm import LLMReply


# ============================================================
# 测试替身
# ============================================================

class _ScriptedLLM:
    """按脚本依次返回预设 reply；脚本用尽即报错（说明循环比预期多跑了一轮）。"""

    def __init__(self, replies: List[LLMReply], final: str = "收口后的回答",
                 configured: bool = True):
        self._replies = list(replies)
        self._final = final
        self.configured = configured
        self.tool_turn_messages: List[List[dict]] = []
        self.final_messages: List[dict] = []
        self.final_calls = 0
        self.tools_seen = None

    def chat_with_tools(self, messages, *, tools, tool_choice="auto",
                        temperature=0.3, max_tokens=1200):
        self.tool_turn_messages.append(list(messages))
        self.tools_seen = tools
        if not self._replies:
            raise AssertionError("脚本 reply 已用尽 —— 循环比预期多跑了一轮")
        return self._replies.pop(0)

    def chat(self, messages, *, temperature=0.7, max_tokens=1200):
        self.final_calls += 1
        self.final_messages = list(messages)
        return self._final


def _tool_call(name: str, arguments: Optional[dict] = None, call_id: str = "call_1") -> LLMReply:
    return LLMReply(content="", tool_calls=[{
        "id": call_id,
        "name": name,
        "arguments": arguments or {},
        "raw_arguments": json.dumps(arguments or {}, ensure_ascii=False),
    }])


def _answer(text: str = "这是最终回答") -> LLMReply:
    return LLMReply(content=text, tool_calls=[])


def _outcome(name: str, ok: bool = True, *, knowledge_used: bool = False,
             summary: str = "已取到数据", error: Optional[str] = None,
             sources: Optional[list] = None) -> ToolOutcome:
    return ToolOutcome(
        name=name, ok=ok, summary=summary, error=error,
        content=json.dumps({"result": "ok" if ok else None, "error": error}, ensure_ascii=False),
        knowledge_used=knowledge_used, sources=sources or [], elapsed_ms=7,
    )


class _Env:
    """替换 LLM 与工具执行器，把整轮变成纯本地确定性逻辑。"""

    def __init__(self, monkeypatch, make_settings, llm, outcomes, *,
                 max_steps: int = 5, budget: int = 8000, deadline: float = 25.0,
                 mock_mode: bool = False):
        import app.llm as llm_module

        monkeypatch.setattr(agent, "settings", make_settings(
            agent_mode_enabled=True, mock_mode=mock_mode,
            agent_loop_max_steps=max_steps,
            agent_loop_token_budget=budget,
            agent_loop_deadline_seconds=deadline,
        ))
        monkeypatch.setattr(llm_module, "get_llm", lambda *a, **k: llm)

        self.llm = llm
        self.tool_calls: List[Dict[str, Any]] = []
        queue = list(outcomes)

        def _fake_execute(name, arguments=None, **kwargs):
            self.tool_calls.append({"name": name, "arguments": arguments, "kwargs": kwargs})
            if not queue:
                raise AssertionError("工具结果脚本已用尽")
            return queue.pop(0)

        monkeypatch.setattr(agent_tools, "execute_tool", _fake_execute)


# ============================================================
# 工具定义
# ============================================================

def test_all_four_tools_are_declared():
    assert agent_tools.tool_names() == [
        "search_knowledge", "get_weekly_stats", "get_training_records", "get_body_trend",
    ]
    assert agent_tools.LOCAL_TOOLS == frozenset({"search_knowledge"})
    assert agent_tools.REMOTE_TOOLS == frozenset(
        {"get_weekly_stats", "get_training_records", "get_body_trend"})


def test_data_tool_descriptions_forbid_estimation():
    """数据工具的描述必须写明"不要自己估算" —— 这是防幻觉的第一道闸。"""
    by_name = {item["function"]["name"]: item["function"] for item in agent_tools.AGENT_TOOL_SCHEMAS}
    assert "绝不要自己估算" in by_name["get_weekly_stats"]["description"]
    assert by_name["get_training_records"]["parameters"]["required"] == ["startDate", "endDate"]
    # 汇总类问题应被引导到周统计（明细有截断，直接统计会漏数）
    assert "get_weekly_stats" in by_name["get_training_records"]["description"]


# ============================================================
# 循环：正常路径
# ============================================================

def test_single_tool_then_answer(monkeypatch, make_settings):
    llm = _ScriptedLLM([_tool_call("get_weekly_stats", {"weekStart": "2026-09-14"}), _answer("这周练了4天")])
    env = _Env(monkeypatch, make_settings, llm, [_outcome("get_weekly_stats", summary="训练 4 天")])

    result = agent.chat_with_agent_loop("我上周练得怎么样", user_id=36)

    assert result.answer == "这周练了4天"
    assert result.agent["stopped_by"] == "completed"
    assert result.agent["steps_used"] == 1
    assert len(result.steps) == 1
    assert result.steps[0]["tool"] == "get_weekly_stats"
    assert result.steps[0]["ok"] is True
    assert result.steps[0]["summary"] == "训练 4 天"
    # 只用了用户数据工具、没用知识库 → data_source=agent（Agent 独有状态，不是失败）
    assert result.data_source == "agent"
    assert result.knowledge_used is False
    assert result.degraded is True   # 未用知识库 → 如实标记
    # 工具调用时必须带上 userId（远程工具靠它确定代表谁取数）
    assert env.tool_calls[0]["kwargs"]["user_id"] == 36


def test_no_tool_call_answers_directly(monkeypatch, make_settings):
    llm = _ScriptedLLM([_answer("直接回答")])
    _Env(monkeypatch, make_settings, llm, [])

    result = agent.chat_with_agent_loop("你好", user_id=36)
    assert result.answer == "直接回答"
    assert result.data_source == "none"
    assert result.steps == []
    assert result.agent["steps_used"] == 0
    assert llm.final_calls == 0


def test_knowledge_tool_marks_milvus_and_dedupes_sources(monkeypatch, make_settings):
    one = {"category": "动作要领", "title": "深蹲要领", "content": "…", "score": 0.81}
    llm = _ScriptedLLM([
        _tool_call("search_knowledge", {"query": "深蹲"}, "c1"),
        _tool_call("search_knowledge", {"query": "深蹲膝盖"}, "c2"),
        _answer("基于知识库的回答"),
    ])
    _Env(monkeypatch, make_settings, llm, [
        _outcome("search_knowledge", knowledge_used=True, sources=[one]),
        _outcome("search_knowledge", knowledge_used=True, sources=[one]),   # 同一条，应去重
    ])

    result = agent.chat_with_agent_loop("深蹲怎么做", user_id=36)
    assert result.data_source == "milvus"
    assert result.knowledge_used is True
    assert result.degraded is False
    assert len(result.sources) == 1, "同一条知识被两轮命中时不应重复列出"
    assert result.sources[0].score_type == "cosine"
    assert result.agent["steps_used"] == 2


# ============================================================
# 循环：三重上限（最容易被写成死循环/超时的地方）
# ============================================================

def test_max_steps_triggers_finalize_without_tools(monkeypatch, make_settings):
    """达到步数上限后必须**再发一次不带工具的调用**收口，而不是返回空。"""
    replies = [_tool_call("get_weekly_stats", {}, call_id=f"c{i}") for i in range(5)]
    llm = _ScriptedLLM(replies, final="基于已取到数据的回答")
    _Env(monkeypatch, make_settings, llm,
         [_outcome("get_weekly_stats") for _ in range(5)], max_steps=2)

    result = agent.chat_with_agent_loop("查一下", user_id=36)

    assert result.agent["stopped_by"] == "max_steps"
    assert result.agent["steps_used"] == 2
    assert result.answer == "基于已取到数据的回答"      # 收口拿到了答案
    assert llm.final_calls == 1
    assert "最大执行步数" in (result.degradation_reason or "")
    assert result.degraded is True


def test_token_budget_stops_the_loop(monkeypatch, make_settings):
    reply = LLMReply(content="", tool_calls=[{
        "id": "c1", "name": "get_weekly_stats", "arguments": {}, "raw_arguments": "{}",
    }], usage={"total_tokens": 9000})
    llm = _ScriptedLLM([reply], final="预算用尽的回答")
    _Env(monkeypatch, make_settings, llm, [], budget=1000)

    result = agent.chat_with_agent_loop("查一下", user_id=36)

    assert result.agent["stopped_by"] == "budget"
    assert result.agent["steps_used"] == 0            # 预算超了就不该再执行工具
    assert "token 预算" in (result.degradation_reason or "")
    assert result.answer == "预算用尽的回答"


def test_deadline_stops_the_loop(monkeypatch, make_settings):
    """墙钟上限：Python 必须先自己收口，不能让 Java 的 90s 超时把响应丢掉。"""
    state = {"t": 0.0}

    def fake_monotonic():
        state["t"] += 30.0
        return state["t"]

    monkeypatch.setattr("time.monotonic", fake_monotonic)

    llm = _ScriptedLLM([_tool_call("get_weekly_stats")], final="超时后的回答")
    _Env(monkeypatch, make_settings, llm, [_outcome("get_weekly_stats")], deadline=25.0)

    result = agent.chat_with_agent_loop("查一下", user_id=36)

    assert result.agent["stopped_by"] == "deadline"
    assert "耗时超过" in (result.degradation_reason or "")
    assert result.answer == "超时后的回答"


def test_at_most_two_tool_calls_per_turn(monkeypatch, make_settings):
    """一次回复里塞一堆工具调用时只执行前 2 个（防成本放大）。"""
    reply = LLMReply(content="", tool_calls=[
        {"id": f"c{i}", "name": "get_weekly_stats", "arguments": {}, "raw_arguments": "{}"}
        for i in range(4)
    ])
    llm = _ScriptedLLM([reply, _answer("好")])
    _Env(monkeypatch, make_settings, llm, [_outcome("get_weekly_stats") for _ in range(4)])

    result = agent.chat_with_agent_loop("查一下", user_id=36)
    assert result.agent["steps_used"] == 2


# ============================================================
# 消息协议（写错这一层会直接 422 或让模型失忆）
# ============================================================

def test_tool_messages_follow_openai_protocol(monkeypatch, make_settings):
    llm = _ScriptedLLM([
        _tool_call("get_body_trend", {"startDate": "2026-09-01", "endDate": "2026-09-21"}, "cX"),
        _answer("体重变化 -0.6kg"),
    ])
    _Env(monkeypatch, make_settings, llm, [_outcome("get_body_trend")])

    agent.chat_with_agent_loop("我体重变化", user_id=36)

    second_turn = llm.tool_turn_messages[1]
    assistant_msg = second_turn[-2]
    tool_msg = second_turn[-1]
    assert assistant_msg["role"] == "assistant"
    assert assistant_msg["tool_calls"][0]["id"] == "cX"
    assert assistant_msg["tool_calls"][0]["type"] == "function"
    assert tool_msg["role"] == "tool"
    assert tool_msg["tool_call_id"] == "cX"


def test_history_comes_before_current_question(monkeypatch, make_settings):
    llm = _ScriptedLLM([_answer("好")])
    _Env(monkeypatch, make_settings, llm, [])

    agent.chat_with_agent_loop("那这周呢", user_id=36, history=[
        {"role": "user", "content": "我上周练得怎么样"},
        {"role": "assistant", "content": "练了 4 天"},
    ])
    first = llm.tool_turn_messages[0]
    assert first[0]["role"] == "system"
    assert first[1]["content"] == "我上周练得怎么样"
    assert first[-1] == {"role": "user", "content": "那这周呢"}


def test_system_prompt_carries_anti_hallucination_rules():
    assert "绝不允许估算或编造" in agent.AGENT_LOOP_SYSTEM_PROMPT
    assert "建议咨询专业医生或教练" in agent.AGENT_LOOP_SYSTEM_PROMPT
    # Agent 模式用工具记录判定，不需要模型自报标记
    assert "[[KB:USED]]" in agent.AGENT_LOOP_SYSTEM_PROMPT


# ============================================================
# 失败与降级
# ============================================================

def test_tool_error_is_fed_back_and_model_can_still_answer(monkeypatch, make_settings):
    llm = _ScriptedLLM([
        _tool_call("get_weekly_stats"),
        _answer("这项数据暂时取不到，你可以到训练看板查看"),
    ])
    _Env(monkeypatch, make_settings, llm,
         [_outcome("get_weekly_stats", ok=False, error="工具网关超时", summary="取数失败")])

    result = agent.chat_with_agent_loop("我上周练得怎么样", user_id=36)

    assert result.steps[0]["ok"] is False
    assert result.steps[0]["error"] == "工具网关超时"
    assert result.data_source == "none"      # 没拿到任何有效数据
    assert "取不到" in result.answer         # 模型如实告知，而不是编造
    # 错误也要回填给模型，否则它会以为工具没被调用
    tool_msg = llm.tool_turn_messages[1][-1]
    assert "工具网关超时" in tool_msg["content"]


def test_unavailable_when_no_key(monkeypatch, make_settings):
    llm = _ScriptedLLM([], configured=False)
    _Env(monkeypatch, make_settings, llm, [])

    result = agent.chat_with_agent_loop("查一下", user_id=36)

    assert result.degraded is True
    assert result.agent["stopped_by"] == "error"
    assert "未配置 DEEPSEEK_API_KEY" in (result.degradation_reason or "")
    assert result.steps == []


def test_unavailable_in_mock_mode(monkeypatch, make_settings):
    llm = _ScriptedLLM([], configured=True)
    _Env(monkeypatch, make_settings, llm, [], mock_mode=True)

    result = agent.chat_with_agent_loop("查一下", user_id=36)
    assert "MOCK_MODE" in (result.degradation_reason or "")


def test_empty_question_rejected(monkeypatch, make_settings):
    llm = _ScriptedLLM([], configured=False)
    _Env(monkeypatch, make_settings, llm, [])
    with pytest.raises(agent.AgentInputError):
        agent.chat_with_agent_loop("   ", user_id=36)


def test_kb_marker_stripped_from_answer(monkeypatch, make_settings):
    llm = _ScriptedLLM([_answer("[[KB:USED]]\n正文")])
    _Env(monkeypatch, make_settings, llm, [])

    result = agent.chat_with_agent_loop("深蹲怎么做", user_id=36)
    assert "[[KB:" not in result.answer
    assert "正文" in result.answer

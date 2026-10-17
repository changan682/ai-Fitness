"""Agent 模式（阶段一：单轮工具调用）的单元测试。

## 测试策略：把「模型决策」和「编排逻辑」分开

Agent 的行为是非确定性的（同一个问题模型可能调工具也可能不调），
所以**不能**断言"模型会不会调" —— 那需要真实大模型且结果不稳定。

这里用**假 LLM**精确控制模型返回什么（``LLMReply``），然后断言**编排逻辑**：

- 模型不调工具 → 标记必须是什么？
- 模型调了工具、有命中 → 标记是什么？第二轮请求体的 ``role=tool`` 结构对不对？
- 模型调了工具、零命中 / 工具失败 → 会不会把"没依据"说成"有依据"？
- Agent 路径自身出问题 → 有没有退回已验证的固定编排？

这层逻辑是**完全确定的**，也是最容易写出幻觉的地方，所以重点测它。
真实"模型会不会调"由 ``scripts/verify_agent_stage1.py`` 端到端验证。
"""

from __future__ import annotations

from typing import Any, Dict, List, Optional

import pytest

from app import agent, agent_tools
from app.llm import LLMReply
from app.milvus_client import KnowledgeHit
from app.models import ChatResponse


# ============================================================
# 测试替身
# ============================================================

class _FakeEngine:
    """假的 RAG 引擎：记录调用参数，返回预设 hits。"""

    def __init__(self, hits: Optional[List[KnowledgeHit]] = None, error: Optional[Exception] = None):
        self._hits = hits or []
        self._error = error
        self.calls: List[Dict[str, Any]] = []

    def retrieve_with_context(self, question: str, *, category=None, top_k=None):
        self.calls.append({"question": question, "category": category, "top_k": top_k})
        if self._error is not None:
            raise self._error
        return self._hits, ("拼好的上下文" if self._hits else "")


class _FakeLLM:
    """假大模型：第一轮返回预设 reply，收口那轮返回固定文本。"""

    def __init__(self, reply: LLMReply, final: str = "收口后的回答", configured: bool = True):
        self._reply = reply
        self._final = final
        self.configured = configured
        self.tools_seen: Optional[List[dict]] = None
        self.first_messages: Optional[List[dict]] = None
        self.final_messages: Optional[List[dict]] = None
        self.final_calls = 0

    def chat_with_tools(self, messages, *, tools, tool_choice="auto", temperature=0.3, max_tokens=1200):
        self.tools_seen = tools
        self.first_messages = messages
        return self._reply

    def chat(self, messages, *, temperature=0.7, max_tokens=1200):
        self.final_messages = messages
        self.final_calls += 1
        return self._final


def _hit(title: str, score: float, category: str = "动作要领") -> KnowledgeHit:
    return KnowledgeHit(
        category=category, title=title, content=f"{title} 的内容",
        source="测试来源", score=score,
    )


def _tool_call_reply(name: str = "search_knowledge", arguments: Optional[dict] = None,
                     call_id: str = "call_1") -> LLMReply:
    return LLMReply(
        content="",
        tool_calls=[{
            "id": call_id,
            "name": name,
            "arguments": arguments if arguments is not None else {"query": "深蹲膝盖能过脚尖吗"},
            "raw_arguments": '{"query":"深蹲膝盖能过脚尖吗"}',
        }],
    )


def _plain_reply(text: str = "直接回答，没有检索") -> LLMReply:
    return LLMReply(content=text, tool_calls=[])


class _Env:
    """把 agent / agent_tools 的依赖整体替换掉，避免真实网络与 Milvus。"""

    def __init__(self, monkeypatch, make_settings, engine, llm,
                 *, agent_mode: bool = True, mock_mode: bool = False,
                 fallback: Optional[ChatResponse] = None):
        import app.llm as llm_module
        import app.rag as rag_module

        monkeypatch.setattr(agent, "settings",
                            make_settings(agent_mode_enabled=agent_mode, mock_mode=mock_mode))
        monkeypatch.setattr(llm_module, "get_llm", lambda *a, **k: llm)
        monkeypatch.setattr(rag_module, "get_rag_engine", lambda *a, **k: engine)

        self.fallback_used = False
        if fallback is not None:
            def _fake_real_rag(question, category, user_id, history):
                self.fallback_used = True
                return fallback
            monkeypatch.setattr(agent, "_chat_with_real_rag", _fake_real_rag)


# ============================================================
# 工具层
# ============================================================

def test_tool_schema_is_openai_compatible():
    # 阶段二起共有 4 个工具：1 个本地（知识库）+ 3 个远程（Java 工具网关）
    assert agent_tools.tool_names() == [
        "search_knowledge", "get_weekly_stats", "get_training_records", "get_body_trend",
    ]
    schema = agent_tools.AGENT_TOOL_SCHEMAS[0]
    assert schema["type"] == "function"
    fn = schema["function"]
    assert fn["name"] == agent_tools.SEARCH_KNOWLEDGE
    assert fn["parameters"]["required"] == ["query"]
    # 分类白名单必须与知识库的分类一致，否则会检索出空结果
    assert fn["parameters"]["properties"]["category"]["enum"] == list(agent_tools.ALLOWED_CATEGORIES)


def test_description_tells_the_model_not_to_search_blindly():
    """描述里必须写"不要为了严谨而检索"，否则模型会退化成每题都搜。"""
    description = agent_tools.AGENT_TOOL_SCHEMAS[0]["function"]["description"]
    assert "不要为了显得严谨而检索" in description
    assert "绝不要编造依据" in description


def test_unknown_tool_is_rejected_without_raising():
    outcome = agent_tools.execute_tool("delete_everything", {"x": 1})
    assert outcome.ok is False
    assert "未知工具" in (outcome.error or "")
    assert "error" in outcome.content


def test_missing_query_without_fallback_is_rejected():
    outcome = agent_tools.execute_tool("search_knowledge", {})
    assert outcome.ok is False
    assert "query" in (outcome.error or "")


def test_missing_query_falls_back_to_the_original_question(monkeypatch, make_settings):
    """模型把参数写成坏 JSON 时，用用户原问题兜底检索一次，而不是整轮失败。"""
    engine = _FakeEngine(hits=[_hit("深蹲要领", 0.81)])
    import app.rag as rag_module
    monkeypatch.setattr(rag_module, "get_rag_engine", lambda *a, **k: engine)

    outcome = agent_tools.execute_tool(
        "search_knowledge", {}, fallback_query="深蹲怎么站距"
    )
    assert outcome.ok is True
    assert engine.calls[0]["question"] == "深蹲怎么站距"


def test_top_k_and_category_are_normalized(monkeypatch):
    engine = _FakeEngine(hits=[_hit("x", 0.9)])
    import app.rag as rag_module
    monkeypatch.setattr(rag_module, "get_rag_engine", lambda *a, **k: engine)

    agent_tools.execute_tool(
        "search_knowledge", {"query": "q", "top_k": 999, "category": "乱写的分类"}
    )
    call = engine.calls[0]
    assert call["top_k"] == agent_tools.MAX_TOP_K   # 夹到上限
    assert call["category"] is None                # 非法分类被丢弃，不传给 Milvus


def test_empty_hits_reports_knowledge_not_used(monkeypatch):
    engine = _FakeEngine(hits=[])
    import app.rag as rag_module
    monkeypatch.setattr(rag_module, "get_rag_engine", lambda *a, **k: engine)

    outcome = agent_tools.execute_tool("search_knowledge", {"query": "碳水循环怎么安排"})
    assert outcome.ok is True
    assert outcome.knowledge_used is False        # 关键：检索成功 ≠ 拿到依据
    assert outcome.below_floor is True
    assert outcome.sources == []
    assert "未检索到" in outcome.content


def test_below_floor_adds_caution_note(monkeypatch):
    """命中分低于地板时必须提醒模型"可能答不上"，而不是直接当依据用。"""
    engine = _FakeEngine(hits=[_hit("勉强相关", 0.10)])
    import app.rag as rag_module
    monkeypatch.setattr(rag_module, "get_rag_engine", lambda *a, **k: engine)

    outcome = agent_tools.execute_tool("search_knowledge", {"query": "q"})
    assert outcome.ok is True
    assert outcome.below_floor is True
    assert outcome.knowledge_used is True          # 有命中，仍然算用上了
    assert "相似度偏低" in outcome.content


def test_retrieval_failure_becomes_not_ok_and_never_raises(monkeypatch):
    engine = _FakeEngine(error=RuntimeError("Milvus 挂了"))
    import app.rag as rag_module
    monkeypatch.setattr(rag_module, "get_rag_engine", lambda *a, **k: engine)

    outcome = agent_tools.execute_tool("search_knowledge", {"query": "q"})
    assert outcome.ok is False
    assert "Milvus 挂了" in (outcome.error or "")
    assert outcome.knowledge_used is False


# ============================================================
# 编排层：开关与降级
# ============================================================

def test_agent_mode_off_keeps_the_proven_path(monkeypatch, make_settings):
    """默认关闭时，chat_with_rag 必须走原来那条链路（行为不变）。"""
    sentinel = ChatResponse(question="q", answer="固定编排", sources=[],
                            generated_at=agent.now_local())
    env_engine = _FakeEngine()
    llm = _FakeLLM(_plain_reply())
    env = _Env(monkeypatch, make_settings, env_engine, llm,
               agent_mode=False, fallback=sentinel)

    result = agent.chat_with_rag("深蹲怎么做")
    assert result.answer == "固定编排"
    assert env.fallback_used is True
    assert llm.tools_seen is None            # 根本没进 Agent 路径


def test_agent_path_falls_back_when_llm_not_configured(monkeypatch, make_settings):
    """未配 Key → Agent 模式不可用 → 必须退回固定编排，而不是报错。"""
    sentinel = ChatResponse(question="q", answer="兜底", sources=[],
                            generated_at=agent.now_local())
    env = _Env(monkeypatch, make_settings, _FakeEngine(), _FakeLLM(_plain_reply(), configured=False),
               agent_mode=True, fallback=sentinel)

    result = agent.chat_with_rag("深蹲怎么做")
    assert result.answer == "兜底"
    assert env.fallback_used is True


def test_agent_path_falls_back_in_mock_mode(monkeypatch, make_settings):
    sentinel = ChatResponse(question="q", answer="mock 兜底", sources=[],
                            generated_at=agent.now_local())
    env = _Env(monkeypatch, make_settings, _FakeEngine(), _FakeLLM(_plain_reply()),
               agent_mode=True, mock_mode=True, fallback=sentinel)

    result = agent.chat_with_rag("深蹲怎么做")
    assert result.answer == "mock 兜底"
    assert env.fallback_used is True


# ============================================================
# 编排层：四条标记路径（本项目最不能出错的地方）
# ============================================================

def test_no_tool_call_means_llm_only_and_visible(monkeypatch, make_settings):
    """模型判断无需检索 → 必须标 llm_only + sources 空 + knowledge_used=False。

    这是阶段一最有价值的路径：检索不再是强制动作。
    但**必须让调用方看得出来这轮没用知识库**，否则就是"看起来像有依据"。
    """
    llm = _FakeLLM(_plain_reply("直接回答"))
    _Env(monkeypatch, make_settings, _FakeEngine(), llm)

    result = agent.chat_with_rag("我该怎么安排休息日")
    assert result.data_source == "llm_only"
    assert result.sources == []
    assert result.knowledge_used is False
    assert result.degraded is True                      # 前端据此显示"未使用知识库"
    assert "无需检索" in (result.degradation_reason or "")
    assert result.tool_calls == []
    assert llm.final_calls == 0                         # 不需要第二轮调用


def test_tool_call_with_hits_produces_milvus_answer(monkeypatch, make_settings):
    engine = _FakeEngine(hits=[_hit("深蹲要领", 0.81), _hit("膝盖轨迹", 0.66)])
    llm = _FakeLLM(_tool_call_reply(), final="### 回答\n基于资料")
    _Env(monkeypatch, make_settings, engine, llm)

    result = agent.chat_with_rag("深蹲膝盖能过脚尖吗")

    assert result.data_source == "milvus"
    assert result.knowledge_used is True
    assert result.degraded is False
    assert [s.title for s in result.sources] == ["深蹲要领", "膝盖轨迹"]
    assert all(s.score_type == "cosine" for s in result.sources)
    assert result.tool_calls and result.tool_calls[0]["name"] == "search_knowledge"
    assert result.tool_calls[0]["knowledge_used"] is True

    # 第二轮请求体：必须按 OpenAI 协议回填 assistant(tool_calls) → tool(tool_call_id)
    tail = llm.final_messages[-2:]
    assert tail[0]["role"] == "assistant"
    assert tail[0]["tool_calls"][0]["id"] == "call_1"
    assert tail[1]["role"] == "tool"
    assert tail[1]["tool_call_id"] == "call_1"
    # 回填内容必须是工具返回的 JSON（含拼好的 knowledge），不能是裸文本或空
    import json as _json
    tool_payload = _json.loads(tail[1]["content"])
    assert tool_payload["hits"] == 2
    assert tool_payload["best_score"] == 0.81
    assert tool_payload["knowledge"] == "拼好的上下文"


def test_tool_call_without_hits_means_none(monkeypatch, make_settings):
    """检索成功但零命中 —— 这是"知识库没覆盖"的诚实结论，不能标成 milvus。"""
    llm = _FakeLLM(_tool_call_reply(), final="知识库没有相关资料，以下基于通用知识")
    _Env(monkeypatch, make_settings, _FakeEngine(hits=[]), llm)

    result = agent.chat_with_rag("碳水循环怎么安排")
    assert result.data_source == "none"
    assert result.sources == []
    assert result.knowledge_used is False
    assert result.degraded is True
    assert "未检索到" in (result.degradation_reason or "")


def test_tool_failure_means_none_with_reason(monkeypatch, make_settings):
    """工具挂了 → 明确标 none + 失败原因，绝不让答案看起来像有依据。"""
    llm = _FakeLLM(_tool_call_reply(), final="未使用知识库的回答")
    _Env(monkeypatch, make_settings, _FakeEngine(error=RuntimeError("Milvus 不可用")), llm)

    result = agent.chat_with_rag("深蹲怎么做")
    assert result.data_source == "none"
    assert result.knowledge_used is False
    assert "Milvus 不可用" in (result.degradation_reason or "")
    assert llm.final_calls == 0                        # 工具失败后不再跑第二轮


def test_below_floor_hit_is_flagged_but_still_labeled_milvus(monkeypatch, make_settings):
    llm = _FakeLLM(_tool_call_reply(), final="基于资料的谨慎回答")
    _Env(monkeypatch, make_settings,
         _FakeEngine(hits=[_hit("勉强相关", 0.10)]), llm)

    result = agent.chat_with_rag("深蹲怎么做")
    assert result.data_source == "milvus"
    assert result.knowledge_used is True
    assert result.degraded is True                     # 有依据但依据不强
    assert "相关度偏低" in (result.degradation_reason or "")


# ============================================================
# 编排层：其它边界
# ============================================================

def test_only_first_tool_call_is_executed(monkeypatch, make_settings):
    """模型一次发起多个工具调用时只执行第一个 —— 阶段一是单轮，避免重复检索放大成本。"""
    engine = _FakeEngine(hits=[_hit("A", 0.9)])
    reply = LLMReply(content="", tool_calls=[
        {"id": "c1", "name": "search_knowledge", "arguments": {"query": "一"}, "raw_arguments": "{}"},
        {"id": "c2", "name": "search_knowledge", "arguments": {"query": "二"}, "raw_arguments": "{}"},
    ])
    llm = _FakeLLM(reply, final="回答")
    _Env(monkeypatch, make_settings, engine, llm)

    agent.chat_with_rag("深蹲怎么做")
    assert len(engine.calls) == 1


def test_kb_marker_is_stripped_from_final_answer(monkeypatch, make_settings):
    """模型偶尔会照抄常规模式的标记，必须剥掉，否则污染正文。"""
    llm = _FakeLLM(_tool_call_reply(), final="[[KB:USED]]\n### 回答\n正文内容")
    _Env(monkeypatch, make_settings, _FakeEngine(hits=[_hit("A", 0.9)]), llm)

    result = agent.chat_with_rag("深蹲怎么做")
    assert "[[KB:USED]]" not in result.answer
    assert "正文内容" in result.answer


def test_empty_question_still_rejected(monkeypatch, make_settings):
    _Env(monkeypatch, make_settings, _FakeEngine(), _FakeLLM(_plain_reply()))
    with pytest.raises(agent.AgentInputError):
        agent.chat_with_rag("   ")


def test_history_is_passed_before_the_current_question(monkeypatch, make_settings):
    """多轮上下文顺序：system → 历史（正序）→ 本轮问题。"""
    llm = _FakeLLM(_plain_reply("好的"))
    _Env(monkeypatch, make_settings, _FakeEngine(), llm)

    agent.chat_with_rag("那做几组", history=[
        {"role": "user", "content": "深蹲膝盖能过脚尖吗"},
        {"role": "assistant", "content": "可以，但要注意……"},
    ])
    messages = llm.first_messages
    assert messages[0]["role"] == "system"
    assert messages[1]["content"] == "深蹲膝盖能过脚尖吗"
    assert messages[2]["role"] == "assistant"
    assert messages[-1] == {"role": "user", "content": "那做几组"}

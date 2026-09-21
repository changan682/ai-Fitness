"""降级/模拟标记的行为回归测试。

<h3>为什么需要这个文件</h3>
接入真实模型之前，项目有两处「输出看起来真实、实则是编造或降级结果」的路径：

1. ``MOCK_MODE=true`` 时姿态评估返回由图片哈希派生的 45-95 分与预置问题/建议，
   而 ``PoseEvaluateResponse`` 与真实多模态推理**结构完全一致**；
2. 知识库不可用时问答退回内置 18 条条目，其 ``sources[].score`` 是
   ``0.62 + 0.08*命中数 + 0.15*重合度`` 的**启发式合成值**，却与真实余弦相似度同形；
   更糟的是 ``_build_rag_answer`` 只在 ``best_score < 阈值`` 时才加降级提示，
   于是「命中的好」的问题会拿回一份**完全看不出降级**的回答。

本文件锁住这两处的可见性：不管走到哪一层，调用方都必须能从响应里看出
「这个分数是模型给的还是算出来的」「这次回答用的是知识库还是内置条目」。

只在字段层面加断言是不够的（字段存在但填错值一样没用），因此这里断言的是
**每个分支实际填入的值**。
"""

from __future__ import annotations

import base64
import json
import struct
import zlib

import pytest

from app import agent
from app import llm as llm_module
from app import multimodal
from app import rag as rag_module


# ============================================================
# 测试素材
# ============================================================

def _png(size: int = 64) -> bytes:
    """生成一张纯色 PNG（多模态模型要求宽高 > 10px）。"""
    raw = b"".join(b"\x00" + bytes((200, 120, 90)) * size for _ in range(size))

    def chunk(tag: bytes, data: bytes) -> bytes:
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    ihdr = struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


PNG_BYTES = _png()
IMAGE_B64 = base64.b64encode(PNG_BYTES).decode("ascii")

_POSE_JSON = json.dumps({
    "score": 78,
    "issues": ["膝盖内扣"],
    "suggestions": ["下蹲时主动向外打开膝盖"],
    "good_points": ["下蹲深度达标"],
}, ensure_ascii=False)


# ============================================================
# 1. 姿态评估：真实推理 vs 本地模拟
# ============================================================

class StubMultimodal:
    """打桩的多模态客户端（真实路径）。"""

    model = "qwen-vl-max-stub"
    configured = True

    def describe_image(self, image_base64, user_prompt, **kwargs):  # noqa: ARG002
        return _POSE_JSON


class TestPoseDataSource:
    def test_real_multimodal_is_marked_qwen_vl(self, monkeypatch):
        """真实多模态路径必须标注 data_source=qwen_vl。"""
        monkeypatch.setattr(agent.settings, "mock_mode", False)
        monkeypatch.setattr(multimodal, "get_multimodal", lambda settings=None: StubMultimodal())

        result = agent.evaluate_pose(IMAGE_B64, "深蹲")

        assert result.data_source == "qwen_vl"
        assert result.score == 78

    def test_mock_mode_is_marked_mock_local(self, monkeypatch):
        """MOCK_MODE=true 的模拟打分必须标注 data_source=mock_local。

        这是本文件要防的核心问题：模拟分数与真实分数在结构上无法区分，
        若不标注，Java/前端会把编造的数字当成真实评估结果展示给用户。
        """
        monkeypatch.setattr(agent.settings, "mock_mode", True)

        result = agent.evaluate_pose(IMAGE_B64, "深蹲")

        assert result.data_source == "mock_local", (
            "模拟打分必须自报家门 —— 否则与真实 qwen-vl 推理不可区分"
        )
        # 分数仍在合法区间内（模拟实现的特征：45-95，由哈希派生）
        assert 45 <= result.score <= 95
        assert result.score_level == agent.score_level(result.score)


# ============================================================
# 2. RAG 问答：真实检索 vs 内置兜底
# ============================================================

class _Hit:
    """模拟 milvus KnowledgeHit。"""

    def __init__(self, score: float, title: str):
        self.score = score
        self.category = "动作要领"
        self.title = title
        self.content = "深蹲时膝盖应沿脚尖方向外推，避免内扣。"
        self.source = "运动解剖学"


class _StubEngine:
    def __init__(self, hits, context: str = "【1】深蹲要点…"):
        self._hits, self._context = hits, context

    def retrieve_with_context(self, question, category=None):  # noqa: ARG002
        return self._hits, self._context


class _StubLLM:
    model = "deepseek-stub"
    configured = True

    def chat_with_system(self, system_prompt, user_prompt, **kwargs):  # noqa: ARG002
        return "## 可以\n\n膝盖适度超过脚尖是正常的。\n\n> 📚 参考：《运动解剖学》"


class TestChatDataSource:
    def test_real_rag_is_not_marked_degraded(self, monkeypatch):
        """完整 RAG（检索命中 + LLM 可用）不应带任何降级标记。"""
        hits = [_Hit(0.7321, "深蹲膝内扣的纠正")]
        monkeypatch.setattr(llm_module, "get_llm", lambda settings=None: _StubLLM())
        monkeypatch.setattr(rag_module, "get_rag_engine", lambda settings=None: _StubEngine(hits))

        resp = agent.chat_with_rag("深蹲膝盖内扣怎么办")

        assert resp.data_source == "milvus"
        assert resp.degraded is False
        assert resp.degradation_reason is None
        assert [s.score_type for s in resp.sources] == ["cosine"]
        assert resp.sources[0].score == 0.7321

    def test_builtin_fallback_is_fully_marked(self, monkeypatch):
        """第 3 层兜底：来源库、降级原因、分数口径三项都必须如实标注。"""
        def _boom(*args, **kwargs):  # noqa: ARG001
            raise RuntimeError("模拟：Milvus 不可用")

        monkeypatch.setattr(agent, "_chat_with_real_rag", _boom)

        resp = agent.chat_with_rag("深蹲时膝盖可以超过脚尖吗")

        assert resp.data_source == "builtin"
        assert resp.degraded is True
        assert resp.degradation_reason and "内置" in resp.degradation_reason
        assert resp.sources, "兜底也要给出来源"
        assert {s.score_type for s in resp.sources} == {"heuristic"}, (
            "内置兜底的分数是启发式合成值，不能标成 cosine"
        )
        # 分数确实落在合成公式的值域内（0.62 起步），与余弦相似度不是一回事
        assert all(s.score >= 0.62 for s in resp.sources)

    def test_high_scoring_fallback_still_carries_the_notice(self, monkeypatch):
        """**本条是问题 2 的核心回归测试**。

        旧实现只在 ``best_score < 阈值`` 时才加降级提示，因此「命中的好」的问题会拿回
        一份带 0.9x 分与「📚 参考」引用、却没有任何降级痕迹的回答 —— 与真实 RAG 无法区分。
        这里特意构造一个高分用例，断言提示**依然存在**。
        """
        question = "深蹲时膝盖可以超过脚尖吗"

        # 先确认这个用例确实是「高分」情形（否则本测试就没测到那个漏洞）
        best = max(agent._score_entry(question, e) for e in agent.MOCK_KNOWLEDGE)
        assert best >= agent._retrieval_threshold(), (
            f"用例构造有误：best_score={best} 未达到阈值，测不到「高分漏标注」这个场景"
        )

        monkeypatch.setattr(agent, "_chat_with_real_rag", lambda *a, **k: (_ for _ in ()).throw(RuntimeError("x")))

        resp = agent.chat_with_rag(question)

        assert best >= 0.75, "内置打分的上限约 0.97，此处应为一个高分问题"
        assert "内置知识条目" in resp.answer, (
            "即使命中分数很高，也必须明确告知这不是 Milvus 知识库检索结果"
        )
        assert "启发式" in resp.answer or "非向量相似度" in resp.answer, (
            "还要说清分数的口径，避免被当成余弦相似度"
        )

    def test_layer2_keeps_cosine_scores_but_flags_no_polish(self, monkeypatch):
        """第 2 层（真实检索 + 本地拼装）：分数仍是真实余弦，但要标明未经大模型润色。

        这一层的两个事实必须分开表达：来源是真实的（score_type=cosine），
        回答是拼装的（degraded=True）—— 混为一谈会让人误以为分数也是假的。
        """
        hits = [_Hit(0.6801, "深蹲膝内扣的纠正")]
        monkeypatch.setattr(rag_module, "get_rag_engine", lambda settings=None: _StubEngine(hits))

        class _UnconfiguredLLM:
            model = "none"
            configured = False

        monkeypatch.setattr(llm_module, "get_llm", lambda settings=None: _UnconfiguredLLM())
        monkeypatch.setattr(agent.settings, "mock_mode", False)

        resp = agent.chat_with_rag("深蹲膝盖内扣怎么办")

        assert resp.data_source == "milvus", "来源仍是真实知识库"
        assert [s.score_type for s in resp.sources] == ["cosine"]
        assert resp.degraded is True
        assert "未经" in (resp.degradation_reason or "")

    def test_no_hits_with_llm_is_marked_source_none(self, monkeypatch):
        """检索失败但 LLM 可用（纯大模型回答）：来源应为 none，而不是假装有来源。"""
        monkeypatch.setattr(llm_module, "get_llm", lambda settings=None: _StubLLM())
        monkeypatch.setattr(rag_module, "get_rag_engine", lambda settings=None: _StubEngine([]))

        resp = agent.chat_with_rag("随便问问")

        assert resp.sources == []
        assert resp.data_source == "none"
        assert resp.degraded is True
        assert resp.degradation_reason


# ============================================================
# 3. 契约：这些标记必须真的出现在 HTTP 响应里
# ============================================================

class TestMarkersReachTheHttpEnvelope:
    """agent 层返回对了还不够 —— 要确认它们真的进了给 Java 的 JSON。"""

    def test_pose_response_json_contains_data_source(self, monkeypatch):
        monkeypatch.setattr(agent.settings, "mock_mode", True)

        payload = agent.evaluate_pose(IMAGE_B64, "深蹲").model_dump(mode="json")

        assert payload["data_source"] == "mock_local"

    def test_chat_response_json_contains_degradation_markers(self, monkeypatch):
        monkeypatch.setattr(
            agent,
            "_chat_with_real_rag",
            lambda *a, **k: (_ for _ in ()).throw(RuntimeError("x")),
        )

        payload = agent.chat_with_rag("深蹲时膝盖可以超过脚尖吗").model_dump(mode="json")

        assert payload["data_source"] == "builtin"
        assert payload["degraded"] is True
        assert payload["degradation_reason"]
        assert all(s["score_type"] == "heuristic" for s in payload["sources"])


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(pytest.main([__file__, "-q"]))

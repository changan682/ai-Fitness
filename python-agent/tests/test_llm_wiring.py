"""大模型接线测试 —— 用本地桩服务验证「真实 LLM 路径」确实被走通。

## 这组测试解决什么问题

第 5 周要求「把 `generate_summary` 从 Mock 替换为真实 DeepSeek 调用」。
但在拿到 API Key 之前无法做真实调用，于是用桩服务验证**接线本身**：

1. 请求真的发出去了吗？——断言桩服务收到了请求；
2. 请求体结构对吗？——断言 `model`、`messages` 的 role 顺序、`temperature`；
3. **Prompt 里带上了业务数据吗？**——断言算好的总容量、对比口径都在 prompt 文本里；
4. 大模型返回的文本真的被采用了吗？——断言结果包含桩返回的独特标记
   （本地拼装绝不会产生该标记，因此这能区分「走了 LLM」与「悄悄降级了」）；
5. 大模型挂了会降级吗？——桩返回 500，断言回落到本地拼装且不抛异常。

Key 到位后只需把 `LLM_BASE_URL` 换回 `https://api.deepseek.com`，
这 5 条结论全部继续成立。
"""

from __future__ import annotations

import pytest

from app import agent
from app.llm import DeepSeekClient, LLMError

#: 桩返回文本里的独特标记，用于证明「结果来自大模型」
STUB_MARK = "桩大模型返回的总结"


def _install_stub_llm(stub_llm, monkeypatch, *, model: str = "deepseek-chat"):
    """把全局 LLM 单例替换成指向桩服务的客户端。"""
    client = DeepSeekClient(api_key="sk-test-fake", base_url=stub_llm.base_url,
                            model=model, max_retries=0)
    monkeypatch.setattr("app.llm._client_singleton", client)
    return client


RECORDS = [
    {"action": "杠铃卧推", "sets": 4, "reps": 10, "weight": 60.0, "rpe": 8},
    {"action": "上斜哑铃卧推", "sets": 3, "reps": 12, "weight": 25.0, "rpe": 7},
    {"action": "绳索夹胸", "sets": 3, "reps": 15, "weight": 15.0, "rpe": 6},
]

COMPARISON = {
    "previousDate": "2026-07-23",
    "scope": "同部位",
    "sharedMuscles": ["胸"],
    "sharedActions": ["杠铃卧推"],
    "records": [{"action": "杠铃卧推", "sets": 4, "reps": 10, "weight": 57.5}],
    "volumeChangePct": 5.3,
}


class TestSummaryLlmPath:

    def test_should_use_llm_result_when_configured(self, stub_llm, make_settings, monkeypatch):
        """配好 Key 时，总结内容必须来自大模型（而不是本地拼装）。"""
        _install_stub_llm(stub_llm, monkeypatch)
        monkeypatch.setattr(agent, "settings", make_settings(mock_mode=False))

        result = agent.generate_summary(
            user_id=1001, date_str="2026-07-30", records=RECORDS, comparison=COMPARISON)

        assert STUB_MARK in result.summary, (
            "结果里没有桩服务的标记，说明走的不是 LLM 路径：\n" + result.summary)
        assert len(stub_llm.requests) == 1, "应当只调用一次大模型"

    def test_request_shape_matches_openai_protocol(self, stub_llm, make_settings, monkeypatch):
        """请求体必须符合 OpenAI 兼容协议（DeepSeek 用的就是这套）。"""
        _install_stub_llm(stub_llm, monkeypatch, model="deepseek-chat")
        monkeypatch.setattr(agent, "settings", make_settings(mock_mode=False))

        agent.generate_summary(user_id=1001, date_str="2026-07-30", records=RECORDS)

        body = stub_llm.last_body()
        assert stub_llm.requests[-1]["path"].endswith("/chat/completions")
        assert body["model"] == "deepseek-chat"
        assert body["stream"] is False
        assert [m["role"] for m in body["messages"]] == ["system", "user"], \
            "必须是 system + user 两条，顺序不能反"
        assert body["temperature"] < 1.0, "总结基于给定数据，温度应偏低"

    def test_prompt_carries_business_data(self, stub_llm, make_settings, monkeypatch):
        """Prompt 里必须带上 Java 侧算好的业务数据 —— 让大模型只做归纳、不做算术。"""
        _install_stub_llm(stub_llm, monkeypatch)
        monkeypatch.setattr(agent, "settings", make_settings(mock_mode=False))

        agent.generate_summary(
            user_id=1001, date_str="2026-07-30", records=RECORDS, comparison=COMPARISON)

        prompt = stub_llm.last_body()["messages"][1]["content"]
        # 4×10×60 + 3×12×25 + 3×15×15 = 2400 + 900 + 675 = 3975
        assert "3975" in prompt, "总容量应由服务端算好放进 prompt：\n" + prompt
        assert "杠铃卧推" in prompt
        assert "2026-07-30" in prompt
        for record in RECORDS:
            assert record["action"] in prompt

    def test_prompt_states_comparison_scope(self, stub_llm, make_settings, monkeypatch):
        """对比口径必须写进 prompt，否则大模型会把「同部位」说成「总容量」。"""
        _install_stub_llm(stub_llm, monkeypatch)
        monkeypatch.setattr(agent, "settings", make_settings(mock_mode=False))

        agent.generate_summary(
            user_id=1001, date_str="2026-07-30", records=RECORDS, comparison=COMPARISON)

        prompt = stub_llm.last_body()["messages"][1]["content"]
        assert "共同肌群" in prompt
        assert "胸" in prompt
        assert "请勿表述为总容量变化" in prompt, "必须明确禁止把口径说错"

    def test_system_prompt_is_the_spec_template(self, stub_llm, make_settings, monkeypatch):
        """system prompt 必须用规范内置的模板，不能是临时拼的几句话。"""
        _install_stub_llm(stub_llm, monkeypatch)
        monkeypatch.setattr(agent, "settings", make_settings(mock_mode=False))

        agent.generate_summary(user_id=1001, date_str="2026-07-30", records=RECORDS)

        system = stub_llm.last_body()["messages"][0]["content"]
        assert "资深健身教练" in system
        assert "最佳表现" in system and "对比分析" in system and "改进建议" in system


class TestSummaryDegradation:

    def test_should_fall_back_when_llm_fails(self, stub_llm, make_settings, monkeypatch):
        """大模型报错时必须回落到本地拼装，且数字仍然正确（不能让前端白屏）。"""
        _install_stub_llm(stub_llm, monkeypatch)
        stub_llm.set_status(500)
        monkeypatch.setattr(agent, "settings", make_settings(mock_mode=False))

        result = agent.generate_summary(
            user_id=1001, date_str="2026-07-30", records=RECORDS, comparison=COMPARISON)

        assert STUB_MARK not in result.summary, "桩明明返回 500，不该拿到它的文本"
        assert "今日训练总结" in result.summary, "应回落到本地拼装的结构"
        assert "3975" in result.summary, "降级后数字仍须由入参真实计算"

    def test_should_not_call_llm_when_mock_mode(self, stub_llm, make_settings, monkeypatch):
        """MOCK_MODE=true 时不该发起任何大模型请求（该开关的语义就是省额度）。"""
        _install_stub_llm(stub_llm, monkeypatch)
        monkeypatch.setattr(agent, "settings", make_settings(mock_mode=True))

        result = agent.generate_summary(
            user_id=1001, date_str="2026-07-30", records=RECORDS, comparison=COMPARISON)

        assert stub_llm.requests == [], f"MOCK_MODE=true 却发了 {len(stub_llm.requests)} 次请求"
        assert "3975" in result.summary

    def test_should_fall_back_when_no_api_key(self, stub_llm, make_settings, monkeypatch):
        """没配 Key 时同样回落，且不报错。"""
        client = DeepSeekClient(api_key="", base_url=stub_llm.base_url, max_retries=0)
        monkeypatch.setattr("app.llm._client_singleton", client)
        monkeypatch.setattr(agent, "settings", make_settings(mock_mode=False))

        result = agent.generate_summary(
            user_id=1001, date_str="2026-07-30", records=RECORDS)

        assert stub_llm.requests == []
        assert "3975" in result.summary


class TestLlmClient:

    def test_should_retry_then_succeed(self, stub_llm):
        """429/5xx 应重试；这里让桩前两次失败、第三次成功。"""
        attempts = {"n": 0}

        def responder(path, body):
            attempts["n"] += 1
            if attempts["n"] < 3:
                return 500, {"error": {"message": "boom"}}
            return 200, {"choices": [{"message": {"role": "assistant", "content": "第三次成功"}}]}

        stub_llm._responder = responder
        client = DeepSeekClient(api_key="sk-test", base_url=stub_llm.base_url,
                                model="deepseek-chat", max_retries=2)
        assert client.chat_with_system("sys", "user") == "第三次成功"
        assert attempts["n"] == 3

    def test_should_not_retry_on_auth_error(self, stub_llm):
        """401（Key 错）重试没有意义，应立即失败，避免白白等待。"""
        attempts = {"n": 0}

        def responder(path, body):
            attempts["n"] += 1
            return 401, {"error": {"message": "invalid api key"}}

        stub_llm._responder = responder
        client = DeepSeekClient(api_key="sk-bad", base_url=stub_llm.base_url, max_retries=3)
        with pytest.raises(LLMError) as exc:
            client.chat_with_system("sys", "user")
        assert attempts["n"] == 1, "鉴权失败不该重试"
        assert "401" in str(exc.value)

    def test_empty_content_should_raise(self, stub_llm):
        """大模型返回空内容要当成错误，否则前端会拿到空总结。"""
        stub_llm.set_text("")
        client = DeepSeekClient(api_key="sk-test", base_url=stub_llm.base_url, max_retries=0)
        with pytest.raises(LLMError):
            client.chat_with_system("sys", "user")

    def test_api_key_must_never_leak_into_error(self, stub_llm):
        """错误信息里绝不能带 Key（日志会落盘，Key 泄漏是安全事故）。"""
        secret = "sk-super-secret-key-do-not-leak"
        stub_llm.set_status(500)
        client = DeepSeekClient(api_key=secret, base_url=stub_llm.base_url, max_retries=0)
        with pytest.raises(LLMError) as exc:
            client.chat_with_system("sys", "user")
        assert secret not in str(exc.value)

    def test_describe_masks_key(self):
        """describe() 用于健康检查，必须脱敏。"""
        client = DeepSeekClient(api_key="sk-1234567890abcdef", base_url="http://x", model="m")
        described = client.describe()
        assert described["configured"] is True
        assert "1234567890abcdef" not in str(described)

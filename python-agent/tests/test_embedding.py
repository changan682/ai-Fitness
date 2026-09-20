"""Embedding 接线与语义质量测试。

## 覆盖三件事

1. **百炼（DashScope）请求体正确性**：`dimensions=768` 是否显式传了（不传会拿到默认 1024 维，
   与规范的 768 不符）、分批是否按 10 条切（百炼单次上限 10，超了直接报错）、
   `index` 乱序时能否按序还原（否则知识条目与向量会错位）。
2. **维度不符要给出可读的错误**：规范硬性要求 768，若 provider 返回别的维度，
   必须在 Embedding 层就报出「哪个 provider 返回了几维、要求几维」，
   而不是等 Milvus 抛一句看不懂的 schema 错误。
3. **离线兜底不是随机数**：`hashing` 提供的是字符 n-gram 哈希向量，
   相似文本的余弦相似度必须显著高于无关文本 —— 否则「无 Key 时用兜底验证链路」就没有意义。
"""

from __future__ import annotations

import math

import pytest

from app.embedding import (
    DashScopeEmbedding,
    EmbeddingError,
    HashingEmbedding,
    SiliconFlowEmbedding,
    build_embedding,
)


def _cosine(a, b) -> float:
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(y * y for y in b))
    return dot / (na * nb) if na and nb else 0.0


class TestDashScopeRequestShape:

    def test_should_send_dimensions_and_batch(self, stub_embedding):
        """25 条文本应分 3 批（10+10+5），且每批都带 dimensions=768。"""
        stub_embedding.set_dim(768)
        client = DashScopeEmbedding(
            api_key="sk-test-fake", base_url=stub_embedding.base_url,
            model="text-embedding-v3", dim=768, batch_size=10)

        vectors = client.embed_documents([f"第{i}条知识内容" for i in range(25)])

        assert len(vectors) == 25
        assert all(len(v) == 768 for v in vectors)
        assert len(stub_embedding.requests) == 3, "应按 batch_size=10 分批"
        for req in stub_embedding.requests:
            assert req["body"]["dimensions"] == 768, \
                "必须显式传 dimensions，否则百炼返回默认 1024 维，与规范的 768 不符"
            assert req["body"]["model"] == "text-embedding-v3"
            assert len(req["body"]["input"]) <= 10, "百炼单次上限 10 条"
        # 各批条数：10 / 10 / 5
        assert [len(r["body"]["input"]) for r in stub_embedding.requests] == [10, 10, 5]

    def test_should_preserve_order_when_response_is_shuffled(self, stub_embedding):
        """响应里的 index 乱序时，必须按 index 还原顺序，否则向量会与知识条目错位。"""
        def responder(path, body):
            inputs = body.get("input") or []
            # 故意倒序返回
            data = [
                {"index": i, "embedding": [float(i)] * 768}
                for i in reversed(range(len(inputs)))
            ]
            return 200, {"data": data}

        stub_embedding._responder = responder
        client = DashScopeEmbedding(
            api_key="sk-test-fake", base_url=stub_embedding.base_url,
            model="text-embedding-v3", dim=768, batch_size=10)

        vectors = client.embed_documents(["a", "b", "c"])

        # index=0 的向量应以 0.0 开头（对应文本 "a"）
        assert vectors[0][0] == 0.0
        assert vectors[1][0] == 1.0
        assert vectors[2][0] == 2.0

    def test_empty_input_should_not_call_api(self, stub_embedding):
        client = DashScopeEmbedding(
            api_key="sk-test-fake", base_url=stub_embedding.base_url,
            model="text-embedding-v3", dim=768)
        assert client.embed_documents([]) == []
        assert stub_embedding.requests == []


class TestDimensionValidation:

    def test_wrong_dimension_should_raise_readable_error(self, stub_embedding):
        """provider 返回 1024 维时，错误信息必须说清「要求 768」这个规范约束。"""
        stub_embedding.set_dim(1024)
        client = DashScopeEmbedding(
            api_key="sk-test-fake", base_url=stub_embedding.base_url,
            model="text-embedding-v3", dim=768)

        with pytest.raises(EmbeddingError) as exc:
            client.embed_documents(["一段文本"])

        message = str(exc.value)
        assert "1024" in message and "768" in message
        assert "dashscope" in message, "要指明是哪个 provider 出的问题"

    def test_http_error_should_not_leak_key(self, stub_embedding):
        secret = "sk-secret-embedding-key"
        stub_embedding.set_status(401)
        client = DashScopeEmbedding(
            api_key=secret, base_url=stub_embedding.base_url,
            model="text-embedding-v3", dim=768)
        with pytest.raises(EmbeddingError) as exc:
            client.embed_documents(["文本"])
        assert secret not in str(exc.value)

    def test_missing_api_key_should_fail_fast(self):
        with pytest.raises(EmbeddingError):
            DashScopeEmbedding(api_key="", base_url="http://x", model="m", dim=768)


class TestHashingFallback:

    def test_similar_text_should_score_higher(self):
        """兜底实现必须保留词法相似度，否则「无 Key 时验证 RAG」就失去意义。"""
        embedder = HashingEmbedding(dim=768)

        query = embedder.embed_query("深蹲时膝盖内扣怎么纠正")
        similar = embedder.embed_query("深蹲膝盖内扣的成因与纠正方法")
        unrelated = embedder.embed_query("增肌期每天应该吃多少蛋白质")

        assert _cosine(query, similar) > _cosine(query, unrelated) * 2, \
            "相似文本的相似度应显著高于无关文本"

    def test_deterministic_and_normalized(self):
        """同一文本必须得到完全相同的向量（否则建库与检索不可复现），且已 L2 归一化。"""
        embedder = HashingEmbedding(dim=768)
        v1 = embedder.embed_query("杠铃卧推标准姿势")
        v2 = embedder.embed_query("杠铃卧推标准姿势")

        assert v1 == v2
        norm = math.sqrt(sum(x * x for x in v1))
        assert abs(norm - 1.0) < 1e-9, f"应为单位向量，实际模长 {norm}"

    def test_whitespace_should_not_change_vector(self):
        """空白不应影响结果（中文文本里的换行/空格是排版噪声）。"""
        embedder = HashingEmbedding(dim=768)
        assert embedder.embed_query("深蹲 膝盖\n内扣") == embedder.embed_query("深蹲膝盖内扣")

    def test_empty_text_should_not_crash(self):
        embedder = HashingEmbedding(dim=768)
        vector = embedder.embed_query("")
        assert len(vector) == 768
        assert any(x != 0 for x in vector), "空文本也要返回有效向量，避免下游除零"

    def test_relevance_threshold_is_calibrated_per_provider(self):
        """两种 provider 的余弦分布不同，阈值必须各自校准 —— 否则会一直误报「未检索到相关资料」。"""
        assert HashingEmbedding(dim=768).relevance_threshold < 0.3, \
            "字符哈希的余弦天然偏低，阈值必须相应下调"
        assert DashScopeEmbedding(
            api_key="k", base_url="http://x", model="m", dim=768
        ).relevance_threshold >= 0.7, "真实语义模型应用常规阈值"


class TestProviderFactory:

    def test_auto_should_pick_dashscope_when_key_present(self, make_settings):
        settings = make_settings(embedding_provider="auto", dashscope_api_key="sk-x")
        assert settings.resolved_embedding_provider == "dashscope"
        assert isinstance(build_embedding(settings), DashScopeEmbedding)

    def test_auto_should_prefer_dashscope_over_siliconflow(self, make_settings):
        settings = make_settings(embedding_provider="auto",
                                 dashscope_api_key="sk-x", siliconflow_api_key="sk-y")
        assert settings.resolved_embedding_provider == "dashscope"

    def test_auto_should_fall_back_to_hashing_without_any_key(self, make_settings):
        settings = make_settings(embedding_provider="auto",
                                 dashscope_api_key="", siliconflow_api_key="")
        assert settings.resolved_embedding_provider == "hashing"
        assert isinstance(build_embedding(settings), HashingEmbedding)
        assert settings.embedding_api_configured is False

    def test_explicit_provider_should_win_over_auto(self, make_settings):
        settings = make_settings(embedding_provider="siliconflow", dashscope_api_key="sk-x",
                                 siliconflow_api_key="sk-y")
        assert settings.resolved_embedding_provider == "siliconflow"
        assert isinstance(build_embedding(settings), SiliconFlowEmbedding)

    def test_unknown_provider_should_raise_with_options(self, make_settings):
        settings = make_settings(embedding_provider="openai")
        with pytest.raises(EmbeddingError) as exc:
            build_embedding(settings)
        assert "openai" in str(exc.value)
        assert "dashscope" in str(exc.value), "错误信息应列出可选值"

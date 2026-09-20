"""知识库建造清单与种子数据校验。

## 为什么测「Embedding 一致性」

向量检索的正确性依赖「库中向量」与「查询向量」在**同一个语义空间**。
用 A 模型建库、用 B 模型查询时，余弦相似度算出来的是无意义的数字 ——
检索会「成功返回」一批毫不相关的知识，再被大模型自信地引用。
**这种失败比检索直接报错更难发现**：接口 200、有来源、有像模像样的回答，
只有人去核对内容才会发现全都不相关。

因此知识库导入成功时会落盘一份「建造清单」（provider/model/dim），
查询前比对，不一致就明确失败并降级为纯 LLM 回答。
这组测试锁住这个防护行为。
"""

from __future__ import annotations

import json

import pytest

from app.knowledge_init import (
    EXPECTED_CATEGORIES,
    MAX_CONTENT_CHARS,
    MIN_CONTENT_CHARS,
    SEED_FILE,
    check_embedding_consistency,
    load_seed_entries,
    read_manifest,
    summarize_entries,
    write_manifest,
)


class TestManifest:

    def test_round_trip(self, tmp_manifest_file):
        write_manifest(provider="dashscope", model="text-embedding-v3", dim=768,
                       collection="fitness_knowledge", documents=200,
                       path=tmp_manifest_file)

        manifest = read_manifest(tmp_manifest_file)
        assert manifest is not None
        assert manifest["provider"] == "dashscope"
        assert manifest["model"] == "text-embedding-v3"
        assert manifest["dim"] == 768
        assert manifest["documents"] == 200
        assert manifest["built_at"]

    def test_missing_manifest_is_not_an_error(self, tmp_manifest_file):
        assert read_manifest(tmp_manifest_file) is None

    def test_corrupted_manifest_is_not_an_error(self, tmp_manifest_file):
        """清单损坏不该让服务起不来，只是失去校验能力。"""
        tmp_manifest_file.write_text("{ this is not json", encoding="utf-8")
        assert read_manifest(tmp_manifest_file) is None


class TestEmbeddingConsistency:

    def test_matching_provider_passes(self, tmp_manifest_file):
        write_manifest(provider="hashing", model="x", dim=768,
                       collection="c", documents=1, path=tmp_manifest_file)
        assert check_embedding_consistency("hashing", path=tmp_manifest_file) is None

    def test_mismatched_provider_is_rejected_with_actionable_message(self, tmp_manifest_file):
        """换模型但没重建知识库时，必须明确拒绝检索并给出修复动作。"""
        write_manifest(provider="hashing", model="char-ngram", dim=768,
                       collection="c", documents=200, path=tmp_manifest_file)

        message = check_embedding_consistency("dashscope", path=tmp_manifest_file)

        assert message is not None
        assert "hashing" in message and "dashscope" in message, "要说清是哪两个不一致"
        assert "FORCE_RELOAD_KNOWLEDGE" in message, "要给出可执行的修复动作"

    def test_missing_manifest_does_not_block(self, tmp_manifest_file):
        """没有清单（更早版本建的库）时不阻断，避免把升级后的服务直接卡死。"""
        assert check_embedding_consistency("dashscope", path=tmp_manifest_file) is None

    def test_manifest_without_provider_does_not_block(self, tmp_manifest_file):
        tmp_manifest_file.write_text(json.dumps({"collection": "c"}), encoding="utf-8")
        assert check_embedding_consistency("dashscope", path=tmp_manifest_file) is None


class TestSeedKnowledge:
    """规范强制：200 条以上、5 大分类、content 长度 150-500、UTF-8 无 BOM。"""

    def test_seed_file_meets_spec(self):
        entries = load_seed_entries()

        assert len(entries) >= 200, f"规范要求 200 条以上，实际 {len(entries)}"

        counts = summarize_entries(entries)
        for category, expected in EXPECTED_CATEGORIES.items():
            assert counts.get(category, 0) >= expected, \
                f"{category} 应有 {expected} 条以上，实际 {counts.get(category, 0)}"

        for entry in entries:
            length = len(entry["content"])
            assert MIN_CONTENT_CHARS <= length <= MAX_CONTENT_CHARS, \
                f"{entry['title']} 的 content 长度 {length} 越界"
            assert entry["source"].strip(), f"{entry['title']} 缺少 source"
            assert entry["title"].strip()

    def test_titles_are_unique(self):
        """标题重复会导致知识库出现内容雷同的条目，检索时互相挤占 Top-K。"""
        entries = load_seed_entries()
        titles = [e["title"] for e in entries]
        duplicates = {t for t in titles if titles.count(t) > 1}
        assert not duplicates, f"发现重复标题: {sorted(duplicates)[:5]}"

    def test_no_bom(self):
        """带 BOM 的 JSON 会让部分解析器在第一个字符处报错。"""
        raw = SEED_FILE.read_bytes()
        assert not raw.startswith(b"\xef\xbb\xbf"), "种子文件不应带 UTF-8 BOM"

    def test_invalid_entry_is_reported_with_index(self, tmp_manifest_file):
        """校验失败要指出是第几条、什么原因，而不是抛一句模糊的异常。"""
        tmp_manifest_file.write_text(json.dumps([
            {"category": "动作要领", "title": "过短的条目", "content": "太短了", "source": "x"},
        ], ensure_ascii=False), encoding="utf-8")

        with pytest.raises(Exception) as exc:
            load_seed_entries(tmp_manifest_file)

        message = str(exc.value)
        assert "content 长度" in message
        assert "0" in message

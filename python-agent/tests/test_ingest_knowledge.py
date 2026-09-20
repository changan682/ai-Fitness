"""``scripts/ingest_knowledge.py`` 与健康检查 last_updated 的纯逻辑测试。

## 为什么这组测试不连 Milvus

入库脚本真正跑起来要 Milvus + 真实 Embedding API，属于「集成验证」；
而**最该被锁住的是校验与去重这类纯逻辑**，它们决定了坏数据会不会被写进库：

1. **分类白名单 / 长度约束**：一条 30 字的「知识」进了库，Embedding 出来是个
   语义模糊的向量，检索时会被当成正经来源引用给用户 —— 事后极难发现是谁写坏的。
   所以每条限制都要有测试，并且**报错必须指出第几条、哪个字段**（只说「格式错误」
   等于让使用者对着几百行的 JSON 自己找）。
2. **去重用的过滤表达式转义**：标题里带双引号时，未转义的表达式会破坏 Milvus
   过滤语法（轻则报错，重则匹配到别的条目）。转义顺序也有讲究（反斜杠必须先转）。
3. **manifest 时间格式化 / 兜底**：``/agent/v1/knowledge/health`` 的 ``last_updated``
   读的就是 ``data/.kb_manifest.json`` 的 ``built_at``。清单缺失或损坏时**必须**退化成
   null 而不是抛异常 —— 健康检查接口不能因为一个附带信息文件读不了就 500。

Milvus 相关的部分用 ``unittest.mock`` 替身验证「调用了什么、传了什么过滤条件」，
不在本文件里依赖真实服务。
"""

from __future__ import annotations

import json
import re
import sys
import uuid
from datetime import datetime
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

BASE_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(BASE_DIR))

from app.knowledge_init import (  # noqa: E402
    ALLOWED_CATEGORIES,
    EXPECTED_CATEGORIES,
    INGEST_MAX_CONTENT_CHARS,
    INGEST_MAX_TITLE_CHARS,
    INGEST_MIN_CONTENT_CHARS,
    INGEST_MIN_TITLE_CHARS,
    load_seed_entries,
)
from app.milvus_client import (  # noqa: E402
    MilvusKnowledgeStore,
    MilvusUnavailableError,
    format_health_timestamp,
    read_manifest_built_at,
)
from scripts.ingest_knowledge import (  # noqa: E402
    IngestError,
    collect_entries,
    duplicate_filter,
    escape_filter_value,
    find_existing,
    load_entries_file,
    main,
    normalize_entry,
    print_distribution,
    redact_secrets,
    validate_entries,
    validate_entry,
)

#: 一条长度合规的正文（150 字左右，离 50/2000 两个边界都远，不会因改动而误判）
OK_CONTENT = (
    "深蹲时保持核心收紧、脊柱中立位，膝盖方向与脚尖一致，下蹲到大腿与地面平行，"
    "起身时脚掌整体蹬地而不是脚尖发力。常见错误是膝盖内扣与弓腰，"
    "前者会增加半月板剪切力，后者会让腰椎承受数倍压力。"
)


def make_entry(**overrides) -> dict:
    """造一条合格条目，按需覆盖某个字段（便于单字段测试）。"""
    entry = {
        "category": "动作要领",
        "title": "深蹲核心控制要点",
        "content": OK_CONTENT,
        "source": "NSCA力量训练指南",
    }
    entry.update(overrides)
    return entry


# ======================================================================
# 临时文件（不用 pytest 的 tmp_path：本项目开发沙箱对系统临时目录无写权限）
# ======================================================================

@pytest.fixture
def write_temp_file():
    """写一个工作区内的临时文件并登记清理。

    刻意不用 pytest 自带的 ``tmp_path``：它落在系统临时目录，而本项目的开发沙箱
    对该目录没有写权限，测试会报 PermissionError 而不是真正的断言失败（环境噪声）。
    与 ``tests/conftest.py`` 里 ``tmp_manifest_file`` 的处理方式一致。
    """
    created = []

    def _write(text: str, *, suffix: str = ".json", encoding: str = "utf-8") -> Path:
        tmp_dir = Path(__file__).resolve().parent / ".tmp"
        tmp_dir.mkdir(parents=True, exist_ok=True)
        path = tmp_dir / f"ingest-{uuid.uuid4().hex}{suffix}"
        path.write_text(text, encoding=encoding)
        created.append(path)
        return path

    yield _write

    for path in created:
        try:
            path.unlink(missing_ok=True)
        except OSError:
            pass


@pytest.fixture
def write_json(write_temp_file):
    """把 Python 对象写成 JSON 临时文件（返回路径）。"""

    def _write(payload, **kwargs) -> Path:
        return write_temp_file(json.dumps(payload, ensure_ascii=False), **kwargs)

    return _write


# ======================================================================
# 分类白名单
# ======================================================================

class TestCategoryWhitelist:
    """规范（提示词.txt 第 235-240、298 行）只允许 5 大分类。"""

    def test_whitelist_is_exactly_the_five_spec_categories(self):
        assert set(ALLOWED_CATEGORIES) == {
            "动作要领", "营养饮食", "恢复与伤病", "训练计划", "补剂科普",
        }

    def test_whitelist_matches_expected_categories(self):
        """``ALLOWED_CATEGORIES``（白名单）与 ``EXPECTED_CATEGORIES``（建议条数）必须同源，
        否则会出现「校验放行的分类没有条数指标」这种悄悄漂移。"""
        assert set(ALLOWED_CATEGORIES) == set(EXPECTED_CATEGORIES)

    @pytest.mark.parametrize("category", ALLOWED_CATEGORIES)
    def test_every_whitelisted_category_passes(self, category):
        assert validate_entry(make_entry(category=category), 0) == []


# ======================================================================
# 条目校验
# ======================================================================

class TestValidateEntry:

    def test_valid_entry_has_no_problem(self):
        assert validate_entry(make_entry(), 0) == []

    def test_content_length_constants_match_pydantic_model(self):
        """与 ``models.KnowledgeEntry``（title 1-200、content 50-2000）严格一致：
        接口校验与脚本校验口径不同的话，会出现「脚本放行、接口 422」的诡异现象。"""
        assert (INGEST_MIN_TITLE_CHARS, INGEST_MAX_TITLE_CHARS) == (1, 200)
        assert (INGEST_MIN_CONTENT_CHARS, INGEST_MAX_CONTENT_CHARS) == (50, 2000)
        assert INGEST_MIN_CONTENT_CHARS <= len(OK_CONTENT) <= INGEST_MAX_CONTENT_CHARS

    def test_category_outside_whitelist_is_reported_with_index_and_field(self):
        problems = validate_entry(make_entry(category="增肌食谱"), 1)

        assert len(problems) == 1
        message = problems[0]
        assert message.startswith("第2条"), "要说清是第几条（1 基，方便对着文件数）"
        assert "category" in message, "要说清是哪个字段"
        assert "增肌食谱" in message, "要带上写错的值"
        assert "动作要领" in message, "要给出合法取值，而不是只说「非法」"

    def test_missing_field_is_reported(self):
        entry = make_entry()
        del entry["source"]

        problems = validate_entry(entry, 0)

        assert len(problems) == 1
        assert "source" in problems[0] and "缺少字段" in problems[0]

    def test_missing_field_short_circuits_other_checks(self):
        """缺字段时只报缺字段：否则一条残条目会连带刷出「长度 0 越界」等噪声。"""
        problems = validate_entry({"category": "动作要领"}, 0)
        assert len(problems) == 1

    def test_non_string_field_is_reported(self):
        problems = validate_entry(make_entry(title=12345), 0)

        assert len(problems) == 1
        assert "title" in problems[0] and "必须是字符串" in problems[0]

    @pytest.mark.parametrize("bad", [None, "纯文本", 42, ["动作要领"]])
    def test_non_object_entry_is_reported(self, bad):
        problems = validate_entry(bad, 2)

        assert len(problems) == 1
        assert problems[0].startswith("第3条")
        assert "不是 JSON 对象" in problems[0]

    def test_blank_title_is_rejected(self):
        problems = validate_entry(make_entry(title="   "), 0)
        assert any("title 长度 0" in p for p in problems)

    def test_too_long_title_is_rejected(self):
        problems = validate_entry(make_entry(title="长" * 201), 0)

        assert any("title 长度 201" in p and "1-200" in p for p in problems)

    def test_short_content_is_rejected(self):
        problems = validate_entry(make_entry(content="太短了"), 0)

        assert any("content 长度 3" in p and "50-2000" in p for p in problems)

    def test_too_long_content_is_rejected(self):
        """上限 2000 字符并非随便定的：Milvus schema 里 content 是 VARCHAR(8192 字节)，
        中文最多 4 字节/字符 → 2000 字符正好贴着上限而不溢出。"""
        problems = validate_entry(make_entry(content="长" * 2001), 0)

        assert any("content 长度 2001" in p for p in problems)

    def test_boundary_lengths_are_accepted(self):
        assert validate_entry(make_entry(content="字" * INGEST_MIN_CONTENT_CHARS), 0) == []
        assert validate_entry(make_entry(content="字" * INGEST_MAX_CONTENT_CHARS), 0) == []
        assert validate_entry(make_entry(title="标" * INGEST_MAX_TITLE_CHARS), 0) == []

    def test_blank_source_is_rejected(self):
        problems = validate_entry(make_entry(source="   "), 0)

        assert any("source 不能为空" in p for p in problems)

    def test_too_long_source_is_rejected(self):
        """source 在 Milvus 里是 VARCHAR(1024 字节)，超长会在 insert 时才报难懂的 schema 错；
        这里提前拦下，报错里点明是 source 字段。"""
        problems = validate_entry(make_entry(source="源" * 201), 0)

        assert any("source" in p and "上限" in p for p in problems)

    def test_whitespace_is_stripped_before_length_check(self):
        """校验口径必须与实际写库的值一致（写库前会 strip），否则会放行一条
        「strip 后不足 50 字」的正文。"""
        assert any("content 长度 49" in p
                   for p in validate_entry(make_entry(content=" " * 2 + "字" * 49), 0))


class TestValidateEntries:

    def test_batch_reports_each_bad_entry(self):
        problems = validate_entries([
            make_entry(),
            make_entry(category="玄学"),
            make_entry(content="短"),
        ])

        assert any(p.startswith("第2条") for p in problems)
        assert any(p.startswith("第3条") for p in problems)
        assert not any(p.startswith("第1条") for p in problems)

    def test_duplicate_category_and_title_in_batch_is_reported(self):
        problems = validate_entries([make_entry(), make_entry()])

        assert len(problems) == 1
        assert "第2条" in problems[0] and "第1条" in problems[0]
        assert "重复" in problems[0]

    def test_same_title_in_different_categories_is_allowed(self):
        """标题相同但分类不同是合法的：Milvus 里没有唯一索引，去重键必须是两字段的组合。"""
        problems = validate_entries([
            make_entry(category="动作要领", title="训练频率建议"),
            make_entry(category="训练计划", title="训练频率建议"),
        ])

        assert problems == []

    def test_non_object_entries_are_skipped_in_dedup_step(self):
        """一条坏数据（不是对象）不该让批内查重再抛一次 KeyError。"""
        problems = validate_entries([42, make_entry()])

        assert len(problems) == 1
        assert "不是 JSON 对象" in problems[0]


class TestNormalizeEntry:

    def test_strips_whitespace(self):
        normalized = normalize_entry(make_entry(title="  深蹲  ", source=" NSCA "))

        assert normalized["title"] == "深蹲"
        assert normalized["source"] == "NSCA"

    def test_drops_extra_fields(self):
        """Milvus schema 是 enable_dynamic_field=False，多余字段会在 insert 时报错。"""
        normalized = normalize_entry(make_entry(extra="不该进库", created_at=1))

        assert set(normalized) == {"category", "title", "content", "source"}


# ======================================================================
# 去重过滤表达式
# ======================================================================

class TestDuplicateFilter:

    def test_basic_expression(self):
        assert duplicate_filter("动作要领", "深蹲") == \
            'category == "动作要领" and title == "深蹲"'

    def test_double_quote_is_escaped(self):
        """标题带引号时不转义会破坏 Milvus 过滤语法（表达式注入的老问题）。"""
        expression = duplicate_filter("动作要领", '他说"深蹲"很好')

        assert expression == 'category == "动作要领" and title == "他说\\"深蹲\\"很好"'

    def test_backslash_is_escaped_before_quote(self):
        """转义顺序必须是「先反斜杠、后引号」：反过来的话引号转义产生的 \\" 会被
        第二次替换再次加杠，表达式直接语法错误。"""
        assert escape_filter_value('a\\"b') == 'a\\\\\\"b'
        assert escape_filter_value("C:\\path") == "C:\\\\path"

    def test_quotes_in_either_field_are_escaped(self):
        expression = duplicate_filter('动作"要领', '深蹲"')

        assert 'category == "动作\\"要领"' in expression
        assert 'title == "深蹲\\""' in expression


class TestFindExisting:
    """去重要真的走 Milvus 的 query + 标量过滤（这里用替身验证「传了什么」）。"""

    def _store_with(self, query_result):
        store = MilvusKnowledgeStore(collection="fitness_knowledge")
        client = MagicMock()
        client.query.return_value = query_result
        return store, client

    def test_returns_entries_already_in_collection(self):
        entry = make_entry()
        store, client = self._store_with([{"category": "动作要领", "title": "深蹲核心控制要点"}])

        with patch.object(store, "client", return_value=client):
            existing = find_existing(store, [entry])

        assert existing == [entry]
        # 必须按 (category, title) 两个字段查，并且带上标量过滤表达式
        assert client.query.call_count == 1
        assert client.query.call_args.kwargs["filter"] == \
            'category == "动作要领" and title == "深蹲核心控制要点"'
        assert client.query.call_args.kwargs["collection_name"] == "fitness_knowledge"

    def test_empty_result_means_not_existing(self):
        entry = make_entry()
        store, client = self._store_with([])

        with patch.object(store, "client", return_value=client):
            assert find_existing(store, [entry]) == []

    def test_quotes_in_title_do_not_break_the_filter(self):
        entry = make_entry(title='带"引号"的标题')
        store, client = self._store_with([])

        with patch.object(store, "client", return_value=client):
            find_existing(store, [entry])

        assert 'title == "带\\"引号\\"的标题"' in client.query.call_args.kwargs["filter"]


# ======================================================================
# manifest 时间（健康检查 last_updated）
# ======================================================================

ISO_RE = re.compile(r"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$")


class TestFormatHealthTimestamp:
    """格式必须是规范 models.py 示例的 ``2026-07-15 10:00:00``。"""

    def test_iso_with_t_separator(self):
        assert format_health_timestamp("2026-09-14T19:34:35") == "2026-09-14 19:34:35"

    def test_already_formatted_value_is_kept(self):
        assert format_health_timestamp("2026-09-14 19:34:35") == "2026-09-14 19:34:35"

    def test_microseconds_are_dropped(self):
        """规范示例没有微秒尾巴，漏给前端会显示成一串无意义数字。"""
        assert format_health_timestamp("2026-09-14T19:34:35.123456") == "2026-09-14 19:34:35"

    def test_datetime_object(self):
        assert format_health_timestamp(datetime(2026, 9, 14, 19, 34, 35)) == \
            "2026-09-14 19:34:35"

    def test_timezone_aware_value_returns_local_wall_time(self):
        """带时区的写法统一换算成本地时间：清单由 ``datetime.now()`` 写入，本就是本地时间。
        不断言具体小时数（那会依赖跑测试的机器时区），只断言格式与可解析性。"""
        text = format_health_timestamp("2026-09-14T19:34:35Z")

        assert text is not None and ISO_RE.match(text)
        assert datetime.strptime(text, "%Y-%m-%d %H:%M:%S")

    @pytest.mark.parametrize("bad", [
        None, "", "   ", "昨天", "not-a-time", float("nan"), {"a": 1}, ["2026-09-14"],
        "2026-13-45 99:99:99",
    ])
    def test_unrecognized_values_return_none_without_raising(self, bad):
        assert format_health_timestamp(bad) is None


class TestReadManifestBuiltAt:
    """清单缺失/损坏/字段缺失/换过 Collection —— 一律返回 None，绝不抛异常。"""

    def test_reads_built_at_from_real_manifest(self, write_json):
        path = write_json({
            "collection": "fitness_knowledge",
            "provider": "dashscope",
            "built_at": "2026-09-14T19:34:35",
        })

        assert read_manifest_built_at(path) == "2026-09-14 19:34:35"

    def test_missing_file_returns_none(self):
        missing = Path(__file__).resolve().parent / ".tmp" / f"nope-{uuid.uuid4().hex}.json"

        assert read_manifest_built_at(missing) is None

    def test_corrupted_json_returns_none(self, write_temp_file):
        path = write_temp_file("{ 这不是 JSON")

        assert read_manifest_built_at(path) is None

    def test_top_level_array_returns_none(self, write_json):
        assert read_manifest_built_at(write_json([{"built_at": "2026-09-14T19:34:35"}])) is None

    def test_missing_built_at_field_returns_none(self, write_json):
        assert read_manifest_built_at(write_json({"provider": "dashscope"})) is None

    def test_unparseable_built_at_returns_none(self, write_json):
        assert read_manifest_built_at(write_json({"built_at": "上周"}) ) is None

    def test_bom_tolerant(self, write_temp_file):
        """Windows 编辑器常写入 BOM，读取必须用 utf-8-sig 兼容。"""
        raw = '\ufeff{"built_at": "2026-09-14T19:34:35"}'
        path = write_temp_file(raw)

        assert read_manifest_built_at(path) == "2026-09-14 19:34:35"

    def test_other_collection_returns_none(self, write_json):
        """清单记的是别的 Collection（改过 MILVUS_COLLECTION）时，它的构建时间对本库无意义。"""
        path = write_json({"collection": "other_kb", "built_at": "2026-09-14T19:34:35"})

        assert read_manifest_built_at(path, collection="fitness_knowledge") is None
        assert read_manifest_built_at(path, collection="other_kb") == "2026-09-14 19:34:35"

    def test_manifest_without_collection_field_is_still_usable(self, write_json):
        """老清单可能没有 collection 字段 —— 不能因此把 last_updated 变成永远 null。"""
        path = write_json({"built_at": "2026-09-14T19:34:35"})

        assert read_manifest_built_at(path, collection="fitness_knowledge") == \
            "2026-09-14 19:34:35"


class TestHealthUsesManifest:
    """``MilvusKnowledgeStore.health()`` 的 last_updated 与「绝不抛异常」两条契约。"""

    def _patched_store(self, store):
        client = MagicMock()
        client.list_collections.return_value = [store.collection]
        return [
            patch.object(store, "client", return_value=client),
            patch.object(store, "count", return_value=200),
            patch.object(store, "_describe_index",
                         return_value="IVF_FLAT(COSINE, nlist=16)"),
        ]

    def test_last_updated_comes_from_manifest(self, write_json):
        path = write_json({"collection": "fitness_knowledge",
                           "built_at": "2026-09-14T19:34:35"})
        store = MilvusKnowledgeStore(collection="fitness_knowledge")

        with patch("app.milvus_client.manifest_path", return_value=path):
            patchers = self._patched_store(store)
            for patcher in patchers:
                patcher.start()
            try:
                info = store.health()
            finally:
                for patcher in patchers:
                    patcher.stop()

        assert info["milvus_connected"] is True
        assert info["total_documents"] == 200
        assert info["last_updated"] == "2026-09-14 19:34:35"

    def test_last_updated_is_null_when_manifest_is_corrupted(self, write_temp_file):
        path = write_temp_file("{ 坏掉的清单")
        store = MilvusKnowledgeStore(collection="fitness_knowledge")

        with patch("app.milvus_client.manifest_path", return_value=path):
            patchers = self._patched_store(store)
            for patcher in patchers:
                patcher.start()
            try:
                info = store.health()
            finally:
                for patcher in patchers:
                    patcher.stop()

        assert info["last_updated"] is None
        assert info["milvus_connected"] is True, "清单坏了不该影响 Milvus 连接状态"

    def test_milvus_down_does_not_raise_and_keeps_manifest_time(self, write_json):
        """Milvus 连不上时：milvus_connected=False、error 有内容，但**不抛异常**；
        last_updated 仍如实给出（它描述的是「手里这份库有多旧」，与当前能否连上无关）。"""
        path = write_json({"collection": "fitness_knowledge",
                           "built_at": "2026-09-14T19:34:35"})
        store = MilvusKnowledgeStore(collection="fitness_knowledge")

        with patch("app.milvus_client.manifest_path", return_value=path), \
                patch.object(store, "client",
                             side_effect=MilvusUnavailableError("连不上 Milvus")):
            info = store.health()

        assert info["milvus_connected"] is False
        assert info["total_documents"] is None
        assert "连不上 Milvus" in info["error"]
        assert info["last_updated"] == "2026-09-14 19:34:35"


# ======================================================================
# 文件加载与脱敏
# ======================================================================

class TestLoadEntriesFile:

    def test_loads_valid_file(self, write_json):
        entries = load_entries_file(write_json([make_entry()]))

        assert len(entries) == 1 and entries[0]["title"] == "深蹲核心控制要点"

    def test_bom_tolerant(self, write_temp_file):
        path = write_temp_file("\ufeff" + json.dumps([make_entry()], ensure_ascii=False))

        assert len(load_entries_file(path)) == 1

    def test_missing_file_raises_readable_error(self):
        with pytest.raises(IngestError) as exc:
            load_entries_file(Path("definitely-not-here.json"))

        assert "文件不存在" in str(exc.value)

    def test_directory_is_rejected(self):
        with pytest.raises(IngestError) as exc:
            load_entries_file(Path(__file__).resolve().parent)

        assert "目录" in str(exc.value)

    def test_broken_json_reports_position(self, write_temp_file):
        with pytest.raises(IngestError) as exc:
            load_entries_file(write_temp_file('[\n {"category": 动作要领}\n]'))

        message = str(exc.value)
        assert "不是合法 JSON" in message
        assert "行" in message, "要指出出错位置，方便直接跳过去改"

    def test_top_level_must_be_array(self, write_json):
        with pytest.raises(IngestError) as exc:
            load_entries_file(write_json({"category": "动作要领"}))

        assert "顶层必须是数组" in str(exc.value)

    def test_empty_array_is_rejected(self, write_json):
        with pytest.raises(IngestError) as exc:
            load_entries_file(write_json([]))

        assert "没有任何条目" in str(exc.value)


class TestRedactSecrets:

    def test_configured_key_is_masked(self):
        text = "调用失败: Authorization Bearer sk-abcdef1234567890"

        assert redact_secrets(text, ["sk-abcdef1234567890"]) == \
            "调用失败: Authorization Bearer sk-****7890"

    def test_multiple_keys_are_all_masked(self):
        text = "a=sk-aaaaaaaaaaaa bb=sk-bbbbbbbbbbbb"
        masked = redact_secrets(text, ["sk-aaaaaaaaaaaa", "sk-bbbbbbbbbbbb"])

        assert "sk-aaaaaaaaaaaa" not in masked
        assert "sk-bbbbbbbbbbbb" not in masked

    def test_short_values_are_left_alone(self):
        """过短的值（如 "abc"）做全局替换会误伤正常文本。"""
        assert redact_secrets("abc 是正常文字", ["abc"]) == "abc 是正常文字"

    def test_none_values_do_not_break(self):
        assert redact_secrets("普通文本", [None, ""]) == "普通文本"


# ======================================================================
# collect_entries：种子 + 自定义的编排
# ======================================================================

class TestCollectEntries:

    def test_custom_file_only(self, write_json):
        entries, problems = collect_entries(write_json([make_entry()]), include_seed=False)

        assert problems == []
        assert len(entries) == 1

    def test_bad_entry_is_reported_and_excluded_from_entries(self, write_json):
        """有坏条目时不能把「干净的那部分」交给调用方去写：宁可整体不写，
        也不要出现「文件里有 3 条，实际只入库了 2 条」这种需要用户自己去发现的状态。"""
        entries, problems = collect_entries(
            write_json([make_entry(), make_entry(category="玄学")]), include_seed=False)

        assert len(problems) == 1
        assert entries == []

    def test_seed_plus_custom_counts(self, write_json):
        entries, problems = collect_entries(write_json([make_entry()]), include_seed=True)

        assert problems == []
        assert len(entries) >= 201, "种子 200 条 + 自定义 1 条"

    def test_custom_entry_clashing_with_seed_is_reported(self, write_json):
        """自定义条目撞了种子标题时，重建会写入两条同名知识，检索时互相挤占 Top-K。

        撞车样本直接从真实种子里取，而不是把标题硬编码进测试：
        种子文件是会被重新生成的（scripts/merge_seed_parts.py），
        硬编码的标题一旦对不上，这个测试就会**静默失效**（变成一条永远不会撞车的用例）。
        """
        seed_entry = load_seed_entries()[0]
        clash = make_entry(category=seed_entry["category"], title=seed_entry["title"])

        entries, problems = collect_entries(write_json([clash]), include_seed=True)

        assert any("与种子数据撞了" in p for p in problems)


# ======================================================================
# 退出码契约（不连 Milvus 的几条分支）
# ======================================================================

class TestExitCodes:

    def test_no_mode_returns_nonzero(self, capsys):
        assert main([]) == 1
        assert "请至少指定一种模式" in capsys.readouterr().out

    def test_stats_conflicts_with_other_modes(self, capsys):
        assert main(["--stats", "--dry-run"]) == 1
        assert "不能与" in capsys.readouterr().out

    def test_dry_run_with_valid_file_returns_zero(self, write_json, capsys):
        assert main(["--file", str(write_json([make_entry()])), "--dry-run"]) == 0
        assert "校验通过" in capsys.readouterr().out

    def test_dry_run_with_invalid_file_returns_nonzero(self, write_json, capsys):
        exit_code = main(["--file", str(write_json([make_entry(content="短")])), "--dry-run"])

        assert exit_code == 1
        assert "校验未通过" in capsys.readouterr().out

    def test_dry_run_rebuild_does_not_touch_milvus(self, capsys):
        """--rebuild --dry-run 也必须完全不连 Milvus（否则「先看看会写多少条」就没法用了）。"""
        with patch("scripts.ingest_knowledge.build_store") as build_store_mock:
            exit_code = main(["--rebuild", "--dry-run"])

        assert exit_code == 0
        build_store_mock.assert_not_called()
        assert "校验通过" in capsys.readouterr().out


class TestPrintDistribution:

    def test_unknown_category_is_flagged_but_does_not_crash(self, capsys):
        """历史遗留的分类（早期版本写进去的）不能让统计功能直接崩。"""
        print_distribution({"动作要领": 3, "远古分类": 1})

        output = capsys.readouterr().out
        assert "动作要领" in output
        assert "白名单外" in output

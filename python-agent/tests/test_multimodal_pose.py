"""姿态评估的**真实多模态链路**测试（第 6 周，通义千问 VL）。

与 ``test_pose_input_validation.py`` 的分工：

- 那个文件测入参校验 + 离线实现的确定性（强制 ``MOCK_MODE=true``，不联网）；
- 本文件测真实链路，**把多模态客户端整体打桩**，因此既能断言「请求发得对不对」，
  也不会有网络抖动、不会真的花钱。

覆盖的关键点：
1. 真实模型会把 JSON 包在 markdown 围栏里、或在末尾多写一个花括号（实测 qwen-vl-max
   就照抄了 Prompt 示例里的 ``}}``）—— 解析器必须扛住这些脏输出；
2. ``score_level`` 一律本地派生，不采信模型自报的等级；
3. 模型说「照片无法判断」时**不能返回 0 分**（会被前端渲染成「需改进」，
   与「无法判断」自相矛盾），要按入参问题引导用户换照片；
4. 模型真的挂了（返回非 JSON / 没有 score）→ 抛 ``MultimodalError``，
   由 Java 走规范第十一章第 7 条的兜底文案。
"""

from __future__ import annotations

import base64
import json

import pytest

from app import agent
from app import multimodal
from app.multimodal import MultimodalError, QwenVLClient, sniff_image_mime
from app.utils import AgentInputError, extract_json_object

def _make_png(size: int = 64, rgb=(220, 30, 30)) -> bytes:
    """生成一张纯色 PNG（VL 要求宽高 > 10px，1x1 会被模型直接拒绝）。

    手写 PNG 字节而不依赖 Pillow：测试环境不保证装了图像库，
    而这几十行能保证「测试里用的确实是一张模型愿意接受的合法图片」。
    """
    import struct
    import zlib

    raw = b"".join(b"\x00" + bytes(rgb) * size for _ in range(size))

    def chunk(tag: bytes, data: bytes) -> bytes:
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    ihdr = struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


PNG_64 = _make_png()
IMAGE_B64 = base64.b64encode(PNG_64).decode("ascii")


def _pose_json(score=78, issues=None, suggestions=None, good_points=None, wrap=None):
    """按 Prompt 约定的 schema 拼一个模型回复；``wrap`` 用来模拟脏输出（围栏/前后夹话）。"""
    payload = {
        "score": score,
        "issues": issues if issues is not None else ["膝盖轻微内扣"],
        "suggestions": suggestions if suggestions is not None else ["向外打开膝盖"],
        "good_points": good_points if good_points is not None else ["下蹲深度达标"],
    }
    text = json.dumps(payload, ensure_ascii=False)
    return wrap.format(text) if wrap else text


class StubMultimodal:
    """打桩的多模态客户端：记录收到的请求，返回预设文本。"""

    model = "qwen-vl-max-stub"

    def __init__(self, reply: str | None = None, error: Exception | None = None):
        self.configured = True
        self._reply = reply
        self._error = error
        self.calls: list[dict] = []

    def describe_image(self, image_base64, user_prompt, **kwargs):
        self.calls.append({"image_base64": image_base64, "user_prompt": user_prompt, **kwargs})
        if self._error:
            raise self._error
        return self._reply or ""


@pytest.fixture
def stub(monkeypatch):
    """默认：真实链路（mock_mode=False）+ 可替换的打桩客户端。"""
    monkeypatch.setattr(agent.settings, "mock_mode", False)

    holder = {"client": StubMultimodal(_pose_json())}

    def _fake_get_multimodal(settings=None):
        return holder["client"]

    monkeypatch.setattr(multimodal, "get_multimodal", _fake_get_multimodal)
    return holder


# ============================================================
# 1. 快乐路径
# ============================================================

class TestHappyPath:

    def test_valid_json_should_map_to_response(self, stub):
        result = agent.evaluate_pose(IMAGE_B64, "深蹲")

        assert result.score == 78
        assert result.score_level == agent.score_level(78)
        assert result.issues == ["膝盖轻微内扣"]
        assert result.suggestions == ["向外打开膝盖"]
        assert result.good_points == ["下蹲深度达标"]
        assert result.evaluated_at is not None

    def test_request_payload_carries_image_and_action(self, stub):
        """请求里必须带上 Base64 图片、动作名，以及正确的 MIME data URI 判断依据。"""
        agent.evaluate_pose(IMAGE_B64, "硬拉")

        call = stub["client"].calls[0]
        assert call["image_base64"] == IMAGE_B64
        assert "硬拉" in call["user_prompt"]
        assert call["image_bytes"] == PNG_64, "必须把原始字节传下去，供魔数判断 MIME"
        assert call["temperature"] <= 0.3, "姿态评估要稳定结论，不应使用高温"

    def test_system_prompt_mentions_action_and_forbids_fabrication(self, stub):
        agent.evaluate_pose(IMAGE_B64, "卧推")

        system_prompt = stub["client"].calls[0]["system_prompt"]
        assert "卧推" in system_prompt
        assert "不要编造" in system_prompt, "必须明确禁止模型编造看不见的细节"
        assert "{{" not in system_prompt, "Prompt 里的 {{ }} 必须已被 format 还原"

    def test_score_level_is_derived_locally(self, stub):
        """模型自报的等级不可信：score=95 必须得到「优秀」，而不管模型怎么说。"""
        stub["client"]._reply = _pose_json(score=95)

        result = agent.evaluate_pose(IMAGE_B64, "深蹲")
        assert result.score_level == agent.score_level(95)

    def test_score_accepts_dirty_numbers(self, stub):
        """模型常写 "78分" / 78.0 / 越界的 105，都要被规范成 0-100 的整数。"""
        for raw, expected in [("78分", 78), (78.0, 78), (" 82 ", 82), (105, 100), (-5, 0)]:
            stub["client"]._reply = _pose_json(score=raw)
            assert agent.evaluate_pose(IMAGE_B64, "深蹲").score == expected

    def test_single_string_instead_of_list_is_wrapped(self, stub):
        stub["client"]._reply = _pose_json(issues="膝盖内扣", suggestions="打开膝盖")

        result = agent.evaluate_pose(IMAGE_B64, "深蹲")
        assert result.issues == ["膝盖内扣"]
        assert result.suggestions == ["打开膝盖"]


# ============================================================
# 2. 脏 JSON 输出（真实模型实测会犯的错）
# ============================================================

class TestDirtyJsonTolerance:

    def test_trailing_extra_brace_is_tolerated(self, stub):
        """实测 qwen-vl-max 会在 JSON 末尾多写一个 `}`（照抄 Prompt 示例），必须能解析。"""
        stub["client"]._reply = _pose_json() + "}"

        assert agent.evaluate_pose(IMAGE_B64, "深蹲").score == 78

    def test_markdown_fence_is_stripped(self, stub):
        stub["client"]._reply = _pose_json(wrap="```json\n{}\n```")

        assert agent.evaluate_pose(IMAGE_B64, "深蹲").score == 78

    def test_prose_around_json_is_ignored(self, stub):
        stub["client"]._reply = _pose_json(wrap="好的，以下是评估结果：{}\n希望对你有帮助。")

        assert agent.evaluate_pose(IMAGE_B64, "深蹲").score == 78

    @pytest.mark.parametrize("text,expected", [
        ('{"a": 1}', {"a": 1}),
        ('{"a": 1}}', {"a": 1}),                                  # 多余右括号
        ('```json\n{"a": 1}\n```', {"a": 1}),                     # markdown 围栏
        ('前言 {"a": 1} 后语', {"a": 1}),                           # 前后夹文字
        ('{"a": "含 { 和 } 的字符串", "b": 2}', {"a": "含 { 和 } 的字符串", "b": 2}),
        ('{"a": {"b": [1, 2]}}', {"a": {"b": [1, 2]}}),           # 嵌套
        ("这不是 JSON", None),
        ("", None),
        (None, None),
        ("[1, 2, 3]", None),                                      # 只要对象，数组不认
    ])
    def test_extract_json_object(self, text, expected):
        """解析器是「模型输出不可信」的唯一缓冲层，边界要逐个钉死。"""
        assert extract_json_object(text) == expected

    def test_non_json_reply_raises_multimodal_error(self, stub):
        stub["client"]._reply = "我觉得这个动作挺好的，没什么问题。"

        with pytest.raises(MultimodalError) as exc:
            agent.evaluate_pose(IMAGE_B64, "深蹲")
        assert "JSON" in str(exc.value)

    def test_missing_score_raises_instead_of_inventing_one(self, stub):
        """拿不到分数时必须报错：编一个分数会让用户以为照片真的被评估过。"""
        stub["client"]._reply = '{"issues": ["x"], "suggestions": [], "good_points": []}'

        with pytest.raises(MultimodalError) as exc:
            agent.evaluate_pose(IMAGE_B64, "深蹲")
        assert "score" in str(exc.value)


# ============================================================
# 3. 照片看不清：不能返回 0 分
# ============================================================

class TestUnjudgeablePhoto:

    @pytest.mark.parametrize("issue", [
        "照片无法判断", "图片无法评估", "关键关节看不清", "这不是该动作的照片",
    ])
    def test_unjudgeable_photo_should_ask_for_a_better_one(self, stub, issue):
        """score=0 + 「无法判断」→ 按入参问题处理（HTTP 400 → Java 9003）。

        否则前端会显示 0 分 /「需改进」，让用户以为自己的动作有问题。
        """
        stub["client"]._reply = _pose_json(
            score=0, issues=[issue], suggestions=["请上传清晰照片"], good_points=["有一个人"])

        with pytest.raises(AgentInputError) as exc:
            agent.evaluate_pose(IMAGE_B64, "深蹲")
        assert "清晰" in exc.value.message, "错误信息要告诉用户怎么改"

    def test_real_low_score_is_kept(self, stub):
        """真实的低分（有具体问题描述）必须照常返回，不能被误判成「无法判断」。"""
        stub["client"]._reply = _pose_json(
            score=20, issues=["膝盖严重内扣", "弓背明显"], suggestions=["降重量", "收紧核心"])

        result = agent.evaluate_pose(IMAGE_B64, "深蹲")
        assert result.score == 20
        assert result.score_level == agent.score_level(20)

    def test_partial_unclear_with_real_score_is_kept(self, stub):
        """只是「某处看不清」但仍给出了分数 → 属于正常的部分评估，不应拒绝。"""
        stub["client"]._reply = _pose_json(
            score=72, issues=["左脚踝看不清，无法判断踝背屈角度"], suggestions=["补一张侧面照"])

        assert agent.evaluate_pose(IMAGE_B64, "深蹲").score == 72


# ============================================================
# 4. 客户端自身：配置与响应解析
# ============================================================

class TestClientBehaviour:

    def test_unconfigured_client_raises(self, stub):
        stub["client"].configured = False

        with pytest.raises(MultimodalError) as exc:
            agent.evaluate_pose(IMAGE_B64, "深蹲")
        assert "DASHSCOPE_API_KEY" in str(exc.value)

    def test_call_failure_propagates_as_multimodal_error(self, stub):
        """模型侧真挂了（限流/未开通/网络）→ 交给 Java 走兜底文案。"""
        stub["client"]._error = MultimodalError("HTTP 403: access_denied")

        with pytest.raises(MultimodalError):
            agent.evaluate_pose(IMAGE_B64, "深蹲")

    @pytest.mark.parametrize("raw,expected", [
        (b"\xff\xd8\xff\xe0rest", "image/jpeg"),
        (b"\x89PNG\r\n\x1a\nrest", "image/png"),
        (b"RIFF\x00\x00\x00\x00WEBPVP8 ", "image/webp"),
        (b"GIF89a", "image/jpeg"),          # 不支持的类型按 JPEG 兜底（VL 会自己报错）
        (b"", "image/jpeg"),
    ])
    def test_sniff_image_mime(self, raw, expected):
        """data URI 的 MIME 必须按魔数判断：文件名后缀可以随便改，不能信。"""
        assert sniff_image_mime(raw) == expected

    def test_extract_content_accepts_array_form(self):
        """部分模型把 content 返回成 [{"type":"text","text":"..."}] 数组，要兼容。"""
        class _Resp:
            status_code = 200
            text = ""

            @staticmethod
            def json():
                return {"choices": [{"message": {"content": [
                    {"type": "text", "text": "第一部分"},
                    {"type": "text", "text": "第二部分"},
                ]}}]}

        assert QwenVLClient._extract_content(_Resp()) == "第一部分第二部分"

    @pytest.mark.parametrize("content", ["", "   ", None])
    def test_extract_content_rejects_empty(self, content):
        class _Resp:
            status_code = 200
            text = ""

            @staticmethod
            def json():
                return {"choices": [{"message": {"content": content}}]}

        with pytest.raises(MultimodalError):
            QwenVLClient._extract_content(_Resp())

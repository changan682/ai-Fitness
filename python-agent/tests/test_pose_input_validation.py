"""姿态评估入参校验测试（规范第 7 条 / 第 1439 行）。

Java 侧会先把图片压缩到 ≤1MB 再转 Base64，但 Python 侧的
``POST /agent/v1/pose-evaluate`` 是内网接口，联调脚本、Swagger、压测工具
都能直接打进来。Base64 解码是「一次性申请一整块内存」的操作：
一张 20MB 的图转成 Base64 约 27MB，几个并发就足以把进程打爆（OOM），
而且 OOM 会让**整个服务**挂掉，不只是这一个请求失败。

因此在入口做两道校验（错误约定沿用项目现有风格：``AgentInputError`` → HTTP 400，
Java 侧统一映射为 9003 参数错误，返回结构不变）：

1. Base64 必须能通过 ``b64decode(validate=True)`` 严格解码；
2. 解码后字节数不得超过 ``settings.pose_max_decoded_image_bytes``。
"""

from __future__ import annotations

import base64

import pytest

from app import agent
from app.models import PoseEvaluateRequest
from app.utils import AgentInputError

# 一张极小的合法 PNG（1x1 像素），用于「正常路径」用例
TINY_PNG_BYTES = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg=="
)


@pytest.fixture(autouse=True)
def _offline_mock_mode(monkeypatch):
    """强制走离线实现，避免本文件触发真实多模态调用。

    本文件测的是**入参校验与离线路径的确定性**，与「模型判得准不准」无关：
    - ``MOCK_MODE=false``（生产默认）时 ``evaluate_pose`` 会真的调用通义千问 VL，
      单元测试里既慢又花钱、还会因网络抖动而 flaky；
    - 1x1 的 PNG 也不满足 VL 的「宽高 > 10px」约束，必然被模型拒绝。

    真实多模态链路的测试在 ``tests/test_multimodal_pose.py``：
    那里用打桩的客户端断言「请求发得对不对、响应解析得对不对」。
    """
    monkeypatch.setattr(agent.settings, "mock_mode", True)


def _b64(data: bytes) -> str:
    return base64.b64encode(data).decode("ascii")


class TestValidInput:

    def test_valid_image_should_return_result(self):
        """合法图片：返回结构不变，score_level 仍由 score 严格推导。"""
        result = agent.evaluate_pose(_b64(TINY_PNG_BYTES), "深蹲")

        assert 0 <= result.score <= 100
        assert result.score_level == agent.score_level(result.score)
        assert result.issues and result.suggestions and result.good_points
        assert result.evaluated_at is not None

    def test_same_image_is_reproducible(self):
        """同一张图 + 同一动作必须给出相同分数（离线实现的可复现性）。"""
        image = _b64(TINY_PNG_BYTES)
        assert agent.evaluate_pose(image, "硬拉").score == \
            agent.evaluate_pose(image, "硬拉").score


class TestEmptyOrInvalidInput:

    def test_empty_image_should_raise_input_error(self):
        with pytest.raises(AgentInputError) as exc:
            agent.evaluate_pose("", "深蹲")
        assert "图片" in exc.value.message

    def test_whitespace_only_image_should_raise_input_error(self):
        with pytest.raises(AgentInputError):
            agent.evaluate_pose("   ", "深蹲")

    def test_invalid_base64_should_raise_input_error(self):
        """非法字符/错误填充必须在解码阶段就被拒绝，而不是带着垃圾数据继续往下走。"""
        with pytest.raises(AgentInputError) as exc:
            agent.evaluate_pose("这不是Base64!!!@@@", "深蹲")
        assert "Base64" in exc.value.message

    def test_truncated_base64_should_raise_input_error(self):
        """长度不是 4 的倍数（填充被截断）同样属于非法 Base64。"""
        with pytest.raises(AgentInputError):
            agent.evaluate_pose(_b64(TINY_PNG_BYTES)[:-3], "深蹲")


class TestSizeLimit:

    def test_oversized_image_should_be_rejected_with_actionable_message(self, monkeypatch):
        """超过上限时要给出「请压缩后再上传」这种可执行的提示，而不是一句内存错误。"""
        monkeypatch.setattr(agent.settings, "pose_max_decoded_image_bytes", 1024)

        with pytest.raises(AgentInputError) as exc:
            agent.evaluate_pose(_b64(b"\x00" * 2048), "深蹲")

        message = exc.value.message
        assert "图片过大" in message
        assert "压缩" in message, "错误信息必须告诉调用方怎么修"

    def test_image_at_the_limit_should_pass(self, monkeypatch):
        """边界：正好等于上限应放行（`>` 而非 `>=`）。"""
        monkeypatch.setattr(agent.settings, "pose_max_decoded_image_bytes", 1024)

        result = agent.evaluate_pose(_b64(b"\x00" * 1024), "深蹲")
        assert result.score >= 0

    def test_default_limit_is_one_and_a_half_megabyte(self):
        """默认上限 1.5MB：比 Java 侧 1MB 的压缩目标宽，正常图不会被误拒。"""
        assert agent.settings.pose_max_decoded_image_bytes == 1_500_000


class TestRequestModelConstraint:

    def test_base64_string_length_is_capped_by_model(self):
        """Pydantic 层先拦一道：Base64 字符串长度上限 2_000_000（约等于解码后 1.5MB）。"""
        from pydantic import ValidationError

        with pytest.raises(ValidationError):
            PoseEvaluateRequest(image_base64="A" * 2_000_001, action_name="深蹲")

    def test_description_mentions_java_compression(self):
        """description 要写明「Java已压缩，解码后 ≤1MB」，便于对端理解契约。"""
        field = PoseEvaluateRequest.model_fields["image_base64"]
        assert "Java已压缩" in field.description
        assert field.metadata, "必须带 max_length 约束"

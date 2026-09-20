"""接口层测试（FastAPI TestClient）—— 直接经过路由 / 异常处理器 / 中间件。

## 为什么要单独补这一层

``tests/`` 下原有的用例都是**纯函数级**的（``agent.generate_summary(...)``、
``PoseEvaluateRequest(...)``、Settings 默认值……），它们证明不了下面这些事：

1. 路由**路径/方法**和 Java 侧 ``RestClient`` 打的地址一致；
2. ``@app.exception_handler`` 真的把 400/404/405/422/500 都转成了
   ``{success, message, data}`` 统一信封 —— 而不是 FastAPI 默认的 ``{"detail": ...}``；
   （这条最容易回归：一旦误删了对 **Starlette 基类** ``HTTPException`` 的处理器，
   404 会立刻漏出 ``{"detail": "Not Found"}``，Java 侧解析出全 null。）
3. ``TraceIdMiddleware`` 的 ``X-Trace-Id`` 回写没被摘掉，且**错误响应也带**；
4. ``data`` 里的字段名保持 snake_case（``score_level`` / ``generated_at``）——
   Java 侧 DTO 用 ``@JsonProperty`` 逐字对齐，改成 camelCase 就是跨语言空值。

## 两条硬性约束

- **绝不联网**：``.env`` 里 ``MOCK_MODE=false`` 且真实配了 DeepSeek / 百炼 Key，
  所以这里用 ``offline_agent`` fixture 把 ``app.main`` 命名空间里的业务函数整体打桩，
  另有一个 autouse 的 ``_forbid_real_external_calls`` 兜底 —— 任何漏网的真实调用
  都会以 AssertionError 的形式**立刻暴露**，而不是变成一次慢且不稳定的网络请求。
- **不触发 lifespan**：刻意不用 ``with TestClient(app) as c:``，
  否则会执行 ``app.main.lifespan``（连 Milvus + 导入知识库），
  测试会变慢、且依赖外部服务。见 ``test_lifespan_is_not_triggered_...``。
"""

from __future__ import annotations

import base64
import importlib
import re
from datetime import datetime
from typing import Any, Dict, List, Optional

import pytest
from fastapi.exceptions import RequestValidationError
from fastapi.testclient import TestClient
from starlette.exceptions import HTTPException as StarletteHTTPException

from app import main as app_main
from app.config import settings
from app.main import app
from app.middleware import TraceIdMiddleware
from app.models import (
    ActionRecommendation,
    ChatResponse,
    ChatSource,
    HealthData,
    KnowledgeHealthResponse,
    PoseEvaluateResponse,
    RecommendResponse,
    SummaryResponse,
)
from app.utils import TRACE_ID_HEADER, AgentInputError

# ============================================================
# 常量
# ============================================================

BASE = "/agent/v1"
SUMMARY_PATH = f"{BASE}/summary"
RECOMMEND_PATH = f"{BASE}/recommend"
POSE_PATH = f"{BASE}/pose-evaluate"
CHAT_PATH = f"{BASE}/chat"
KNOWLEDGE_HEALTH_PATH = f"{BASE}/knowledge/health"
HEALTH_PATH = f"{BASE}/health"

TRACE_ID = "test-trace-123"

#: 1x1 的合法 PNG（Base64），与 scripts/smoke_test.py 用的是同一张
TINY_PNG_B64 = (
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH"
    "/q842iQAAAABJRU5ErkJggg=="
)

SUMMARY_BODY = {
    "user_id": 1001,
    "date": "2026-07-30",
    "records": [
        {"action": "杠铃卧推", "sets": 4, "reps": 10, "weight": 60, "rpe": 8},
        {"action": "上斜哑铃卧推", "sets": 3, "reps": 12, "weight": 25, "rpe": 7},
        {"action": "绳索夹胸", "sets": 3, "reps": 15, "weight": 15, "rpe": 6},
    ],
    "comparison": {
        "previousDate": "2026-07-23",
        "records": [
            {"action": "杠铃卧推", "sets": 4, "reps": 10, "weight": 55, "rpe": 8},
        ],
    },
}
RECOMMEND_BODY = {"target_muscle": "胸", "equipment": ["哑铃", "杠铃"], "count": 3}
POSE_BODY = {"image_base64": TINY_PNG_B64, "action_name": "深蹲"}
CHAT_BODY = {
    "question": "深蹲时膝盖到底能不能超过脚尖？",
    "category": None,
    "user_id": 1001,
}

#: 桩响应的固定时间戳，用来断言 ISO8601 序列化口径
STUB_NOW = datetime(2026, 7, 30, 15, 35, 0)

#: 六个接口的元数据：(桩函数名, 方法, 路径, 请求体, 本用例说明)
ENDPOINTS = [
    pytest.param("generate_summary", "post", SUMMARY_PATH, SUMMARY_BODY, id="summary"),
    pytest.param("recommend_actions", "post", RECOMMEND_PATH, RECOMMEND_BODY, id="recommend"),
    pytest.param("evaluate_pose", "post", POSE_PATH, POSE_BODY, id="pose-evaluate"),
    pytest.param("chat_with_rag", "post", CHAT_PATH, CHAT_BODY, id="chat"),
    pytest.param(
        "get_knowledge_health", "get", KNOWLEDGE_HEALTH_PATH, None, id="knowledge-health"
    ),
    pytest.param("check_agent_health", "get", HEALTH_PATH, None, id="health"),
]

#: data 的字段名契约（Java 侧 @JsonProperty 逐字对齐，多一个少一个都会解析出 null）
DATA_KEYS = {
    "generate_summary": {"summary", "generated_at"},
    "recommend_actions": {"recommendations", "generated_at"},
    "evaluate_pose": {
        "score",
        "score_level",
        "issues",
        "suggestions",
        "good_points",
        "evaluated_at",
    },
    "chat_with_rag": {"question", "answer", "sources", "generated_at"},
    "get_knowledge_health": {
        "milvus_connected",
        "collection_name",
        "total_documents",
        "last_updated",
        "index_type",
        "embedding_dim",
    },
    "check_agent_health": {
        "status",
        "milvus_connected",
        "llm_api_configured",
        "knowledge_base_ready",
        "timestamp",
    },
}

#: 时间字段必须是 ISO8601（Java 侧声明为 String 并已按 ISO8601 解析）
ISO8601_FIELDS = {
    "generate_summary": "generated_at",
    "recommend_actions": "generated_at",
    "evaluate_pose": "evaluated_at",
    "chat_with_rag": "generated_at",
}

ISO8601_RE = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}")
TRACE_ID_RE = re.compile(r"^\d{8}-\d{6}-[0-9a-f]{6}$")

#: 所有「出网入口」：打桩它们就能保证本文件绝不真的调 DeepSeek / 百炼 VL / Milvus
EXTERNAL_ENTRY_POINTS = (
    ("app.llm", "get_llm"),
    ("app.embedding", "get_embedding"),
    ("app.rag", "get_rag_engine"),
    ("app.multimodal", "get_multimodal"),
)


# ============================================================
# 工具函数
# ============================================================

def _call(client: TestClient, method: str, path: str, body: Optional[dict] = None,
          headers: Optional[Dict[str, str]] = None):
    """按方法发请求（body 为 None 时表示无请求体）。"""
    if method.lower() == "get":
        return client.get(path, headers=headers)
    return client.post(path, json=body, headers=headers)


def _assert_envelope_shape(body: dict) -> None:
    """信封的键必须**恰好**是 success/message/data 三个。"""
    assert set(body.keys()) == {"success", "message", "data"}, (
        f"响应体不是统一信封：{sorted(body.keys())}"
    )


def _assert_error_envelope(response, expected_status: int,
                           trace_id: Optional[str] = None) -> dict:
    """断言「错误响应也是统一信封」——这是 Java 侧能稳定降级的前提。"""
    assert response.status_code == expected_status, (
        f"期望 HTTP {expected_status}，实际 {response.status_code}：{response.text[:200]}"
    )
    assert response.headers.get("content-type", "").startswith("application/json"), (
        "错误响应必须是 JSON（Java 侧按 JSON 反序列化）"
    )

    body = response.json()
    _assert_envelope_shape(body)
    assert body["success"] is False, "错误响应的 success 必须是 false"
    assert body["data"] is None, "错误响应的 data 必须是 null"
    assert isinstance(body["message"], str) and body["message"].strip(), (
        "错误响应必须带人类可读的 message"
    )
    # FastAPI 默认错误格式会带 detail；本项目统一信封里绝不能出现
    assert "detail" not in body, f"漏出了 FastAPI 默认的 detail 格式：{body}"

    trace = response.headers.get(TRACE_ID_HEADER)
    assert trace and trace.strip(), "错误响应也必须回写 X-Trace-Id"
    if trace_id is not None:
        assert trace == trace_id, "透传的 traceId 必须原样回写（跨语言链路追踪的前提）"
    return body


def _collect_keys(node: Any, keys: List[str]) -> None:
    """递归收集响应体里所有 dict 的键。"""
    if isinstance(node, dict):
        for key, value in node.items():
            keys.append(key)
            _collect_keys(value, keys)
    elif isinstance(node, list):
        for item in node:
            _collect_keys(item, keys)


# ============================================================
# Fixture
# ============================================================

@pytest.fixture(autouse=True)
def _forbid_real_external_calls(monkeypatch):
    """兜底防联网：任何真实的大模型 / Embedding / Milvus / 多模态入口直接报错。

    单独写这个 fixture（而不是只依赖 offline_agent）的原因：
    400 分支的用例必须跑**真实**业务函数（体积校验、空 records 判断都在里面），
    万一代码顺序被人调整成「先调模型再校验」，这里会立刻炸出 AssertionError，
    而不是安静地发一次真实网络请求（慢、花钱、结果随机）。
    """
    def _boom(*_args, **_kwargs):
        raise AssertionError(
            "接口层测试禁止真实外部调用（LLM / Embedding / Milvus / 多模态）"
        )

    for module_name, attr in EXTERNAL_ENTRY_POINTS:
        try:
            module = importlib.import_module(module_name)
        except ImportError:  # pragma: no cover - 模块被重命名时只跳过这一层保护
            continue
        if hasattr(module, attr):
            monkeypatch.setattr(module, attr, _boom)


@pytest.fixture
def client():
    """普通 TestClient：服务端未捕获异常直接抛出，便于定位。

    **刻意不用 ``with``**：``TestClient.__enter__`` 才会跑 lifespan，
    而 lifespan 里会连 Milvus + 导入知识库（见模块 docstring）。
    """
    return TestClient(app)


@pytest.fixture
def lenient_client():
    """把未预期异常交回响应体，用于验证 500 的统一信封。"""
    return TestClient(app, raise_server_exceptions=False)


@pytest.fixture
def offline_agent(monkeypatch):
    """把 ``app.main`` 命名空间里的 5 个业务函数 + 健康检查整体换成确定性桩。

    为什么打这里：``main.py`` 顶部是 ``from . import agent``，
    路由体里全部写成 ``agent.xxx(...)``，因此打 ``app.main.agent.xxx``
    最贴近真实调用点，也不会牵动其它模块的导入。

    返回一个 ``{函数名: 收到的参数}`` 字典，供用例断言「路由把字段原样传下去了」。
    """
    calls: Dict[str, Dict[str, Any]] = {}

    stubs = {
        "generate_summary": SummaryResponse(
            summary="### 📊 训练概览\n桩总结（不调用大模型）", generated_at=STUB_NOW
        ),
        "recommend_actions": RecommendResponse(
            recommendations=[
                ActionRecommendation(
                    action_name="杠铃卧推",
                    target_muscle="胸",
                    focus_area="胸大肌中部",
                    recommended_sets="4",
                    recommended_reps="8-10",
                    difficulty="新手",
                    notes="肩胛骨后缩下沉，杠铃下放至胸中部",
                    equipment=["杠铃"],
                )
            ],
            generated_at=STUB_NOW,
        ),
        "evaluate_pose": PoseEvaluateResponse(
            score=88,
            score_level="良好",
            issues=["底部略有含胸"],
            suggestions=["下蹲时挺胸，保持脊柱中立"],
            good_points=["膝盖与脚尖方向一致"],
            evaluated_at=STUB_NOW,
        ),
        "chat_with_rag": ChatResponse(
            question=CHAT_BODY["question"],
            answer="### 结论\n膝盖超过脚尖本身不是问题。",
            sources=[
                ChatSource(
                    category="动作要领",
                    title="深蹲时膝盖与脚尖的位置关系",
                    content="膝盖是否超过脚尖取决于身体比例和深蹲方式。",
                    score=0.93,
                )
            ],
            generated_at=STUB_NOW,
        ),
        "get_knowledge_health": KnowledgeHealthResponse(
            milvus_connected=True,
            collection_name="fitness_knowledge",
            total_documents=200,
            last_updated="2026-07-30T15:35:00",
            index_type="IVF_FLAT",
            embedding_dim=768,
        ),
        "check_agent_health": HealthData(
            status="UP",
            milvus_connected=True,
            llm_api_configured=True,
            knowledge_base_ready=True,
            timestamp="2026-07-30T15:35:00",
        ),
    }

    for name, result in stubs.items():
        def _make_stub(name=name, result=result):
            def _stub(*args, **kwargs):
                calls[name] = {"args": args, **kwargs}
                return result

            return _stub

        monkeypatch.setattr(app_main.agent, name, _make_stub())

    return calls


# ============================================================
# 1. 正常路径：统一信封 + snake_case 字段名契约
# ============================================================

class TestSuccessPath:

    @pytest.mark.parametrize("stub_name, method, path, body", ENDPOINTS)
    def test_success_envelope_is_uniform(
        self, client, offline_agent, stub_name, method, path, body
    ):
        """防回归：所有业务接口都必须返回 {success:true, message:"ok", data:{...}}。

        信封不统一（比如某个接口直接 return 了裸模型）会让 Java 侧
        ``RestClient`` 拿到 success=null，前端整块数据变空白。
        """
        response = _call(client, method, path, body)

        assert response.status_code == 200, response.text[:200]
        payload = response.json()
        _assert_envelope_shape(payload)
        assert payload["success"] is True
        assert payload["message"] == "ok"
        assert isinstance(payload["data"], dict), "成功响应的 data 必须是对象"
        assert response.headers.get(TRACE_ID_HEADER), "成功响应也要回写 X-Trace-Id"
        # 桩必须真的被调到：证明这条用例走的是「路由 + 信封」而不是真实外部调用
        assert stub_name in offline_agent, "业务函数没有被调用，路由可能没接上"

    @pytest.mark.parametrize("stub_name, method, path, body", ENDPOINTS)
    def test_data_keys_match_java_contract(
        self, client, offline_agent, stub_name, method, path, body
    ):
        """防回归：data 的字段名必须与规范逐字一致（Java 侧 @JsonProperty 对齐）。

        例如 pose 的 ``score_level`` 写成 ``scoreLevel``、summary 的
        ``generated_at`` 写成 ``generatedAt``，Java 反序列化不会报错，
        只会安静地得到 null —— 这类问题在联调时极难定位，所以在这里锁死。
        """
        payload = _call(client, method, path, body).json()

        assert set(payload["data"].keys()) == DATA_KEYS[stub_name]

    @pytest.mark.parametrize("stub_name, method, path, body", ENDPOINTS)
    def test_no_camel_case_key_anywhere(
        self, client, offline_agent, stub_name, method, path, body
    ):
        """防回归：整个响应体（含嵌套对象/数组）不允许出现任何大写字母的键。

        这是「跨语言契约」的通用兜底：只要有人顺手把某个字段写成 camelCase，
        这条用例就会指出具体的键名。
        """
        payload = _call(client, method, path, body).json()

        keys: List[str] = []
        _collect_keys(payload, keys)
        camel = [k for k in keys if k != k.lower()]
        assert not camel, f"出现非 snake_case 的字段名：{camel}"

    @pytest.mark.parametrize(
        "stub_name, method, path, body",
        # 只挑带 datetime 字段的接口（其余接口没有时间字段，无需跳过占位）
        [case for case in ENDPOINTS if case.values[0] in ISO8601_FIELDS],
    )
    def test_timestamp_fields_are_iso8601(
        self, client, offline_agent, stub_name, method, path, body
    ):
        """防回归：时间字段序列化为 ISO8601（Java 侧声明为 String 并按此解析）。

        若有人把 ``generated_at`` 改成 ``yyyy-MM-dd HH:mm:ss``，
        Java 侧 ``PySummaryData.generatedAt`` 会解析失败或变成 null。
        """
        data = _call(client, method, path, body).json()["data"]
        value = data[ISO8601_FIELDS[stub_name]]
        assert ISO8601_RE.match(str(value)), f"不是 ISO8601：{value}"

    @pytest.mark.parametrize(
        "stub_name, method, path, body, expected",
        [
            pytest.param(
                "generate_summary", "post", SUMMARY_PATH, SUMMARY_BODY,
                {"user_id": 1001, "date_str": "2026-07-30", "comparison": SUMMARY_BODY["comparison"]},
                id="summary-字段映射",
            ),
            pytest.param(
                "recommend_actions", "post", RECOMMEND_PATH, RECOMMEND_BODY,
                {"target_muscle": "胸", "equipment": ["哑铃", "杠铃"], "count": 3},
                id="recommend-字段映射",
            ),
            pytest.param(
                "evaluate_pose", "post", POSE_PATH, POSE_BODY,
                {"image_base64": TINY_PNG_B64, "action_name": "深蹲"},
                id="pose-字段映射",
            ),
            pytest.param(
                "chat_with_rag", "post", CHAT_PATH, CHAT_BODY,
                {"question": CHAT_BODY["question"], "category": None, "user_id": 1001},
                id="chat-字段映射",
            ),
        ],
    )
    def test_request_fields_are_passed_through(
        self, client, offline_agent, stub_name, method, path, body, expected
    ):
        """防回归：路由把请求字段按**正确的关键字名**传给了业务函数。

        名字写错（例如 ``date`` 传成 ``date_str`` 之外的变量、``count`` 被硬编码）
        在纯函数测试里看不出来，只有经过路由才能发现。
        """
        _call(client, method, path, body)

        received = offline_agent[stub_name]
        for key, value in expected.items():
            assert key in received, f"业务函数没收到 {key}：{sorted(received)}"
            assert received[key] == value, f"{key} 传值不一致：{received[key]!r} != {value!r}"

    def test_summary_records_are_passed_as_list(self, client, offline_agent):
        """防回归：records 全量透传（不是被截断/改名后的副本）。"""
        _call(client, "post", SUMMARY_PATH, SUMMARY_BODY)
        assert offline_agent["generate_summary"]["records"] == SUMMARY_BODY["records"]

    def test_chat_echoes_question(self, client, offline_agent):
        """防回归：/chat 的 data.question 必须回显提问内容（前端展示的依据）。"""
        payload = _call(client, "post", CHAT_PATH, CHAT_BODY).json()
        assert payload["data"]["question"] == CHAT_BODY["question"]

    def test_recommend_item_fields_are_complete(self, client, offline_agent):
        """防回归：推荐条目 8 个字段齐全且是 snake_case（前端卡片逐字段绑定）。"""
        data = _call(client, "post", RECOMMEND_PATH, RECOMMEND_BODY).json()["data"]

        assert len(data["recommendations"]) == 1
        assert set(data["recommendations"][0].keys()) == {
            "action_name",
            "target_muscle",
            "focus_area",
            "recommended_sets",
            "recommended_reps",
            "difficulty",
            "notes",
            "equipment",
        }

    def test_chat_source_fields_are_complete(self, client, offline_agent):
        """防回归：RAG 来源条目字段齐全且带 score（前端要展示引用出处与相关度）。"""
        data = _call(client, "post", CHAT_PATH, CHAT_BODY).json()["data"]

        assert data["sources"], "sources 不能为空"
        assert set(data["sources"][0].keys()) == {"category", "title", "content", "score"}


# ============================================================
# 2. X-Trace-Id 透传（跨语言链路追踪）
# ============================================================

class TestTraceIdPropagation:

    @pytest.mark.parametrize("stub_name, method, path, body", ENDPOINTS)
    def test_trace_id_is_echoed_on_success(
        self, client, offline_agent, stub_name, method, path, body
    ):
        """防回归：请求带 X-Trace-Id 时，响应头必须原样回写。

        Java 侧 RestClient 依赖这个头把「前端 → Java → Python」三段日志串起来；
        一旦消息被中间件丢弃，线上排障就只能靠时间戳猜。
        """
        response = _call(client, method, path, body, headers={TRACE_ID_HEADER: TRACE_ID})

        assert response.status_code == 200
        assert response.headers.get(TRACE_ID_HEADER) == TRACE_ID

    @pytest.mark.parametrize(
        "stub_name, method, path, body", ENDPOINTS
    )
    def test_trace_id_is_generated_when_absent(
        self, client, offline_agent, stub_name, method, path, body
    ):
        """防回归：不带 X-Trace-Id 时必须生成一个合规 traceId，而不是留空。

        生成规则见 ``utils.new_trace_id``：``<yyyyMMdd-HHmmss>-<random6hex>``。
        """
        response = _call(client, method, path, body)

        trace = response.headers.get(TRACE_ID_HEADER)
        assert trace, "没有请求头时也必须生成 traceId"
        assert TRACE_ID_RE.match(trace), f"traceId 格式不符合规范：{trace}"

    @pytest.mark.parametrize(
        "bad_trace_id",
        [
            pytest.param("abc", id="短于 8 位"),
            pytest.param("a" * 65, id="长于 64 位"),
            pytest.param("test@trace#123", id="含非法字符"),
            pytest.param("   ", id="纯空白"),
        ],
    )
    def test_invalid_trace_id_is_replaced(self, client, offline_agent, bad_trace_id):
        """防回归：非法/超长 traceId 不得污染日志，必须丢弃并重新生成。

        ``is_valid_trace_id`` 只允许 字母/数字/横线/下划线/点 且长度 8-64，
        因为 traceId 会被直接写进日志行，垃圾内容会把日志格式打崩。
        """
        response = _call(client, "get", HEALTH_PATH, headers={TRACE_ID_HEADER: bad_trace_id})

        trace = response.headers.get(TRACE_ID_HEADER)
        assert trace != bad_trace_id
        assert TRACE_ID_RE.match(trace), f"替换后的 traceId 不合规：{trace}"

    @pytest.mark.parametrize(
        "method, path, body, status",
        [
            pytest.param("get", f"{BASE}/not-exist", None, 404, id="404-未知路由"),
            pytest.param("post", HEALTH_PATH, None, 405, id="405-方法不匹配"),
            pytest.param("post", CHAT_PATH, {"question": ""}, 422, id="422-参数校验失败"),
        ],
    )
    def test_trace_id_is_echoed_on_error_responses(self, client, method, path, body, status):
        """防回归：**错误**响应同样要回写 traceId。

        这是最容易回归的一条：异常处理器是「绕过」路由返回的，
        一旦只在路由里回写（而不是在中间件/处理器里统一回写），
        恰恰是唯一需要 traceId 的失败请求会丢链路。

        这里刻意不打桩业务函数：404/405/422 都在进入业务逻辑之前就结束了。
        """
        response = _call(client, method, path, body, headers={TRACE_ID_HEADER: TRACE_ID})

        _assert_error_envelope(response, status, trace_id=TRACE_ID)

    def test_trace_id_is_echoed_on_400(self, client):
        """防回归：业务校验失败（400）同样要带 traceId。

        400 走的是 ``agent_input_error_handler``，与 404/405/422 是**不同的**处理器，
        必须单独覆盖。这里用真实的 ``generate_summary``（空 records 在进入大模型前就抛错）。
        """
        response = client.post(
            SUMMARY_PATH,
            json={"user_id": 1001, "date": "2026-07-30", "records": []},
            headers={TRACE_ID_HEADER: TRACE_ID},
        )

        _assert_error_envelope(response, 400, trace_id=TRACE_ID)

    def test_trace_id_is_echoed_on_500(self, lenient_client, monkeypatch):
        """防回归：500 兜底处理器运行在中间件之外，也必须带同一个 traceId。

        ``main.unhandled_exception_handler`` 专门用 ``request.state.trace_id``
        （而非 contextvar）重新装回 traceId —— 这条用例就是锁这个设计。
        """
        def _boom(*_args, **_kwargs):
            raise RuntimeError("模拟未预期异常")

        monkeypatch.setattr(app_main.agent, "generate_summary", _boom)

        response = lenient_client.post(
            SUMMARY_PATH, json=SUMMARY_BODY, headers={TRACE_ID_HEADER: TRACE_ID}
        )

        _assert_error_envelope(response, 500, trace_id=TRACE_ID)


# ============================================================
# 3. 参数校验失败（422）：模型层就拦住
# ============================================================

VALIDATION_CASES = [
    pytest.param("post", SUMMARY_PATH, {"json": {"date": "2026-07-30", "records": []}},
                 id="summary-缺 user_id"),
    pytest.param("post", SUMMARY_PATH, {"json": {"user_id": 1001, "records": []}},
                 id="summary-缺 date"),
    pytest.param("post", SUMMARY_PATH, {"json": {"user_id": 1001, "date": "2026-07-30"}},
                 id="summary-缺 records"),
    pytest.param("post", SUMMARY_PATH, {"json": {}}, id="summary-空对象"),
    pytest.param("post", SUMMARY_PATH, {"json": None}, id="summary-无请求体"),
    pytest.param(
        "post", SUMMARY_PATH,
        {"content": b"{not-a-json", "headers": {"content-type": "application/json"}},
        id="summary-非法 JSON",
    ),
    pytest.param("post", CHAT_PATH, {"json": {"question": ""}}, id="chat-question 空串"),
    pytest.param("post", CHAT_PATH, {"json": {"question": "深" * 501}}, id="chat-question 超长"),
    pytest.param("post", CHAT_PATH, {"json": {}}, id="chat-缺 question"),
    pytest.param(
        "post", RECOMMEND_PATH, {"json": {"target_muscle": "胸", "equipment": []}},
        id="recommend-器械列表为空",
    ),
    pytest.param(
        "post", RECOMMEND_PATH,
        {"json": {"target_muscle": "胸", "equipment": ["哑铃"], "count": 0}},
        id="recommend-count 下越界",
    ),
    pytest.param(
        "post", RECOMMEND_PATH,
        {"json": {"target_muscle": "胸", "equipment": ["哑铃"], "count": 11}},
        id="recommend-count 上越界",
    ),
    pytest.param("post", RECOMMEND_PATH, {"json": {"equipment": ["哑铃"]}},
                 id="recommend-缺 target_muscle"),
    pytest.param("post", POSE_PATH, {"json": {"action_name": "深蹲"}},
                 id="pose-缺 image_base64"),
    pytest.param(
        "post", POSE_PATH, {"json": {"image_base64": TINY_PNG_B64}},
        id="pose-缺 action_name",
    ),
    pytest.param(
        "post", POSE_PATH,
        {"json": {"image_base64": "A" * 2_000_001, "action_name": "深蹲"}},
        id="pose-image_base64 超 2_000_000 字符",
    ),
]


class TestValidationErrors:

    @pytest.mark.parametrize("method, path, kwargs", VALIDATION_CASES)
    def test_validation_error_is_unified_envelope(
        self, client, offline_agent, method, path, kwargs
    ):
        """防回归：Pydantic 校验失败必须 → 422 统一信封，且 message 可读。

        FastAPI 默认返回 ``{"detail": [{...英文...}]}``，Java 侧按信封解析会得到
        success=null；这里同时锁死「结构是信封」和「没有 detail 键」。
        """
        response = client.request(method.upper(), path, **kwargs)

        body = _assert_error_envelope(response, 422)
        # 人类可读：本项目统一包一层中文说明 + 逐字段列表
        assert "校验失败" in body["message"] or body["message"], "message 不能为空"

    def test_validation_message_names_the_offending_field(self, client, offline_agent):
        """防回归：报错信息要点名字段（``user_id``），否则联调时只能靠猜。

        ``_format_validation_error`` 会把 loc 里的 body/query 前缀去掉，
        只留字段名 —— 这条用例保证它没退化成一句笼统的「参数错误」。
        """
        response = client.post(SUMMARY_PATH, json={"date": "2026-07-30", "records": []})

        body = _assert_error_envelope(response, 422)
        assert "user_id" in body["message"], f"报错没点名字段：{body['message']}"


# ============================================================
# 4. 业务校验失败（400）：模型能过、业务不可执行
# ============================================================

class TestBusinessErrors:
    """这一组**刻意不打桩业务函数**：要验证的正是真实实现抛 AgentInputError 后，
    异常处理器把它转成 HTTP 400 统一信封。所有用例都在校验处提前返回，不会联网。"""

    def test_summary_without_records_is_400(self, client):
        """防回归：无训练记录 → 400「今日暂无训练记录」，而不是硬编一份总结。"""
        response = client.post(
            SUMMARY_PATH, json={"user_id": 1001, "date": "2026-07-30", "records": []}
        )

        body = _assert_error_envelope(response, 400)
        assert "训练记录" in body["message"]

    def test_chat_with_blank_question_is_400(self, client):
        """防回归：空白问题（能过 min_length=1 但语义为空）→ 400「问题不能为空」。"""
        response = client.post(CHAT_PATH, json={"question": "   "})

        body = _assert_error_envelope(response, 400)
        assert "问题不能为空" in body["message"]

    def test_pose_with_empty_image_is_400(self, client):
        """防回归：空图片 → 400（Java 侧映射 9003 参数错误）。"""
        response = client.post(POSE_PATH, json={"image_base64": "", "action_name": "深蹲"})

        body = _assert_error_envelope(response, 400)
        assert "图片" in body["message"]

    def test_pose_with_invalid_base64_is_400(self, client):
        """防回归：非法 Base64 在解码阶段就被拒，而不是带着垃圾数据继续走下去。"""
        response = client.post(
            POSE_PATH, json={"image_base64": "这不是Base64!!@@@", "action_name": "深蹲"}
        )

        body = _assert_error_envelope(response, 400)
        assert "Base64" in body["message"]

    def test_pose_oversized_image_is_400(self, client, monkeypatch):
        """防回归：解码后超过 ``pose_max_decoded_image_bytes`` → 400 且提示可执行。

        说明（重要）：用**默认** 1_500_000 字节上限时，这条分支在 HTTP 层打不到 ——
        Pydantic 的 ``image_base64`` max_length=2_000_000 恰好等价于解码后 1_500_000
        字节，超过它的请求会先被判 422（见 TestValidationErrors）。
        所以要覆盖 400 分支，必须把配置的上限调小；这里是**调配置**，
        不是改代码，等价于运维把上限收紧到 1KB 的场景。
        """
        monkeypatch.setattr(settings, "pose_max_decoded_image_bytes", 1024)
        oversized = base64.b64encode(b"\x00" * 2048).decode("ascii")

        response = client.post(
            POSE_PATH, json={"image_base64": oversized, "action_name": "深蹲"}
        )

        body = _assert_error_envelope(response, 400)
        assert "图片过大" in body["message"], body["message"]
        assert "压缩" in body["message"], "错误信息要告诉调用方怎么修"

    def test_pose_image_exactly_at_default_limit_is_accepted(self, client, monkeypatch):
        """边界防回归：正好 1_500_000 字节必须放行（判定是 ``>`` 而非 ``>=``）。

        顺便锁死一个隐含契约：Pydantic 字符串上限 2_000_000 字符
        ⇔ 解码后正好 1_500_000 字节 —— 两层上限必须保持一致，
        否则会出现「字符串能传但字节数必然超限」的必错组合。

        这里把 ``mock_mode`` 打开，走本地确定性打分（不调通义千问 VL）。
        """
        monkeypatch.setattr(settings, "mock_mode", True)
        at_limit = base64.b64encode(b"\x00" * 1_500_000).decode("ascii")
        assert len(at_limit) == 2_000_000, "字符串长度上限与字节上限必须一一对应"

        response = client.post(POSE_PATH, json={"image_base64": at_limit, "action_name": "深蹲"})

        assert response.status_code == 200, response.text[:200]
        data = response.json()["data"]
        assert 0 <= data["score"] <= 100
        # score_level 由 score 本地派生，不能采信模型自报（离线实现同样适用）
        assert data["score_level"] == app_main.agent.score_level(data["score"])


# ============================================================
# 5. 路由层错误：404 / 405
# ============================================================

ROUTING_CASES = [
    pytest.param("get", f"{BASE}/not-exist", 404, "Not Found", id="GET 未知路径 404"),
    pytest.param("post", f"{BASE}/not-exist", 404, "Not Found", id="POST 未知路径 404"),
    pytest.param("get", "/", 404, "Not Found", id="根路径未注册 404"),
    pytest.param("get", f"{BASE}/healthx", 404, "Not Found", id="前缀相似但未注册 404"),
    pytest.param("get", SUMMARY_PATH, 405, "Method", id="GET /summary 方法不匹配 405"),
    pytest.param("post", HEALTH_PATH, 405, "Method", id="POST /health 方法不匹配 405"),
    pytest.param(
        "post", KNOWLEDGE_HEALTH_PATH, 405, "Method",
        id="POST /knowledge/health 方法不匹配 405",
    ),
    pytest.param("delete", SUMMARY_PATH, 405, "Method", id="DELETE /summary 方法不匹配 405"),
]


class TestRoutingErrors:

    @pytest.mark.parametrize("method, path, status, message_part", ROUTING_CASES)
    def test_routing_error_is_unified_envelope(
        self, client, method, path, status, message_part
    ):
        """防回归：404/405 也必须是统一信封（不能漏 FastAPI 默认的 detail）。

        ``main.py`` 专门注册的是 **Starlette 基类** ``HTTPException`` 的处理器：
        未匹配路由的 404 由 Starlette 内部抛出基类异常，只注册
        ``fastapi.HTTPException`` 会漏掉它们。这组用例就是那个设计的回归网。
        """
        response = client.request(method.upper(), path)

        body = _assert_error_envelope(response, status)
        assert message_part.lower() in body["message"].lower(), body["message"]

    def test_unknown_route_is_not_mistaken_for_a_500(self, client):
        """防回归：未知路由不能触发 500 兜底（那会把「路径写错」伪装成服务故障）。

        路径写错在生产上是 Java 侧配置问题，必须能从 status code 一眼区分。
        """
        response = client.get(f"{BASE}/summarize")  # 少了一个 y，典型笔误
        _assert_error_envelope(response, 404)

    def test_405_does_not_reach_business_logic(self, client, monkeypatch):
        """防回归：方法不匹配时绝不能执行到业务函数（否则会产生副作用）。

        用一个「被调用就失败」的桩来证明路由层就把它拦住了。
        """
        called = []

        def _should_not_be_called(*_args, **_kwargs):
            called.append(True)
            raise AssertionError("405 请求不应进入业务逻辑")

        monkeypatch.setattr(app_main.agent, "generate_summary", _should_not_be_called)

        response = client.get(SUMMARY_PATH)

        _assert_error_envelope(response, 405)
        assert not called


# ============================================================
# 6. 500 兜底：不把堆栈抛给调用方
# ============================================================

class TestUnhandledException:

    def test_unexpected_exception_returns_500_envelope(self, lenient_client, monkeypatch):
        """防回归：未预期异常 → 500 统一信封，且不泄漏堆栈/内部细节。"""
        def _boom(*_args, **_kwargs):
            raise RuntimeError("内部实现细节：连接池耗尽 at line 42")

        monkeypatch.setattr(app_main.agent, "evaluate_pose", _boom)

        response = lenient_client.post(POSE_PATH, json=POSE_BODY)

        body = _assert_error_envelope(response, 500)
        assert "内部错误" in body["message"]
        assert "RuntimeError" not in body["message"], "不能把异常类名/堆栈透给调用方"
        assert "line 42" not in body["message"], "不能把内部细节透给调用方"

    def test_500_keeps_envelope_shape_and_json_content_type(self, lenient_client, monkeypatch):
        """防回归：500 仍是 JSON 信封（Java 侧才走得到「AI_RESPONSE_ERROR」降级链路）。

        若 500 返回 HTML/纯文本，Java 的反序列化会抛异常，
        将业务错误升级成「AI 服务不可用」，故障范围被放大。
        """
        def _boom(*_args, **_kwargs):
            raise RuntimeError("boom")

        monkeypatch.setattr(app_main.agent, "chat_with_rag", _boom)

        response = lenient_client.post(CHAT_PATH, json=CHAT_BODY)

        assert response.status_code == 500
        assert response.headers.get("content-type", "").startswith("application/json")
        _assert_envelope_shape(response.json())


# ============================================================
# 7. 结构化不变量（不依赖 HTTP 的「接线」断言）
# ============================================================

class TestStructuralInvariants:

    def test_trace_middleware_is_installed(self):
        """防回归：``TraceIdMiddleware`` 一旦被摘掉，全部 traceId 用例会同时失效。

        直接断言接线本身，失败原因比「响应头没值」明确得多。
        """
        installed = [getattr(m, "cls", None) for m in app.user_middleware]
        assert TraceIdMiddleware in installed, f"实际中间件：{installed}"

    def test_all_exception_handlers_are_registered(self):
        """防回归：四类异常处理器必须齐全（尤其是 Starlette **基类** HTTPException）。

        这是 400/404/405/422/500 统一信封的总开关：
        漏掉任何一个，对应状态码就会漏出 FastAPI 默认格式。
        """
        assert StarletteHTTPException in app.exception_handlers, "404/405 会漏出 detail"
        assert RequestValidationError in app.exception_handlers, "422 会漏出 detail"
        assert AgentInputError in app.exception_handlers, "400 会变成 500"
        assert Exception in app.exception_handlers, "未预期异常会漏出堆栈"

    def test_lifespan_is_not_triggered_without_context_manager(self, client, monkeypatch):
        """防回归：本文件的 client 不进入 ``with``，因此**不会**跑 lifespan。

        ``lifespan`` 里会连 Milvus 并导入知识库（``ensure_knowledge_base``）。
        这里把它换成记录调用的桩：只要接口能正常返回、而桩没被调用，
        就证明测试完全没有被启动事件牵连 —— 这正是 CI 上「不依赖外部服务」的前提。
        """
        try:
            knowledge_init = importlib.import_module("app.knowledge_init")
        except ImportError:  # pragma: no cover - 模块重构时跳过探测
            pytest.skip("knowledge_init 模块不可导入，跳过 lifespan 探测")

        calls: List[Dict[str, Any]] = []
        monkeypatch.setattr(
            knowledge_init, "ensure_knowledge_base",
            lambda **kwargs: calls.append(kwargs) or {},
        )
        monkeypatch.setattr(settings, "auto_init_knowledge", True)
        monkeypatch.setattr(settings, "force_reload_knowledge", False)

        response = client.get(HEALTH_PATH)

        assert response.status_code == 200
        assert calls == [], "TestClient 触发了 lifespan（会自动连 Milvus），必须去掉 with 用法"

    def test_app_routes_are_registered_under_agent_v1(self):
        """防回归：接口路径必须挂在 /agent/v1 前缀下（Java 侧 URL 是硬编码的）。

        通过 OpenAPI 描述来断言路径与方法，避免依赖不同 Starlette 版本
        在 ``app.routes`` 里暴露的内部对象形态。
        """
        paths = app.openapi().get("paths", {})
        expected = {
            HEALTH_PATH: "get",
            SUMMARY_PATH: "post",
            RECOMMEND_PATH: "post",
            POSE_PATH: "post",
            CHAT_PATH: "post",
            KNOWLEDGE_HEALTH_PATH: "get",
        }
        for path, method in expected.items():
            assert path in paths, f"路由缺失：{path}"
            assert method in paths[path], f"{path} 缺少 {method.upper()} 方法"

"""配置模块 —— 使用 pydantic-settings 从环境变量 / .env 读取。

对应规范第二章「Python FastAPI 代码」的 ``app/config.py``。
所有配置项都带默认值，保证「不配任何环境变量也能直接启动」，
方便第 4 周本地联调。
"""

from functools import lru_cache
from pathlib import Path
from typing import Optional

from pydantic_settings import BaseSettings, SettingsConfigDict

# python-agent/ 目录（config.py 在 python-agent/app/ 下）
BASE_DIR = Path(__file__).resolve().parent.parent


class Settings(BaseSettings):
    """服务配置。

    环境变量名 = 字段名大写（大小写不敏感），例如 ``agent_host`` → ``AGENT_HOST``。
    """

    model_config = SettingsConfigDict(
        env_file=BASE_DIR / ".env",
        # 用 utf-8-sig 而不是 utf-8：Windows 上用 PowerShell 5.1 / 记事本编辑 .env 时会写入
        # UTF-8 BOM（EF BB BF），普通 utf-8 解码会把**第一个键**变成 "\ufeffAGENT_PORT"，
        # 导致该键静默失效、悄悄回退成默认值。utf-8-sig 能兼容有无 BOM 两种情况。
        env_file_encoding="utf-8-sig",
        case_sensitive=False,
        extra="ignore",
    )

    # ==================== 服务自身 ====================
    agent_host: str = "0.0.0.0"
    agent_port: int = 8000
    agent_env: str = "dev"
    # 日志级别：DEBUG / INFO / WARNING / ERROR
    log_level: str = "INFO"
    # 日志格式：text（开发，带 traceId 的彩色文本）/ json（生产，结构化 JSON）
    log_format: str = "text"

    # ==================== 大模型（第 5-6 周启用）====================
    # 可空：未配置时 /agent/v1/health 的 llm_api_configured 返回 false
    deepseek_api_key: Optional[str] = None
    llm_model: str = "deepseek-chat"
    llm_base_url: str = "https://api.deepseek.com"

    # ==================== 多模态视觉（姿态评估，第 6 周启用）====================
    # 用百炼「通义千问 VL」，与 Embedding 共用 DASHSCOPE_API_KEY（同一家只维护一个 Key）。
    # 实测（本项目账号 2026-09）：qwen-vl-max / qwen-vl-plus 可用；
    #   qwen-vl-max-latest、qwen2.5-vl-7b-instruct 返回 403 access_denied（未开通）。
    # 另有一条硬约束：图片宽高都必须 > 10px，否则模型直接报 InvalidParameter。
    qwen_vl_model: str = "qwen-vl-max"
    # 单次多模态调用超时（秒）。姿态评估是同步接口，不能让 Java 请求线程长时间挂住
    multimodal_timeout_seconds: float = 60.0
    # 重试次数（仅对网络异常 / 429 / 5xx 生效；其它 4xx 立刻失败，重试无意义）
    multimodal_max_retries: int = 1

    # ==================== Milvus 向量库（第 5-6 周启用）====================
    milvus_host: str = "localhost"
    milvus_port: int = 19530
    milvus_collection: str = "fitness_knowledge"
    # Embedding 向量维度。规范强制 768（与 shibing624/text2vec-base-chinese 的 hidden size 对齐），
    # 换模型时必须同步改这里并重建 Collection，否则 Milvus 会报维度不匹配。
    # ⚠️ 注意 text2vec-**large**-chinese 的 hidden size 是 1024，与 768 维 Collection 混用
    # 会在 insert() 时直接抛维度不匹配 —— 本地模型必须用 text2vec-base-chinese。
    embedding_dim: int = 768
    # RAG 检索返回条数（规范：默认 Top-5）
    rag_top_k: int = 5
    # 检索时的 nprobe（必须与 nlist 配套）。
    # 规范：200 条知识用 nlist=16 + nprobe=4；nprobe=16 只适用于 nlist=128 的场景。
    milvus_nprobe: int = 4
    # 索引类型（规范：IVF_FLAT 或 HNSW）与聚类数
    milvus_index_type: str = "IVF_FLAT"
    # IVF_FLAT 的聚类数 nlist，经验值 ≈ √N（N=200 → √200≈14，取 16）。
    # 参数必须与数据规模匹配：nlist 远超 √N 时每个聚类只分到 1-2 条向量，
    # 聚类近似失效、召回率反而下降（严禁 nlist=128）。数据量涨到万级再按 √N 重调。
    milvus_nlist: int = 16

    # ==================== Embedding 供应商（第 5 周启用）====================
    # embedding_provider 取值：
    #   auto         —— 按已配置的 Key 自动选择（默认）：有百炼 Key 用 dashscope，
    #                   有硅基流动 Key 用 siliconflow，都没有则退化到 hashing
    #   dashscope    —— 阿里云百炼 text-embedding-v3（推荐，一个 Key 同时覆盖第6周的多模态）
    #   siliconflow  —— 硅基流动 BAAI/bge-base-zh-v1.5（原生 768 维）
    #   local        —— 本地 sentence-transformers 加载 shibing624/text2vec-base-chinese
    #                   （规范首选方案，768 维，免费但需下模型）
    #   hashing      —— 离线兜底：字符 n-gram 哈希向量（无需联网/模型，有真实词法相似度，
    #                   用于在没有 Key 的环境下验证 Milvus + RAG 全链路是否通）
    embedding_provider: str = "auto"

    # --- 阿里云百炼（DashScope，OpenAI 兼容接口）---
    dashscope_api_key: Optional[str] = None
    dashscope_base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    dashscope_embedding_model: str = "text-embedding-v3"

    # --- 硅基流动 ---
    siliconflow_api_key: Optional[str] = None
    siliconflow_base_url: str = "https://api.siliconflow.cn/v1"
    siliconflow_embedding_model: str = "BAAI/bge-base-zh-v1.5"

    # --- 本地模型 ---
    # 规范固定用 shibing624/text2vec-base-chinese（hidden size = 768，与 embedding_dim 严格对齐）。
    # ⚠️ 不要换成 text2vec-large-chinese：它的 hidden size 是 1024，
    # 插入 768 维的 Milvus Collection 时会直接报维度不匹配。
    local_embedding_model: str = "shibing624/text2vec-base-chinese"

    # 云端 Embedding 单次请求最多提交多少条文本。
    # 百炼 text-embedding-v3 限制单次最多 10 条，超过会直接报错，因此分批提交。
    embedding_batch_size: int = 10

    # ==================== Java 回调（第 7 周 MQ 回调使用）====================
    java_callback_url: str = "http://localhost:8080"
    hmac_secret: str = "change-me-hmac-shared-secret"

    # ==================== 姿态评估入参体积上限 ====================
    # Base64 字符串**解码后**允许的最大字节数（默认 1.5MB）。
    # Java 侧已先把图片压缩到 ≤1MB 再转 Base64，这里做防御性校验：
    # 万一 Java 侧漏了压缩、或有人直接打内网接口，超大图会在 base64.b64decode
    # 一次性申请出数十 MB 的内存，几个并发请求就能把 Python 进程打爆（OOM）。
    # 超限按参数错误（AgentInputError → HTTP 400 → Java 侧 9003）拒绝。
    pose_max_decoded_image_bytes: int = 1_500_000

    # ==================== RabbitMQ（第 7 周启用）====================
    # 交换机 / 队列名称与 Java 侧 `WeeklyPlanProducer` + `RabbitMQConfig` 严格一致，
    # 任一处写错的表现都是「消息发出去但没人消费」，且不会报错（只会在 MQ 控制台看到堆积）。
    rabbitmq_host: str = "localhost"
    rabbitmq_port: int = 5672
    # ⚠️ vhost 是**独立于账号**的一层命名空间：连错 vhost 时用户名密码都对，
    # 但队列「不存在」（NOT_FOUND），排查时很容易误以为是队列没声明。
    rabbitmq_vhost: str = "/agent1"
    rabbitmq_user: str = "guest"
    rabbitmq_password: str = "guest"
    # 周计划队列（Java 生产者 → Python 消费者）
    rabbitmq_queue: str = "ai.weekly.plan"
    rabbitmq_exchange: str = "ai.fitness.exchange"
    rabbitmq_routing_key: str = "ai.weekly.plan.request"

    # --- 消息重试（同一 taskId 的失败重投次数上限）---
    # 用 Redis 计数器 `mq:retry:{taskId}` 统计，**不用 x-death / redelivered**：
    # requeue=True 重投时 RabbitMQ **不会**递增 x-death（x-death 只在进过死信队列后才记），
    # 拿它当计数会永远停在 0 → 失败消息无限重投，把队列堵死。
    mq_max_retries: int = 3
    # 重试计数的 TTL（秒）：与规范伪代码一致取 3600，
    # 保证「当天反复失败」不会把计数永久留在 Redis 里，把第二天同样的 taskId 直接判死。
    mq_retry_ttl_seconds: int = 3600

    # --- Redis（重试计数用）---
    redis_host: str = "192.168.199.128"
    redis_port: int = 6379
    redis_password: Optional[str] = None
    redis_db: int = 0

    # 【预留开关，当前未接入 app/main.py】是否随 FastAPI 应用启动消费者线程。
    # 现在的部署方式是**独立长驻进程**：`python -m app.mq_consumer`（另开一个终端/容器）。
    # 之所以不做成「Web 服务一起启动」：--reload 重启、多 worker、pytest 都会各起一个消费者，
    # 同一条消息会被多个进程抢；而且 FastAPI 一启动就要连 MQ，
    # 没装 pika 或 MQ 不通时连 Web 服务都起不来。
    # 保留该配置项是为了以后要做"单进程部署"时有明确开关，默认关闭。
    mq_enabled: bool = False

    # ==================== 运行模式开关 ====================
    # MOCK_MODE：是否**跳过真实大模型调用**（省 API 额度 / 无 Key 时也能跑）。
    #   true  → 训练总结走本地拼装；知识问答走「真实 Milvus 检索 + 本地拼装回答」
    #   false → 调用 DeepSeek 生成（需 DEEPSEEK_API_KEY）
    #
    # 注意：本开关**只影响大模型**。Milvus / Embedding / 知识库检索始终走真实实现——
    # 它们跑在本机或由配置决定，属于和 MySQL/Redis 一样的本地依赖，
    # 把它一起 mock 掉等于白白丢弃已经导入的 200 条真实知识。
    mock_mode: bool = True

    # 启动时自动初始化知识库（规范第 3544 行：知识库初始化必须在应用启动时自动完成）。
    # 独立于 mock_mode：Milvus 跑在本机，检查成本很低，
    # 且 ensure_knowledge_base 是幂等的（已导入则跳过，不会重复 Embedding）。
    auto_init_knowledge: bool = True

    # 强制重建知识库（忽略已有数据，全量重新 Embedding 并导入）。
    # 换 Embedding 模型后必须置 true 一次，否则新旧向量维度/语义空间不一致。
    force_reload_knowledge: bool = False

    # 故障注入接口开关（GET /agent/v1/_debug/fault）：默认关闭。
    # 打开后可用于验证 Java 侧 RestClient 的降级链路（6001/6002 错误码）。
    enable_fault_injection: bool = False

    @property
    def llm_api_configured(self) -> bool:
        """LLM Key 是否已配置（仅判断是否存在，绝不打印其值）。"""
        return bool(self.deepseek_api_key and self.deepseek_api_key.strip())

    @property
    def resolved_embedding_provider(self) -> str:
        """解析出实际生效的 Embedding 供应商。

        规范把「DeepSeek Embedding」列为备选，但 **DeepSeek 并不提供 embedding 接口**
        （见 deepseek-ai/DeepSeek-V3 issue #806），因此这里按已配置的 Key 自动降级：
        百炼 → 硅基流动 → 离线 hashing 兜底。
        """
        provider = (self.embedding_provider or "").strip().lower()
        if provider and provider != "auto":
            return provider
        if self.dashscope_api_key and self.dashscope_api_key.strip():
            return "dashscope"
        if self.siliconflow_api_key and self.siliconflow_api_key.strip():
            return "siliconflow"
        return "hashing"

    @property
    def embedding_api_configured(self) -> bool:
        """当前是否在用真实的云端 Embedding（false 表示跑在离线兜底实现上）。"""
        return self.resolved_embedding_provider in ("dashscope", "siliconflow", "local")

    @property
    def multimodal_configured(self) -> bool:
        """姿态评估所需的多模态模型是否可用（需要百炼 Key）。

        与 ``mock_mode`` 的关系：``MOCK_MODE=true`` 时姿态评估走本地确定性实现
        （明确标注为模拟，供无 Key 的离线演示），此时即使本属性为 true 也不会真的调用模型。
        """
        return bool(self.dashscope_api_key and self.dashscope_api_key.strip())


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """获取全局唯一配置实例（带缓存）。"""
    return Settings()


settings = get_settings()

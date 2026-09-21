# Python Agent 服务（AI 健身私教 & 体态管家）

Java BFF（Spring Boot）内网调用的 Python AI 能力服务，基于 **FastAPI + Pydantic v2**。

> **当前进度：第 8 周（全部 AI 能力已完成）。**
> Milvus 向量库、知识库（200 条 / 5 大分类）、RAG 检索链路已跑通并验证；
> 五个同步 AI 能力（训练总结 / 动作推荐 / 姿态评估 / 知识库问答 / 知识库健康）**都已接真实模型**：
>
> - Embedding：阿里云百炼 `text-embedding-v3`（768 维）
> - 大模型：DeepSeek `deepseek-chat`
> - **多模态：通义千问 `qwen-vl-max`**（第 6 周新接，姿态评估走真实看图推理）
> - 动作推荐：DeepSeek 生成 + **动作名白名单校验**，失败回退内置动作库规则引擎
> - 第 7 周：RabbitMQ 每周复盘异步链路（消费 → LLM → HMAC 回调 Java）
> - 第 8 周：**给「模拟/降级」结果加标记**，见下方「8.1 哪些结果是真、哪些是折扣」
>
> ⚠️ **Key 要单独确认**：百炼的 Embedding 与 VL 是两套模型权限，Embedding 能用不代表 VL 已开通；
> 大模型 Key 也可能失效。开工/答辩前先跑 `scripts/check_api_keys.py`（三项探活）。

---

## 1. 目录结构

```
python-agent/
├── app/
│   ├── __init__.py        # 包标识 + 版本号
│   ├── config.py          # 配置（pydantic-settings，读环境变量/.env）
│   ├── models.py          # Pydantic v2 模型（逐字照抄规范，勿改字段名）
│   ├── utils.py           # traceId、日志、日期、数值格式化、HMAC 签名
│   ├── middleware.py      # TraceIdMiddleware（X-Trace-Id 透传 + 回写）
│   ├── llm.py             # 大模型客户端（DeepSeek，OpenAI 兼容协议）【第5周】
│   ├── multimodal.py      # 多模态客户端（通义千问 VL，姿态评估用）【第6周】
│   ├── mq_consumer.py     # RabbitMQ 消费者：pika 手动 ACK、重试、死信 【第7周】
│   ├── callback_client.py # 回调 Java（HMAC 签名，覆盖 body 哈希）【第7周】
│   ├── embedding.py       # Embedding 抽象层，四种 provider 可切换 【第5周】
│   ├── milvus_client.py   # Milvus 连接 / Collection / 索引 / 增删查 【第5周】
│   ├── rag.py             # RAG 引擎：Embedding + 检索 + Context 拼接 【第5周】
│   ├── knowledge_init.py  # 知识库启动初始化（幂等）【第5周】
│   ├── agent.py           # 核心 Agent 函数（真实实现 + 本地兜底）
│   └── main.py            # FastAPI 入口 + 路由 + 全局异常处理 + 启动钩子
├── data/
│   ├── seed_knowledge.json  # 知识库种子数据（200 条，5 大分类）← 最终产物
│   └── seed_parts/          # 分类中间产物（便于单独修订，用脚本合并）
├── scripts/
│   ├── smoke_test.py              # 接口冒烟（31 项断言）
│   ├── check_api_keys.py          # Key 探活：大模型 / Embedding / 多模态 三项
│   ├── ingest_knowledge.py        # 知识入库工具：--stats / --file / --rebuild / --dry-run
│   ├── merge_seed_parts.py        # 合并分类种子 → seed_knowledge.json
│   ├── verify_real_ai.py          # 真实 AI 链路验收（26 项，第 5 周起交付）
│   ├── verify_pose_multimodal.py  # 姿态评估真实多模态端到端验证（10 项）
│   ├── verify_weekly_plan_chain.py# 每周复盘异步链路：发送→消费→回调→验签（11 项）
│   ├── verify_summary_layers.py   # AI 总结双层缓存/同部位对比/变更失效（12 项）
│   ├── verify_java_ai_endpoints.py# Java /api/ai/* 五接口端到端（27 项，含 multipart）
│   └── _test_image.py             # 验收脚本共用的测试图片（宽高须 >10px，见模块注释）
├── tests/                    # pytest（401 项）
├── Dockerfile                # 生产镜像（python:3.12-slim）
├── docker-entrypoint.sh      # 等 Milvus 就绪后再起 FastAPI
├── requirements.txt
├── .env.example
└── README.md
```

---

## 2. 环境要求与安装

- Python **3.10+**（本机实测：Anaconda `E:\Anaconde\python.exe`，Python 3.12.4）
- 第 5 周起多了一个依赖：**`pymilvus>=2.4`**（代码用的是 2.4 才引入的 `MilvusClient` 门面）

```powershell
cd "C:\Users\hhn\Desktop\spring cloud\agent2\python-agent"
E:\Anaconde\python.exe -m pip install -r requirements.txt
```

> **关于 `openai` SDK**：不需要。DeepSeek 与阿里云百炼都走 OpenAI 兼容的 HTTP 接口，
> 代码里用 `httpx` 直接发请求（见 `app/llm.py`、`app/embedding.py`），少一个依赖。
>
> **关于本地 Embedding**：只有 `EMBEDDING_PROVIDER=local` 才需要
> `sentence-transformers`（会连带装数 GB 的 torch），默认走云端或离线兜底，故未列入默认安装。

---

## 3. 依赖的中间件：Milvus

第 5 周起需要一个 Milvus 向量库。**通过项目根目录的 `docker-compose.yml` 启动**
（包含 milvus + etcd + minio 三个容器，带健康检查）：

```powershell
cd "C:\Users\hhn\Desktop\spring cloud\agent2"
docker compose up -d          # 启动
docker compose ps             # 查看状态（等 milvus 变成 healthy）
docker compose logs -f milvus # 跟踪日志
docker compose down           # 停止（保留数据卷）
docker compose down -v        # 连数据一起删（下次启动会重新初始化知识库）
```

> ⚠️ **本机实测 `hub.docker.com` / `registry-1.docker.io` 不可达**（超时），
> 因此 `milvus` 与 `minio` 这两个 Docker Hub 镜像需要先配镜像加速器
> （Docker Desktop → Settings → Docker Engine 加 `registry-mirrors`），
> 或在 `.env` 里覆盖 `MILVUS_IMAGE` / `MINIO_IMAGE` 指向可用镜像源。
> `etcd` 用的是 quay.io，实测可达，无需加速。

其他中间件（MySQL / Redis / RabbitMQ）在虚拟机 `192.168.199.128` 上，由 Java 侧使用，Python 不直连。

---

## 4. 配置

所有配置都有默认值，**不建 `.env` 也能启动**（会退化到离线兜底模式）。需要接入真实 AI 时：

```powershell
Copy-Item .env.example .env
# 然后填入 DASHSCOPE_API_KEY / DEEPSEEK_API_KEY
```

### 4.1 关键开关

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `MOCK_MODE` | `true` | **只影响大模型**。`true` 时训练总结走本地拼装；**Milvus 检索始终真实**（它是本地依赖，mock 掉等于白丢 200 条知识） |
| `AUTO_INIT_KNOWLEDGE` | `true` | 启动时自动初始化知识库（规范要求）。幂等，已导入则跳过 |
| `FORCE_RELOAD_KNOWLEDGE` | `false` | **换 Embedding 模型后必须置 `true` 启动一次**，否则新旧向量语义空间不一致 |
| `EMBEDDING_PROVIDER` | `auto` | `auto`/`dashscope`/`siliconflow`/`local`/`hashing` |
| `ENABLE_FAULT_INJECTION` | `false` | 暴露 `GET /agent/v1/_debug/fault`，供 Java 侧验证降级链路 |

### 4.2 大模型（DeepSeek）

| 环境变量 | 默认值 |
|---|---|
| `DEEPSEEK_API_KEY` | 空（留空时 `llm_api_configured=false`） |
| `LLM_MODEL` | `deepseek-chat` |
| `LLM_BASE_URL` | `https://api.deepseek.com` |

### 4.3 Embedding（RAG 的语义检索质量取决于这里）

> ⚠️ 规范把「DeepSeek Embedding」列为备选，但 **DeepSeek 并不提供 Embedding 接口**
> （见 [deepseek-ai/DeepSeek-V3 issue #806](https://github.com/deepseek-ai/DeepSeek-V3/issues/806)），
> 因此没有 `deepseek` 这个取值。

| provider | 模型 | 需要 Key | 说明 |
|---|---|---|---|
| `auto` | — | — | 按已配置的 Key 自动选择（推荐） |
| `dashscope` | `text-embedding-v3` | `DASHSCOPE_API_KEY` | **推荐**：第 6 周的姿态评估本来就要通义千问 VL，同一家只需维护一个 Key |
| `siliconflow` | `BAAI/bge-base-zh-v1.5` | `SILICONFLOW_API_KEY` | 原生 768 维 |
| `local` | `shibing624/text2vec-base-chinese` | 无 | 规范首选方案，**768 维**，免费但要下 ~400MB 模型 |
| `hashing` | 字符 n-gram 哈希 | 无 | **离线兜底**：只能命中词法相近内容，用于无 Key 时验证全链路 |

> ⚠️ 本地模型**不要**换成 `text2vec-large-chinese`：它的 hidden size 是 **1024**，
> 而 Collection 的 `dim=768`，混用会在 Milvus `insert()` 时直接抛维度不匹配。
> 若确实要换模型，必须先确认新模型输出维度，同步改 `EMBEDDING_DIM` 并
> `FORCE_RELOAD_KNOWLEDGE=true` 重建索引（不同模型的向量不在同一语义空间）。

其余：`EMBEDDING_DIM=768`（规范硬性要求）、`EMBEDDING_BATCH_SIZE=10`（百炼单次上限 10 条）。

### 4.4 Milvus 与检索参数

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `MILVUS_HOST` / `MILVUS_PORT` | `localhost` / `19530` | |
| `MILVUS_COLLECTION` | `fitness_knowledge` | |
| `MILVUS_INDEX_TYPE` / `MILVUS_NLIST` | `IVF_FLAT` / `16` | nlist 经验值 ≈ √N：知识库 N=200 → √200≈14，故取 16 |
| `MILVUS_NPROBE` | `4` | **必须与 nlist 配套**（nprobe=16 只适用于 nlist=128 的场景） |
| `RAG_TOP_K` | `5` | 检索返回条数（规范要求 Top-5） |

> ⚠️ **索引参数必须与数据规模匹配**。严禁用 `nlist=128` 这类远超 √N 的取值：
> N=200 时每个聚类只会分到 1-2 条向量，聚类近似失效、**召回率反而下降**。
> 数据量增长到万级后，再按 √N 重新调整 nlist 与 nprobe。

### 4.5 姿态评估入参与多模态（第 6 周）

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `DASHSCOPE_API_KEY` | （空） | 多模态 Key，**与 Embedding 共用百炼的同一个 Key** |
| `QWEN_VL_MODEL` | `qwen-vl-max` | 实测 `qwen-vl-max` / `qwen-vl-plus` 可用；`qwen-vl-max-latest`、`qwen2.5-vl-*` 可能 403 未开通 |
| `MULTIMODAL_TIMEOUT_SECONDS` | `60.0` | 单次多模态调用超时（同步接口，不能让 Java 线程挂太久） |
| `MULTIMODAL_MAX_RETRIES` | `1` | 仅对网络异常 / 429 / 5xx 重试；4xx 立刻失败 |
| `POSE_MAX_DECODED_IMAGE_BYTES` | `1500000` | Base64 **解码后**的字节上限（1.5MB），超限按参数错误（HTTP 400）拒绝 |

模型侧两条实测约束（写进代码注释，避免反复踩）：

1. **图片宽高都必须 > 10px**，否则返回 `InvalidParameter: The image length and width do not meet the model restrictions`；
2. 它会在回复的 JSON **末尾多写一个 `}`**（照抄 Prompt 示例），所以 JSON 解析必须做括号配对容错。

姿态评估的降级策略（与其它接口不同，务必注意）：

- 模型说「照片无法判断」→ 抛 `AgentInputError`（HTTP 400 → Java 9003），
  提示用户换一张清晰照片。**不会**返回 0 分（那会被前端渲染成「需改进」，自相矛盾）。
- 模型真的挂了（未配 Key / 网络 / 非 JSON）→ 抛 `MultimodalError`（HTTP 500 → Java 6002），
  Java 按规范返回兜底文案「姿态评估服务暂时不可用，请稍后再试」。
- 只有 `MOCK_MODE=true` 才走本地模拟打分（`_evaluate_pose_local`，明确标注为模拟）。

### 4.6 Java 回调签名（第 7 周，Python → Java）

签名原文**必须覆盖请求体**（规范 8.1 / 第 3612-3616 行）：

```
canonical = method + "\n" + path + "\n" + X-Timestamp + "\n" + sha256Hex(rawBody)
X-Signature = HMAC-SHA256(canonical, HMAC_SECRET)      # 十六进制小写
```

- path 示例：`/api/ai/callback/weekly-plan`，method 统一大写 `POST`。
- 只签 `taskId + timestamp` 时，**body 被篡改签名依然有效**，防篡改形同虚设 —— 早期实现
  正是这个缺陷，现已修正（`tests/test_hmac_signature.py` 用「篡改 body 必须验签失败」锁住）。
- Java 侧用原始请求体算哈希，因此 Python 用 `build_callback_headers()` 时必须传入
  **真正发送的那份 body 字节**，不能传反序列化后的 dict（字段顺序/空格差异会导致验签随机失败）。
- 校验侧还会检查 `|now - X-Timestamp| <= 5 分钟`（防重放），并用 `hmac.compare_digest` 常量时间比较。

---

## 5. 启动与停止

```powershell
# 1. 先起 Milvus（见 §3）
cd "C:\Users\hhn\Desktop\spring cloud\agent2"
docker compose up -d

# 2. 再起 Python 服务
cd python-agent
E:\Anaconde\python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

- 服务地址：`http://localhost:8000`
- Swagger UI：`http://localhost:8000/docs`
- Java 侧对应配置：`ai.python.base-url=http://localhost:8000`

启动日志会打印当前生效的真实配置（Mock 状态、Embedding provider、Milvus 地址、知识库导入结果），
**先看这几行就能判断当前处于哪个档位**：

```
配置: mockMode=True llmModel=deepseek-chat llmApiConfigured=False deepseekApiKey=(未配置)
配置: milvus=localhost:19530 collection=fitness_knowledge embeddingProvider=hashing embeddingDim=768
WARNING MOCK_MODE=true：训练总结/动作推荐/姿态评估走本地实现，不调用大模型…
WARNING 未配置真实 Embedding（当前 provider=hashing）：RAG 检索只能命中词法相近的内容…
知识库初始化完成: collection=fitness_knowledge 写入=200 跳过=False 当前总数=200 provider=hashing
```

> 控制台是 GBK 时中文会显示成乱码，先执行 `chcp 65001` 或设 `$env:PYTHONIOENCODING='utf-8'`。

---

## 6. 接口清单

统一前缀 `/agent/v1`，统一响应信封 `{"success": bool, "message": str, "data": object|null}`。

| 方法 | 路径 | 用途 | 请求 | 响应 `data` |
|---|---|---|---|---|
| GET | `/agent/v1/health` | 服务健康检查 | — | `status`、`milvus_connected`、`llm_api_configured`、`knowledge_base_ready`、`timestamp` |
| POST | `/agent/v1/summary` | 训练智能总结 | `{user_id, date, records:[{action,sets,reps,weight,rpe}], comparison}` | `summary`（Markdown）、`generated_at` |
| POST | `/agent/v1/recommend` | 动作智能推荐 | `{target_muscle, equipment:[...], count}` | `recommendations:[{action_name,target_muscle,focus_area,recommended_sets,recommended_reps,difficulty,notes,equipment}]`、`generated_at` |
| POST | `/agent/v1/pose-evaluate` | 动作姿态评估 | `{image_base64, action_name}` | `score`、`score_level`、`issues`、`suggestions`、`good_points`、`evaluated_at`、**`data_source`** |
| POST | `/agent/v1/chat` | 知识库 RAG 问答 | `{question, category, user_id}` | `question`、`answer`（Markdown）、`sources:[{category,title,content,score,`**`score_type`**`}]`、`generated_at`、**`data_source`**、**`degraded`**、**`degradation_reason`** |
| GET | `/agent/v1/knowledge/health` | Milvus 知识库健康 | — | `milvus_connected`、`collection_name`、`total_documents`、`last_updated`、`index_type`、`embedding_dim` |

> 对外只暴露 Java 的 `/api/ai/*`；`/agent/v1/*` 是内网接口，仅 Java 调用（规范第十一章第 3 条）。
>
> Java 侧 DTO 把时间字段声明为 `String`：Python 的 `datetime` 序列化是 **ISO8601**
> （`2026-07-30T15:35:00`），而 Java 全局把 `LocalDateTime` 配成了 `yyyy-MM-dd HH:mm:ss`。

---

## 7. RAG 链路与三层降级

### 7.1 检索链路

```
question → Embedding(768维) → Milvus search(Top-5, COSINE, nprobe=4)
        → 拼接 Context（带编号+来源，编号与 sources 顺序对应）
        → DeepSeek 基于 Context 生成回答 → { answer, sources }
```

`comparison` 字段（Java 传 → Python 用）的结构：

```json
{
  "previousDate": "2026-09-13",
  "scope": "同部位",              // 或 "同名动作"
  "sharedMuscles": ["胸"],
  "sharedActions": ["杠铃卧推"],
  "records": [ ... ],
  "volumeChangePct": -37.5        // 与 scope 同口径
}
```

> ⚠️ **`scope` 必须原样告知大模型**。第 4 周曾把「同名动作口径」的百分比渲染成
> 「同部位」，导致文字与数字对不上 —— 渲染文案必须跟着 `scope` 走。

### 7.2 问答的三层降级（规范第 3547 行）

| 层 | 条件 | 行为 |
|---|---|---|
| 1 | Milvus + LLM 都可用 | 完整 RAG：真实检索 + 大模型生成 |
| 2 | Milvus 可用、无 LLM Key（或 `MOCK_MODE=true`） | **真实检索结果 + 本地拼装回答**，并标注「未经 AI 润色」 |
| 3 | 检索也失败 | 用内置 18 条知识兜底，保证接口不 500 |

> 第 2 层是刻意设计：已经检索到 200 条真实知识时，**不该退回内置的 18 条** —— 那等于白丢检索结果。

### 7.3 训练总结的降级

同样的渐进增强：优先 DeepSeek（用内置的 `SUMMARY_SYSTEM_PROMPT`），失败或未配置 Key 时
回落到本地拼装（数字全部由入参真实计算，第 4 周已验证）。

---

## 8. 当前哪些是真的、哪些是模拟的

| 接口 / 能力 | 真实 | 仍是模拟 |
|---|---|---|
| `knowledge/health` | ✅ Milvus 连接、文档数、索引类型、维度、**最后更新时间（读 manifest 的 built_at）**全部真实 | — |
| `health` 的 `milvus_connected` / `knowledge_base_ready` | ✅ 真实 | — |
| `chat` 的检索 | ✅ 真实 Milvus Top-5 检索 | 回答质量取决于 Embedding：`hashing` 只能命中词法相近内容 |
| `chat` 的回答 | 配了 Key 后 ✅ DeepSeek 生成 | 无 Key 时为本地拼装（会明确标注） |
| `summary` 的数字 | ✅ 总容量/动作数/最佳动作/对比幅度由入参真实计算 | 文案措辞 |
| `summary` 的文案 | 配了 Key 后 ✅ DeepSeek 生成 | 无 Key 时为本地拼装 |
| `recommend` | ✅ DeepSeek 生成 + **动作名白名单校验**（只允许动作库里的动作） | LLM 失败/越界时回退内置动作库规则引擎；`MOCK_MODE=true` 时只用规则引擎 |
| `pose-evaluate` | ✅ **真实多模态推理**（qwen-vl-max 看图给分），`score_level` 由 `score` 严格派生 | `MOCK_MODE=true` 时才走本地模拟打分（由图片哈希派生），此时响应标 `data_source=mock_local` |

### 8.1 哪些结果是真、哪些打了折扣（第 8 周新增的标记）

第 4-6 周把接口都接上了真实模型，但留下一个隐患：**「模拟」与「降级」的结果在响应结构上与真实结果完全一致**。
默认配置（`MOCK_MODE=true`）下姿态评估会返回 45-95 的编造分数、内置兜底问答会返回启发式「相关度」，
调用方无从分辨。第 8 周做**纯增量**扩展，让每条结果自报来源：

| 字段 | 取值 | 含义 |
|:---|:---|:---|
| `pose-evaluate` 的 `data_source` | `qwen_vl` / `mock_local` | 真实看图推理 / 本地模拟打分（**不是**图像分析） |
| `chat` 的 `data_source` | `milvus` / `builtin` / `none` | 200 条真实知识库 / 内置 18 条兜底 / 没检索到来源（纯大模型回答） |
| `chat` 的 `degraded` + `degradation_reason` | bool + 中文说明 | 是否走了降级路径、以及原因（直接展示给用户） |
| `chat.sources[].score_type` | `cosine` / `heuristic` | 分数是真实余弦相似度 / 启发式合成值（**不同问题之间不可比**） |

配套的界面行为（Java 透传、前端展示）：`mock_local` 时前端在结果上方显示「模拟结果（非真实图像分析）」；
`degraded` 时气泡上显示「降级回答」与原因；`score_type=heuristic` 时分数标明为「合成分数」而不是伪装成余弦相似度。
另外内置兜底的回答里**无条件**追加了一段降级说明 —— 原先只在「最高分低于阈值」时才提示，
导致「命中的好」的问题拿回一份看不出任何降级痕迹、却带 0.97 分与引用的回答。

> 相关回归测试：`tests/test_degradation_markers.py`（9 项，逐分支断言标记的实际取值，
> 其中「高分兜底也必须带提示」那条是本次修复的核心防线）。

---

## 9. 验收 / 自测

```powershell
# 前置：Milvus 已起、Python 服务已起（Java 服务在跑才能跑第 4 个脚本）

# 1. Key 三项探活：大模型 / Embedding / 多模态（**开工第一件事**）
E:\Anaconde\python.exe scripts/check_api_keys.py

# 2. 姿态评估真实多模态端到端（10 项断言，会真的调用 qwen-vl-max）
#    建议传自己的真实照片：--image D:/photos/squat.jpg
E:\Anaconde\python.exe scripts/verify_pose_multimodal.py

# 3. 接口冒烟（6 个正常路径 + 4 个错误路径，共 31 项断言）
E:\Anaconde\python.exe scripts/smoke_test.py --base-url http://127.0.0.1:8000

# 4. Java /api/ai/* 五个接口端到端（27 项，含 multipart 图片上传）
E:\Anaconde\python.exe scripts/verify_java_ai_endpoints.py

# 5. AI 总结的双层缓存 / 同部位对比 / 变更失效（12 项）
#    需要 Redis 密码才能验证「MySQL 兜底层」（否则该步会跳过）
$env:REDIS_PASSWORD='<你的Redis密码>'
E:\Anaconde\python.exe scripts/verify_summary_layers.py

# 6. 知识库：统计 / 增量追加 / 重建（Milvus 需在线）
E:\Anaconde\python.exe scripts/ingest_knowledge.py --stats
E:\Anaconde\python.exe scripts/ingest_knowledge.py --dry-run --file my_knowledge.json

# 7. 知识库种子数据校验与合并
E:\Anaconde\python.exe scripts/merge_seed_parts.py --dry-run

# 8. 每周复盘异步链路（发送→消费→回调→验签，11 项；需要 Java 已启动）
E:\Anaconde\python.exe scripts/verify_weekly_plan_chain.py

# 9. Python 单元测试（在 python-agent 目录下执行，不需要联网）
E:\Anaconde\python.exe -m pytest tests -q
```

`tests/` 覆盖：Embedding 接线与维度断言、配置默认值（768 维 / nlist=16 / nprobe=4 /
text2vec-base-chinese）、回调签名（HMAC 覆盖 body、篡改 body 必须验签失败、时间戳偏移
≤5 分钟）、**姿态评估多模态链路（Prompt 组装 / 脏 JSON 容错 / 分数校验 / 看不清照片的处理）**、
**动作推荐白名单（越界动作名必须丢弃、失败回退规则引擎）**、知识库入库工具与种子数据一致性、
**接口层（TestClient：信封结构、错误分支、X-Trace-Id 透传）**。
端到端部分由上面 1-7 号脚本负责。

---

## 10. 第 7 周：每周复盘异步链路（已完成）

规范第 748 行的验收标准是「**数据库查到回调写入的周计划**」。整条链路：

```
  每周日 20:00  Java ScheduledTasks.weeklyStatsReport   纯 Java 统计 → t_weekly_plan.week_summary
  每周日 21:00  Java ScheduledTasks.triggerWeeklyPlanMQ
                 └─ WeeklyPlanProducer → ai.fitness.exchange / ai.weekly.plan.request
                        └─ Python mq_consumer（本模块）
                             ├─ 调 DeepSeek 生成下周建议（WEEKLY_PLAN_SYSTEM_PROMPT）
                             ├─ callback_client 带 HMAC 签名回调 Java
                             └─ 成功后 basic_ack；失败 nack(requeue)；重试 >3 次进死信
                                   └─ Java AICallbackController 验签 → 幂等 → 写 suggestion_text
```

### 怎么运行消费者

```powershell
cd python-agent
E:\Anaconde\python.exe -m app.mq_consumer                    # 长驻消费（另开一个终端）
E:\Anaconde\python.exe -m app.mq_consumer --once             # 只处理一条就退出（联调/验证）
E:\Anaconde\python.exe -m app.mq_consumer --once --wait-seconds 30
```

- **依赖**：`pika`（消费）与 `redis`（重试计数）——`pip install pika redis`。两者都是**惰性导入**，
  没装也不影响 FastAPI 主服务与 pytest。
- **消费者是独立进程**，不随 FastAPI 启动（原因见 `config.py` 里 `mq_enabled` 的注释）。
- 退出码：`0` 正常 / `1` 连不上 RabbitMQ / `2` 缺依赖 / `3` `--once` 等待超时（队列没消息）。

### 端到端验收

```powershell
# 前置：Java 服务已启动（8080）、RabbitMQ 可达
E:\Anaconde\python.exe scripts/verify_weekly_plan_chain.py
```

它会：注册测试用户 + 写本周训练记录 → 投递消息 → 启动消费者处理一条 →
轮询 `/api/v1/weekly-plan/latest` 确认 AI 建议已落库 → 检查主队列与死信队列计数。

### 关键设计（答辩可能被问到）

| 点 | 做法与理由 |
|---|---|
| ACK 时机 | **只有「LLM 成功」且「回调 Java 成功」才 `basic_ack`**。LLM 成功但回调失败却已 ACK = 任务永久丢失 |
| 重试 | `basic_nack(requeue=True)` + Redis 计数 `mq:retry:{taskId}`（TTL 3600s）；不用 `x-death`，因为 requeue **不会**让它递增 |
| 死信 | 重试 >3 次 → `basic_nack(requeue=False)` → `ai.fitness.dlx` → `ai.weekly.plan.dlq`（人工排查） |
| 消息 TTL | 队列 `x-message-ttl=3600000`（1 小时）。消费者宕机 1 小时内即暴露，不用 7 天 TTL 掩盖故障 |
| 验签 | 签名原文 = `method\npath\ntimestamp\nsha256Hex(rawBody)`，**Java 与 Python 有跨语言黄金向量锁死一致性** |
| 防重放 | `\|now - X-Timestamp\| ≤ 5 分钟`，并用 Redis 记 `(taskId, timestamp)` 组合 |
| 幂等 | Redis 锁 `lock:weekly:plan:callback:{taskId}` + DB 唯一索引 `uk_task_id` 兜底；重复回调返回 `6001 任务已处理`（消费者视为成功） |
| 一行一周 | 回调复用 20:00 统计任务建的那一行（按 user+week 查），避免同一周出现两行导致 latest 不确定 |

### 故障注入验证（实测）

- 把 `JAVA_CALLBACK_URL` 指向死端口 → 消费 3 次都失败并重投，第 4 次进死信，
  Broker 上 `x-death.reason = rejected`，消息**没丢**。
- 伪造签名 / 篡改 body / 重放 10 分钟前的合法签名 → 回调接口分别返回 `9002 签名校验失败`
  与 `9002 时间戳缺失或已过期`，均不落库。

### 还剩什么

| 项 | 说明 |
|---|---|
| 知识库规模 | 200 条（规范已达标）；按需扩充到 300+（补「跑步膝」「女性生理期训练」等话题） |
| 死信告警 | 规范要求「队列深度 >0 打印 ERROR 并告警」，目前是 ERROR 日志 + 管理后台可见；邮件/钉钉渠道仍预留 |

搜 `TODO(` 可定位残留的待替换点。

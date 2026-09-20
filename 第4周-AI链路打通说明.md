# 第 4 周交付说明：Python FastAPI 骨架 + Java RestClient 跨语言链路

> 路线图第 4 周：**搭建 Python FastAPI 骨架，Java 编写 RestClient 调用 Python 的 Mock 接口**
> 产出物：跨语言 HTTP 链路打通 ｜ 验证方式：日志打印「Java调Python成功」
> 本阶段 Python 侧全部是 **Mock 数据**，不接真实大模型与 Milvus（那是第 5-6 周）。

---

## 一、交付内容

### 1. Python 侧（新增 `python-agent/`）

Java 工程占了项目根目录，因此 Python 服务放在 `python-agent/` 子目录，内部结构仍与规范一致：

| 文件 | 作用 |
|:---|:---|
| `python-agent/app/main.py` | FastAPI 入口 + 6 个路由 + 统一异常信封 + 启动自检日志 |
| `python-agent/app/models.py` | **逐字照抄规范第 1291-1381 行**的 13 个 Pydantic v2 模型 + `HealthData` |
| `python-agent/app/agent.py` | 核心 Agent 函数（全部 Mock，每处标注 `# TODO(第5-6周)`）+ 4 个 Prompt 模板 + 68 个真实动作 + 18 条知识 |
| `python-agent/app/utils.py` | traceId 生成、带 traceId 的日志、HMAC-SHA256 回调签名、密钥脱敏 |
| `python-agent/app/middleware.py` | `TraceIdMiddleware`：读/生成 `X-Trace-Id` → ContextVar → 回写响应头 |
| `python-agent/app/config.py` | pydantic-settings，读环境变量 |
| `python-agent/scripts/smoke_test.py` | 可重复验收脚本（31 项断言） |
| `python-agent/README.md` | 中文启动与接口文档 |
| `python-agent/requirements.txt` / `.env.example` | 6 个轻量依赖（Milvus/LLM 依赖已注释，第 5-6 周再放开） |

接口一览（统一前缀 `/agent/v1`，统一信封 `{success, message, data}`）：

| 方法 | 路径 | 说明 |
|:---|:---|:---|
| GET | `/agent/v1/health` | 服务健康 |
| POST | `/agent/v1/summary` | 训练智能总结 |
| POST | `/agent/v1/recommend` | 动作推荐 |
| POST | `/agent/v1/pose-evaluate` | 姿态评估 |
| POST | `/agent/v1/chat` | 知识库 RAG 问答 |
| GET | `/agent/v1/knowledge/health` | Milvus 知识库健康 |

### 2. Java 侧（新增 18 个文件，改动 3 个）

**新增：**

| 分类 | 文件 |
|:---|:---|
| 配置 | `config/AiProperties.java`、`config/RestClientConfig.java` |
| 跨语言协议 DTO（snake_case） | `dto/ai/PythonEnvelope`、`PySummaryRequest`、`PySummaryData`、`PyRecommendRequest`、`PyRecommendData`、`PyPoseRequest`、`PyPoseData`、`PyChatRequest`、`PyChatData`、`PyAgentHealthData`、`PyKnowledgeHealthData` |
| 前端契约 DTO（camelCase） | `dto/AiSummaryRequest`、`AiSummaryResponse`、`AiRecommendRequest`、`AiRecommendResponse`、`AiPoseResponse`、`AiChatRequest`、`AiChatResponse`、`AiKnowledgeHealthResponse` |
| 客户端 | `client/AiPythonClient.java` |
| 服务 | `service/AiSummaryService.java`、`service/AiProxyService.java` |
| 控制器 | `controller/AIController.java`（`/api/ai/*` 共 5 个接口） |
| 工具 | `util/AiTimeUtil.java`（ISO8601 → 本地时间） |
| 测试 | `src/test/java/com/fitness/client/AiPythonClientLiveTest.java` |

**改动：**

- `service/HealthCheckService.java` — Python 探测改用 `AiPythonClient`，删掉原来另写的一套 `HttpClient`（避免「健康检查说 UP、业务调用却超时」的配置漂移）
- `repository/TrainingRecordRepository.java` — 新增 `findFirstByUserIdAndTrainingDateLessThanOrderByTrainingDateDesc`，供总结接口找「上次训练」
- `application.yml` — 已有 `ai.python.*` / `ai.fallback.*` 配置，本次新增消费方

---

## 二、Java 代理层设计要点

| 设计点 | 做法 | 为什么 |
|:---|:---|:---|
| **超时** | JDK `HttpClient`，连接 3s / 读取 30s | 没有超时的 HTTP 客户端是线上事故常见来源：Python 卡住会连带拖死 Tomcat 工作线程 |
| **连接复用** | 底层 JDK HttpClient 自带连接池 | 避免每请求三次握手 |
| **traceId 透传** | 请求拦截器从 MDC 取 traceId 注入 `X-Trace-Id` | 规范第十章「强制要求」跨语言链路可追溯 |
| **信封拆解** | `AiPythonClient.unwrap()` 统一校验 `success` 并取 `data` | 业务层不必每个接口重复判空 |
| **异常翻译** | 连接/超时 → 6001；Python 5xx → 6002 | 统一语义，便于前端按 code 分支 |
| **降级兜底** | `AiProxyService.withFallback()` 把 msg 换成 `ai.fallback.*` 预设文案 | 规范第十一章第 7 条：不能让前端白屏 |
| **数据组装** | Java 查库组装 `records` + `comparison` 后推给 Python | Python 不连业务库，业务数据必须由 Java 备好（规范 7.1 第 2 步） |
| **总结缓存** | Redis `ai:summary:{userId}:{date}` + 击穿互斥锁 `lock:ai:summary:{userId}:{date}` | 规范 Redis 2.8 + 第五章击穿防护 |
| **问答不缓存** | `/api/ai/chat` 每次实时检索 | 规范明确要求；知识库会持续追加，缓存答案会让新知识失效 |

`/api/ai/summary` 的 `cached` 标志用「loader 是否被执行」反推，而不是再查一次缓存 —— 因为 `getOrLoad` 在未抢到锁、重试仍未命中时会兜底直接回源，用「是否生成过」判断才准确。

---

## 三、验证记录（全部真实执行）

### 3.1 Python 侧自验

```
启动：E:\Anaconde\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000
脚本：E:\Anaconde\python.exe scripts/smoke_test.py
结果：通过 31 项，失败 0 项 ✅
```
含 6 个正常路径 + 4 个错误路径（422/400/404/500 全部是 `{success:false,message,data:null}` 信封，无 FastAPI 默认 `detail` 泄漏）。

### 3.2 跨语言实测（Java → Python 真实 HTTP）

```powershell
$env:AI_LIVE_TEST="true"; mvn -o test -Dtest=AiPythonClientLiveTest
```
```
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Java 日志（**就是路线图要求的验收字样**）：
```
[traceId=20260913-151825-1e3048] com.fitness.client.AiPythonClient
  - Java调Python成功: path=/agent/v1/summary, summaryLength=458
  - Java调Python成功: path=/agent/v1/recommend, 推荐动作数=5
  - Java调Python成功: path=/agent/v1/chat, 引用来源数=3
```

Python 侧同一时刻的日志（**同一个 traceId**）：
```
[20260913-151825-1e3048] app.middleware - 请求开始 method=POST path=/agent/v1/summary traceIdFrom=header
[20260913-151825-1e3048] app.agent     - 训练总结生成完成(Mock): 总容量=3975 最佳动作=杠铃卧推
```
`traceIdFrom=header` 说明 traceId 是**从 Java 透传过来的**，不是 Python 自己生成的 —— 跨语言链路真正串通。

### 3.3 数据真的贯通了（不是返回写死文案）

测试传入 3 条记录（4×10×60 + 3×12×25 + 3×15×15），断言 Python 返回的总结里必须出现按入参算出的总容量 **3975**；再加一条**中文断言**，确认整条链路没有把中文解成乱码（只断言数字是查不出编码问题的）。

### 3.4 降级链路验证（规范第十一章第 7 条「AI 降级方案」）

规范原文要求：**Python 超时或报错时 Java 必须返回预设兜底文案，不能让前端白屏。**

这条是硬性要求，但真实 Python 跑着的时候反而不好稳定复现「不可用」。做法是把 baseUrl 指向本机特权端口 `127.0.0.1:1`（必然无人监听，连接立刻被拒），稳定复现该分支，并可纳入常规构建：

```
mvn -o test -Dtest=AiDegradationTest
Tests run: 7, Failures: 0
```
覆盖：连接失败 → 6001；推荐/问答/姿态三个接口的 msg 均为**兜底文案**而非技术错误信息；姿态评估的非法动作名在调用 Python **之前**就被 9003 拦下；健康探测失败返回 null 而不抛异常；知识库不可用映射为 6003（不是 6001）。

断言重点不是「抛异常了」，而是 **msg 必须能直接给用户看** —— 如果返回 `Connection refused` 或 `AI服务超时`，前端就只能白屏或展示天书。

### 3.5 协议契约验证（6 个用例，不依赖真实 Python）

跨语言对接最危险的失败模式不是「连不上」，而是**连上了、HTTP 200、字段全是 null** —— 比如 Python 返回 `action_name` 而 Java 写成 `actionName`，接口一片绿、前端全空值。只断言「请求成功」的测试根本抓不到。

因此用 JDK 自带的 `HttpServer` 起桩服务（无需任何新依赖），对**每个字段**逐一断言：

```
mvn -o test -Dtest=AiProtocolContractTest
Tests run: 6, Failures: 0
```
覆盖：`action_name/focus_area/recommended_sets/...` 全部映射正确；ISO8601 时间被正确解析（而不是退化成 now()）；HTTP 5xx → 6002；HTTP 200 但 `success=false` → 6002 且保留 Python 的 message；`data=null` 也视为异常（避免前端拿到 null）。

> 写这个测试时它抓到了我自己的一个 bug：桩服务只把场景开关接在了 `/summary` 上，`/recommend` 永远返回 200，导致「Python 返回 5xx」的用例拿到 200 而误判。已改为三个接口共用同一个场景分发 —— 否则这就是个假绿测试。

### 3.6 全量回归

```
mvn -B test
Tests run: 123, Failures: 0, Errors: 0, Skipped: 4
BUILD SUCCESS
```
（4 个 skipped 就是需要真实 Python 的联调测试 `AiPythonClientLiveTest`，默认按环境变量开关跳过，避免没起 Python 的机器上构建变红。）

---

## 四、怎么跑起来（联调步骤）

```powershell
# 1. 启动 Python Agent（保持前台运行）
cd python-agent
E:\Anaconde\python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 8000

# 2. 启动 Java BFF（需要 192.168.199.128 的 MySQL/Redis 已开启）
cd ..
mvn spring-boot:run

# 3. 冒烟：健康检查里 pythonAgent 应为 UP
curl http://localhost:8080/api/v1/health

# 4. 联调测试（可选）
$env:AI_LIVE_TEST="true"; mvn -o test -Dtest=AiPythonClientLiveTest
```

---

## 五、注意事项与已知限制

1. **⚠️ `/api/ai/knowledge/health` 当前会返回 `milvusConnected=true`、`totalDocuments=200` —— 这是 Mock 占位值，不代表 Milvus 真的可用。** 第 4 周 Python 侧完全没有连 Milvus（`pymilvus` 依赖都还没装）。第 5-6 周接入后这些字段才有真实含义。
2. **`/agent/v1/health` 里的 `milvus_connected` / `knowledge_base_ready` 同理是 Mock 值**；Java 的 `/api/v1/health` 只用其中的 `status` 字段判断 pythonAgent 可用性，因此不受影响。
3. **本机 192.168.199.128 的 MySQL(3306)/Redis(6379)/RabbitMQ(5672) 当前不通**，因此**无法启动完整的 Java 应用做 `/api/ai/*` 的端到端验证**。本次验证的是 `RestClient → Python` 这段真实 HTTP 链路（这是第 4 周的核心交付物）；完整链路需等虚拟机中间件开启后再跑。
4. **「与上次同部位对比」目前是「与上次同名动作对比」**：`t_training_record` 只有 `actionName`、没有肌群字段，动作→肌群的对照表属第 5 周细化项。同名动作必然同肌群，结论准确，只是覆盖范围比「同部位」窄。已写入代码注释。
5. **`t_ai_summary_cache` 表还没用上**：当前总结只缓存到 Redis，进程重启或跨零点后需重新生成。DB 持久化层（规范 Redis 2.8 的「兜底」）属第 5 周。
6. Windows 控制台直接看 Python 日志时中文可能显示成乱码（控制台代码页问题）；**HTTP 响应本身是正确的 UTF-8**，已用 `httpx` 验证（`\u4eca\u65e5\u8bad\u7ec3\u603b\u7ed3` = 「今日训练总结」）。看日志建议先 `chcp 65001`。
7. Python 侧发现的 4 个真实缺陷已修（404 信封泄漏、500 日志丢 traceId、Windows `.env` BOM 使首个键静默失效、`volumeChangePct` 被误标为「总容量」）。
8. **`targetMuscle` 取值范围 Java 比 Python 严格**：Python 侧额外接受 `全身` 与别名，而 Java 的 `AiRecommendRequest` 按规范 7.2 只允许 `胸/背/腿/肩/手臂/核心`，传 `全身` 会被 Java 挡成 9003。这是**有意的**：Java 是对外契约边界，以规范为准；Python 的宽松是为了后续复用方便。若将来要放开，改 Java 侧正则即可，Python 无需改动。
9. **Python 侧 `tests/`（pytest）尚未创建**：规范第九章要求 Python 层有 pytest 单测与 API 测试。本周验收以 `python-agent/scripts/smoke_test.py`（31 项真实断言，含错误路径）为准；pytest 建议随第 5-6 周的真实 Agent 一并补齐（Mock 阶段测 Mock 意义有限）。
10. **故障注入接口默认关闭**：Python 的 `GET /agent/v1/_debug/fault?mode=500|slow|400` 需 `ENABLE_FAULT_INJECTION=true` 才注册，且是独立路由 —— Java 的正常调用碰不到它，所以 Java 侧的降级分支改用桩服务验证（见 3.4/3.5）。

---

## 六、第 5-6 周需要替换的位置

| 位置 | 要做什么 |
|:---|:---|
| `python-agent/app/agent.py` → `generate_summary()` | 换真实 LLM（DeepSeek）调用，启用已内置的 `SUMMARY_SYSTEM_PROMPT` |
| `python-agent/app/agent.py` → `recommend_actions()` | 换真实 LLM 或规则引擎 |
| `python-agent/app/agent.py` → `evaluate_pose()` | 接多模态模型（通义千问 VL） |
| `python-agent/app/agent.py` → `chat_with_rag()` | 接 `rag.py`：Embedding → Milvus Top-5 → LLM |
| **新建** `python-agent/app/milvus_client.py` / `rag.py` / `knowledge_init.py` | Milvus 连接、Collection 与索引、启动初始化 |
| **新建** `python-agent/data/seed_knowledge.json` | 规范强制要求 ≥200 条知识、5 大分类 |
| `python-agent/requirements.txt` | 放开 `pymilvus`、`langchain`、`openai` 等依赖 |
| Java `AiSummaryService` | 补 `t_ai_summary_cache` 实体与 DB 兜底层；补「动作→肌群」对照表以支持同部位对比 |
| Java `AiProxyService` | `/api/ai/chat` 增加 6003（知识库未初始化）的分支映射 |

# 第 5 周交付说明：真实大模型 + Milvus 向量库 + RAG 检索

> 路线图第 5 周：**接入真实大模型与向量数据库，把第 4 周的 Mock 换成真东西**
> 规范验收口径：**Swagger 返回真实 AI 文本 + pymilvus 连接成功**
> 两项均已达成，且**全程通过 Java 的 `/api/ai/*` 端到端验证**（不是只测 Python）。

---

## 一、交付内容

### 1. Python 侧（新增 6 个模块 + 1 份知识库 + 4 个验收脚本）

| 文件 | 作用 |
|:---|:---|
| `python-agent/app/llm.py` | DeepSeek 客户端（`httpx` 直连，**不引入 openai SDK**）：超时、重试、`finish_reason` 处理、用量日志 |
| `python-agent/app/embedding.py` | Embedding 抽象层，5 种供应商：`dashscope` / `siliconflow` / `local` / `hashing` / `auto` |
| `python-agent/app/milvus_client.py` | `MilvusClient` 门面：连接、Collection + `IVF_FLAT(COSINE)` 索引、`flush()`、`count(*)` |
| `python-agent/app/rag.py` | RAG 主链路：query → Embedding → Milvus Top-K → 阈值过滤 → 组装引用来源 |
| `python-agent/app/knowledge_init.py` | 启动幂等导入：manifest 比对 → 缺失才 Embedding → 分批入库（**重复启动 `skipped=True`**） |
| `python-agent/data/seed_knowledge.json` | **200 条**中文健身知识，5 大分类（动作要领/训练计划/营养饮食/恢复与伤病/补剂科普） |
| `python-agent/data/seed_parts/*.json` | 知识库分片源文件（60/40/35/35/30），`merge_seed_parts.py` 合并成上面那份 |
| `python-agent/scripts/smoke_test.py` | 6 接口冒烟（31 项断言，含 4 条错误路径） |
| `python-agent/scripts/verify_real_ai.py` | **真实 AI 验收**：RAG 语义检索质量 + 真实总结（26 项断言） |
| `python-agent/scripts/verify_java_ai_endpoints.py` | **经 Java 的端到端验收**（27 项断言，含 multipart 图片上传） |
| `python-agent/scripts/verify_summary_layers.py` | **双层缓存 + 变更失效 + 同部位口径**（12 项断言） |
| `python-agent/scripts/check_api_keys.py` | 三个 Key 的连通性自检（只报有效性，不打印 Key） |
| `python-agent/tests/`（pytest，40 用例） | `test_embedding.py` / `test_llm_wiring.py` / `test_knowledge_consistency.py` |

真实模型与库的版本：`deepseek-chat`（DeepSeek 官方 API）｜ 百炼 `text-embedding-v3`（768 维）｜ pymilvus 2.6.16 → Milvus Server 2.6.19。

### 2. Java 侧（新增 5 个 + 改动 2 个）

| 分类 | 文件 |
|:---|:---|
| 缓存实体与仓储 | `entity/AiSummaryCache.java`、`repository/AiSummaryCacheRepository.java` |
| 业务服务 | `service/AiSummaryService.java`（三层缓存 + 快照失效）、`service/AiProxyService.java` |
| 工具 | `util/ActionMuscleMapper.java`（动作 → 6 大肌群，供「同部位」对比） |

改动点：

- `controller/AIController.java` — 5 个 `/api/ai/*` 接口全部接真实 Python（不再是桩）
- `exception/GlobalExceptionHandler.java` — 补 `MissingServletRequestPartException` / `MultipartException` / `HttpMediaTypeNotSupportedException` 三个 handler（见第三节 3.3）
- `python-agent/requirements.txt` — 放开 `pymilvus`（其余依赖仍保持精简）

---

## 二、验收结果（全部真实环境，可复现）

| 验收脚本 | 结果 | 说明 |
|:---|:---|:---|
| `scripts/smoke_test.py` | **31 / 31 ✅** | 6 个 Python 接口 + 4 条错误路径 |
| `scripts/verify_real_ai.py` | **26 / 26 ✅** | 真实 Embedding 检索质量 + 真实 DeepSeek 总结 |
| `scripts/verify_java_ai_endpoints.py` | **27 / 27 ✅** | 经 Java 8080 的完整链路（含 multipart） |
| `scripts/verify_summary_layers.py` | **12 / 12 ✅** | 双层缓存 / MySQL 兜底 / 变更失效 / 同部位口径 |
| `mvn -B test` | **131 / 131 ✅** | Java 全量单测 |
| `pytest` | **40 / 40 ✅** | Python 全量单测 |

合计 **96 项接口级断言 + 171 项单元测试**全绿。

### 2.1 「真实」体现在哪里（可对比的数字）

**Embedding 是真实的**，最直接的证据是检索分数分布：

| Embedding 实现 | 语义问题（如「训练计划什么时候该减载」）的 Top-1 分数 |
|:---|:---|
| `hashing`（离线兜底，仅字符 n-gram） | 0.09 – 0.26 |
| `dashscope text-embedding-v3`（**当前生效**） | **0.67 – 0.91** |

且改写问法也能正确命中：「练了几个月没什么进步该怎么办」（知识库里没有这个句子）→ 命中《新手停滞的第一原因：吃不够而不是练不对》与《多久不进步算平台期》，说明是**语义**匹配而非关键词匹配。

**大模型是真实的**：总结里的数字来自入参（`总容量3975kg`、`杠铃卧推2400kg领跑`），但表述是模型自己组织的（`同练胸肌群容量较上次提升5.3%`、`下次卧推最后一组尝试62.5kg×8`）——本地模板写不出这种带判断的建议。

### 2.2 反向验证：把「真」去掉会怎样

只断言「接口 200」的测试无法区分真假 AI。所以每项关键能力都做了**破坏性验证**：

- **删掉 Redis key 后再查** → `cached=true` 且内容与首次完全一致 ⇒ 证明确实有 MySQL 兜底层在工作（而不是 Redis 删了就当没缓存、或直接报错）。
- **把今天的训练记录 4 组改成 5 组** → `cached=false` 且内容变化 ⇒ 证明写入训练记录时**主动失效**了 AI 缓存。
- **把 `hashing` 与百炼的检索分数放在一起比** → 分数区间不重叠 ⇒ 证明向量来自真实模型，不是兜底实现。
- **`ensure_knowledge_base()` 连续调用两次** → 第二次 `skipped=True` ⇒ 证明启动初始化幂等，不会每次重启都重复 Embedding 200 条。

---

## 三、踩到的坑与修复（都能复现，值得写进报告）

### 3.1 「共同肌群」被渲染成「同名动作容量」——数字与口径对不上

第 4 周遗留问题：`t_training_record` 只有 `actionName`，没有肌群，所以「与上次训练对比」实际是「与上次同名动作对比」。
第 5 周加了 `ActionMuscleMapper`（动作 → 6 大肌群）后口径升级为「同部位」，但**文案没跟着改**：数字已经是「共同肌群」的容量变化，文字却仍写「同名动作容量变化」，用户会以为比自己跟自己的同一动作。

修法：给对比结果加 `scope` 字段（`同部位` / `同名动作`），渲染文案**跟着 scope 走**：

- `scope=同部位` → 「共同肌群（胸）的容量较上次提升 5.3%」
- `scope=同名动作` → 「同名动作的训练容量较上次…（口径：**仅同名动作**，不是全天总容量变化）」

同时给 LLM 的 Prompt 里也写明口径，避免模型自由发挥时又说回「同名动作」。这个坑在验收脚本里被固定为一条断言（`未把同部位误标为「同名动作」`），防止回退。

### 3.2 Milvus `get_collection_stats().row_count` 返回 0 —— 假空库

导入 200 条后立刻查统计，`row_count=0`，看起来像「一条都没进去」。实际是 Milvus 的写入**不保证立即可见**，统计要等 segment flush。
修法：导入后显式 `flush()`，健康检查改用 `query(count(*))` 而不是 `get_collection_stats()`。
> 这个坑很危险：如果只按 `row_count` 判断，应用会以为库是空的并**重复导入**；如果用它做断言，测试会假绿/假红。

### 3.3 multipart 传图片报错码是 9999 而不是 9003 —— 参数校验被当成系统异常

Java 的 `/api/ai/pose-evaluate` 收 `multipart/form-data`。漏传 `image` 字段时，Spring 抛的 `MissingServletRequestPartException` **没有任何现有 handler 覆盖**，直接掉进兜底的 `Exception → 9999（系统异常）`。前端会把它当服务器崩了，而实际是用户漏传参数。

`verify_java_ai_endpoints.py` 抓到了它（这是写端到端脚本而不是只写单测的价值）。修法：补 3 个 handler —— `MissingServletRequestPartException`、`MultipartException`、`HttpMediaTypeNotSupportedException`，统一映射到 9003，并把「该接口要求 multipart/form-data」写进 message。现在传 `x-www-form-urlencoded` 也是 9003 而不是 9999。

### 3.4 `embedding` 的相似度阈值不能对所有实现用同一个数

RAG 里原本统一用「余弦相似度 < 0.75 就认为没检索到相关内容」。这在真实 Embedding 上合理，但离线 `hashing` 实现的分数天然在 0.1 上下，于是**每条问答都回「未检索到高度相关的内容」**——功能看着正常，实际 RAG 完全没生效。
修法：把阈值做成**按供应商配置**（`hashing: 0.15`，云端模型 `0.75`），并在日志里打出实际生效的阈值。

### 3.5 规范里的两个错误（照抄会直接启动失败）

| 规范原文 | 问题 | 实际写法 |
|:---|:---|:---|
| `key-prefix: fitness:cache:` | YAML 里裸冒号 + 尾冒号 = 非法标量，**启动直接报错** | `key-prefix: "fitness:cache:"` |
| `characterEncoding=utf8mb4` | MySQL JDBC 的编码参数必须是 Java 字符集名，`utf8mb4` 会抛 `UnsupportedEncodingException` | `characterEncoding=UTF-8` |

两处均已改为正确写法，并在 `检查报告-第1-3周.md` 中登记。

### 3.6 百炼的 `LTAI…` Key 不是 API-KEY

阿里云控制台上有两种东西：**AccessKey ID/Secret**（`LTAI…`）和**百炼 API-KEY**（`sk-…`）。用前者调 Embedding 接口会返回 `401 invalid_api_key`，且错误信息不会告诉你是「Key 类型错了」。
修法：`check_api_keys.py` 增加「Key 形态自检」（`sk-` 前缀判断 + 真实调用验证），并把这条经验写进 `.env.example` 注释。

### 3.7 其它已修问题（简记）

- Redis 存 `AiSummaryCache` 报 `SerializationException: java.time.LocalDateTime not supported` —— `GenericJackson2JsonRedisSerializer` 默认不带 JavaTimeModule。改为注入 Spring 的 `ObjectMapper.copy()`（带上 `LocalDateTime` 支持与默认类型信息），并补了 `RedisSerializerRoundTripTest` 做**变异验证**（去掉修复后 4 条断言里 3 条会红）。
- MySQL 报 `Access denied`（1045）—— MySQL 8 的 `caching_sha2_password` 首次连接需要公钥，连接串必须带 `allowPublicKeyRetrieval=true`。
- Redis 报 `-NOAUTH` —— 密码没配上；已把 `REDIS_PASSWORD` 收敛为**只从环境变量读**（`${REDIS_PASSWORD:}`），避免密码写进 `application.yml` 提交上仓库。
- `python -c "…"` 内联脚本带引号在 PowerShell 下反复被转义搞坏（4 次）—— 改为一律写成脚本文件；同时也避免用 `Get-Content`/`Set-Content` 读写中文文件（会破坏 UTF-8），本项目一律用编辑工具改文件。

---

## 四、怎么跑起来

```powershell
# 0. 前置：虚拟机 192.168.199.128 的 MySQL/Redis 已开；本机 Docker 里的 Milvus(19530) 已启动

# 1. Python Agent（首次启动会自动导入 200 条知识，约 2-3 分钟）
cd python-agent
E:\Anaconde\python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 8000

# 2. Java BFF
cd ..
$env:REDIS_PASSWORD="123456"
mvn spring-boot:run

# 3. 验收（4 个脚本，全部需要上面两个服务在跑）
cd python-agent
E:\Anaconde\python.exe scripts/smoke_test.py                     # 31 项
E:\Anaconde\python.exe scripts/verify_real_ai.py                 # 26 项（真实 Key）
E:\Anaconde\python.exe scripts/verify_java_ai_endpoints.py       # 27 项（经 Java）
E:\Anaconde\python.exe scripts/verify_summary_layers.py          # 12 项（双层缓存）
```

Swagger 上可直接看到真实 AI 文本：<http://localhost:8080/doc.html> → `/api/ai/summary` 与 `/api/ai/chat`。

> **注意**：`verify_real_ai.py` 与 `verify_summary_layers.py` 的断言分「有 Key」和「无 Key」两种模式。
> 无 Key 时总结走本地确定性拼装，脚本按固定措辞严格校验；有 Key 时文案由模型自由发挥，脚本自动放宽为语义校验。
> 这一点是必须的——用固定措辞断言大模型的输出，会把**正确**的结果判成失败。

---

## 五、注意事项与已知限制

1. **知识库共 200 条，刚够规范下限**。当前 RAG 的质量瓶颈在知识覆盖度（例如「跑步膝」「女性生理期训练」等话题没有条目），第 6 周扩到 300+ 条更稳。
2. ~~**`/api/ai/pose-evaluate` 目前还是 Mock 评分**~~ → **第 6 周已接通真实多模态**（通义千问 `qwen-vl-max`，见 `app/multimodal.py`）。
   实测结论（本项目账号）：`qwen-vl-max` / `qwen-vl-plus` 可用；`qwen-vl-max-latest`、`qwen2.5-vl-7b-instruct` 返回 403 未开通。
   另有一条硬约束：**图片宽高都必须 > 10px**（1x1 会被模型拒绝）。
   降级策略与其它接口不同，务必注意：模型说「照片无法判断」时**不返回假分数**，
   而是按入参问题返回 400（Java 9003）引导用户换照片；模型真的挂了才走 6002 兜底文案。
   验收：`scripts/verify_pose_multimodal.py`（10 项断言，会真的调用模型）。
3. ~~**`recommend_actions` 仍是规则引擎**~~ → **第 6 周已接 DeepSeek**，但**动作名仍然可枚举**：
   先用规则引擎算出「用户器械做得了」的候选集，把它塞进 Prompt 要求模型**只能从候选集里挑**，
   返回后做**白名单校验**（越界动作名直接丢弃），全部越界或调用失败则回退规则引擎。
   这样既拿到了 LLM 的选动作/写要点能力，又不会出现训练记录里查不到的野生动作名。
4. **Embedding 用百炼 `text-embedding-v3`（768 维）而非规范首选的本地 `text2vec-base-chinese`**。规范把本地模型列为首选（免费、离线），但需要下载约 400MB 模型且首次加载慢；百炼同样是 768 维，与规范约束一致。`embedding.py` 里 `local` 分支已实现（模型为 `shibing624/text2vec-base-chinese`，**768 维**），换回去只需改 `EMBEDDING_PROVIDER=local`。
   ⚠️ **本地模型不要选 `text2vec-large-chinese`**：它的 hidden size 是 **1024**，而 Collection 建的是 `dim=768`，混用会在 Milvus `insert()` 时直接抛维度不匹配。**换 Embedding 模型必须** `FORCE_RELOAD_KNOWLEDGE=true` 重建一次，否则新旧向量不在同一语义空间（维度也可能不同）、检索结果会莫名其妙。
5. **DeepSeek 不提供 Embedding 接口**（规范把它列为备选是错的，见 deepseek-ai/DeepSeek-V3 issue #806）。`embedding.py` 的 `auto` 分支因此按「百炼 → 硅基流动 → 离线 hashing」降级，不会去调一个不存在的接口。
6. **Milvus 实例里还有另一个项目的 `customer_service_kb` collection**。本项目的代码只读写 `fitness_knowledge`，`knowledge_init.py` 不会碰其它 collection；但**不要**在 Milvus 里做 drop 全库之类的操作。
7. **API Key 绝不进代码与日志**：`.env` 已在 `.gitignore` 里（`.env.example` 提交的是占位值），日志用 `mask_secret()` 脱敏，pytest 里有一条用例专门断言「Key 不会出现在异常信息里」。
8. **`verify_summary_layers.py` 会真的往 Redis / MySQL 写测试数据**（每跑一次注册一个新用户 + 2 条训练记录 + 1 行总结缓存），本地库跑多了会攒垃圾数据，介意就自己清。当前 `t_ai_summary_cache` 里 id 1/2/4/6 就是历次验证留下的（3/5 被失效逻辑删了，这本身就是失效生效的证据）。
9. ~~**`last_updated` 字段目前是 `null`**~~ → **第 6 周已实现**：`milvus_client.health()` 读 `data/.kb_manifest.json` 的 `built_at`，
   格式化成 `YYYY-MM-DD HH:MM:SS`（缺失/损坏/换过 Collection 一律返回 null，绝不抛异常）。
   每次 `--rebuild` 或增量追加都会刷新 `built_at`，因此「重建后 last_updated 会跟着变」。
   顺带发现并修掉一个**隐蔽的谎报**：`_describe_index()` 之前用配置里的 `nlist` 渲染索引摘要，
   配置改成 16 而索引里实际仍是 128 时，它照样显示 16。现在改为读 `describe_index()` 的真实参数，
   并在配置与索引不一致时显式告警（`index_nlist_mismatch()` + `ingest_knowledge.py --stats`）。
10. ~~**虚拟机上的 RabbitMQ 已通但还没用**~~ → **第 7 周已接通异步链路**：Java 声明拓扑并生产消息
    （`RabbitMQConfig` + `WeeklyPlanProducer`，每周日 21:00 由 `ScheduledTasks` 触发），
    Python 侧 `app/mq_consumer.py` 用 pika 手动 ACK 消费、调 LLM 生成建议、
    再经 HMAC 签名回调 Java 写 `t_weekly_plan.suggestion_text`。
    队列参数已在 Broker 上实测：`x-message-ttl=3600000`（1 小时）、死信交换机 `ai.fitness.dlx`
    → 死信队列 `ai.weekly.plan.dlq`，三条绑定与设计一致。

---

## 六、第 6 周要做的（**已完成**）

| 位置 | 要做什么 | 状态 |
|:---|:---|:---|
| `app/agent.py` → `evaluate_pose()` | 接通义千问 VL 真实多模态姿态评估（**先验 Key 的 VL 权限**） | ✅ 新建 `app/multimodal.py`；VL 权限已实测；降级策略见「注意事项 2」 |
| `app/agent.py` → `recommend_actions()` | 可选改 LLM 生成 + **动作名白名单校验** | ✅ 已接 DeepSeek，白名单用「候选集」而非整库；失败回退规则引擎 |
| **新建** `scripts/ingest_knowledge.py` | 知识入库工具：增量导入 / 重建 / 查看统计 | ✅ 支持 `--stats` / `--file` / `--rebuild` / `--dry-run`，含 `(category,title)` 双重去重 |
| `data/seed_knowledge.json` | 扩到 300+ 条，补规范要求的 5 大分类覆盖 | ⏸ 规范只要求 ≥200 条，当前 200 条（60/40/35/35/30）已达标；扩充为可选项 |
| `knowledge_init.py` | 把 `last_updated` 暴露给健康检查（限制项 9） | ✅ 读 manifest 的 `built_at`；并修掉索引参数谎报 |

补充完成的两项（原列在 README）：

| 位置 | 内容 | 状态 |
|:---|:---|:---|
| `tests/` | 补 API 层测试（FastAPI TestClient） | ✅ `tests/test_api_endpoints.py`（90 例）：信封结构、snake_case 契约、400/404/405/422/500、X-Trace-Id 透传 |
| `scripts/check_api_keys.py` | 多模态探活 | ✅ 增加第三项「通义千问 VL」探活（1x1 图会被模型拒绝，脚本用 64x64） |

### ⚠️ 遗留一件事（环境操作，不是代码问题）

`--stats` 显示 **Milvus 里实际索引仍是 `nlist=128`，而配置已改为 `nlist=16`**：

```
索引类型     : IVF_FLAT(COSINE, nlist=128)
⚠️  索引参数与配置不一致：Milvus 里实际 nlist=128，配置里是 nlist=16。
    配置改动尚未生效，需要 FORCE_RELOAD_KNOWLEDGE=true 重建 Collection
```

改配置**不会**修改已建好的索引，需要显式重建一次（`FORCE_RELOAD_KNOWLEDGE=true` 重启，
或 `python scripts/ingest_knowledge.py --rebuild`）。不重建的话，论文里写的索引参数与实跑对不上。

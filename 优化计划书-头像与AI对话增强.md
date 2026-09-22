# 优化计划书：头像自定义 + AI 对话增强（4 项体验问题）

> 提出人：实际体验反馈　|　编写：代码侧核查后成文
> 核查基准：`fa27f42`（含 `5956c2c` 文档订正）+ 当前工作区
> 状态：**批次 A / B / C 已完成并实测通过，批次 D 进行中**
>
> **拍板结论（§9 已闭环）**
> 1. 实施顺序：A→B→C→D→E 全做
> 2. 头像：本地磁盘存储 + 读取接口进 JWT 白名单
> 3. 会话：**新增 `t_ai_chat_history` 表（长期） + Redis 热层（2h）**，采用写穿透 + 读回填
> 4. LLM 兜底：默认开启，低相关即走通用知识并明确标注「未使用知识库」
> 5. 主动问询：用户点击才生成（不在保存体测后自动调用）
>
> **进度**
> | 批次 | 内容 | 状态 | 证据 |
> |:--|:--|:--|:--|
> | A | 问答低相关 → 大模型通用知识兜底（`llm_only`） | ✅ 已提交 `42249cc` | Python 405 项、前端 24 项；标定实测见 §3.2.1 |
> | B | 头像上传/读取全链路 | ✅ 已提交 `75f76a2` | Java 226 项、前端 28 项；真实联调 14 项全过 |
> | C | 对话记忆（表 + 热层 + 回填 + 清理任务） | ✅ 已提交 `0425214` | Java 239 项、Python 410 项、前端 30 项；真实联调 9 项全过 |
> | D | 身体状态主动问询 `/api/ai/body-consult` | ✅ 已提交 `e271d4e` | Java 249 项、Python 426 项、前端 36 项；真实联调 9 项全过 |
> | E | 文档同步 + 全量回归 | ✅ 本轮 | README / python-agent README / 第5周 / 第8周 / 前端 README 数字与新增能力已同步；三端全绿 |
>
> **批次 D 落地要点（与计划的差异，均有实测依据）**
> 1. 快照由 Java 组装（体测/趋势/训练/饮食/档案），Python 不查库 —— 守住 BFF 边界；
> 2. 「近 7 天训练几次」按**不同训练日期**数，不按记录条数：一次训练常录 4-6 条动作记录，
>    按条数会把"练了 3 天"算成"练了 18 次"，AI 据此给的频率建议会完全错向；
> 3. 缓存带**快照指纹**：数据一变立即重新生成（否则用户新记体测后仍看到旧结论）；
> 4. 无体测数据时**不调大模型**，直接规则回复并引导先记录（省 token）；
> 5. 规则兜底覆盖：体重单次变化 >1%、腰围上升、7 天零训练、平均 RPE ≥ 9、体脂缺失、样本 <2；
> 6. **档案里有伤病时必须主动提出并建议就医**（联调实测：`左膝半月板损伤` 被列入 high 级
>    risk_flag）—— 用户自己填了伤病而 AI 只字不提，是这个功能最容易出的事故。
>
> **批次 D 开发中抓到的两个真 bug（都由测试发现）**
> - 提示词模板里的 JSON 花括号被 Python `.format()` 当成占位符 → `KeyError` →
>   **LLM 路径永远走不到、每次都静默退回规则**（表现是"功能能用但从来不是 AI 生成的"，
>   没有测试极难发现）。修法：模板里的 `{}` 转义成 `{{}}`。
> - 伤病记录只存在于档案字段里，规则层当时只检查生成文本 → 不会触发就医提示。修法：
>   规则层直接读 `profile.injury_record`，并放在 flags 最前面（避免被 3 条上限挤掉）。

---

## 0. 结论摘要

| # | 反馈 | 核查结论 | 性质 | 优先级 |
|:--|:--|:--|:--|:--|
| 1 | 不能自己更换头像 | 全项目**零实现**：`t_user` 无头像列、无上传接口、前端是写死的 `UserOutlined` 图标 | 新功能 | P0 |
| 2 | 问答只能靠知识库，没有 LLM 兜底 | **真缺陷**：检索"有命中但都不相关"时，把无关条目硬塞给大模型，还标 `degraded=false` | 缺陷修复 | P0 |
| 3 | 问答没有记忆 | 契约里根本没有会话概念，前端消息只在 `useState`，切 Tab 即销毁 | 功能缺失 | P1 |
| 4 | 没有按身体状态主动问询的 AI | 现有 AI 全是"用户问 → 系统答"，无主动发起能力 | 新功能 | P1 |

**贯穿四项的一条硬约束**：延续上一轮刚建立的原则——**任何降级/模拟结果必须在数据里带标记、在界面上看得见**。本计划新增的每一层兜底都要带 `data_source` / `degraded` / `degradation_reason`，不允许出现"看起来像真的"的假结果。

---

## 1. 现状核查（证据）

### 1.1 头像：确实一点都没有
- `sql/init.sql:12-29` 的 `t_user` 字段为 `nickname / gender / birth_date / height / weight / training_goal / training_level / injury_record / phone / password / created_at / updated_at` —— **无 avatar 列**。
- `User` 实体、`UserProfileResponse`、`LoginResponse.UserBrief` 均无头像字段。
- 前端两处头像都是硬编码图标：`ProfilePage.tsx:236`、`AppLayout.tsx:63` 的 `<Avatar icon={<UserOutlined />} />`。
- 无任何文件上传/静态资源映射（`WebConfig` 只有 JWT 拦截器；`MultipartFile` 仅用在姿态评估 `AIController:67`）。
- 结论：不是"换不了"，是**根本没有这个功能**，要从前端到 DDL 全链路新建。

### 1.2 问答兜底：现有 4 层里漏了最关键的一层
`python-agent/app/agent.py` 现有链路（`chat_with_rag` → `_chat_with_real_rag`）：

| 层 | 触发条件 | 现状 | 问题 |
|:--|:--|:--|:--|
| L1 完整 RAG | 检索有命中 | LLM + context 回答，`data_source=milvus`, `degraded=false` | ✅ 正常 |
| L2 本地拼装 | 检索有命中 但无 LLM Key / `MOCK_MODE=true` | `_build_rag_answer` 拼装，标注"未经润色" | ✅ 正常 |
| L3 纯 LLM | 检索**抛异常**或 **0 命中** | 空 context + `DEGRADED_NOTICE` | ⚠️ 只覆盖"没有条目" |
| L4 内置知识 | 上面全挂 | 18 条内置条目 + 启发式分数 + 强制降级说明 | ✅ 正常 |

**缺口在 L1 与 L3 之间**（`agent.py:1947-2013`）：
- 检索返回 5 条、最高余弦相似度 **0.42**（远低于阈值 0.75）时，`hits` 非空 → 不进入降级 → `agent.py:2006` 用 `RAG_SYSTEM_PROMPT.format(context=context)` 把**这 5 条无关内容**塞给大模型，并要求它"基于资料回答"。
- 结果：用户问到知识库没覆盖的话题（例如"碳水循环怎么安排""手腕旧伤能不能练推举"），拿到的是**由无关资料拼出来的、却自称有知识库依据**的回答，且 `degraded=false`，界面上不显示任何提示，来源列表还挂着 5 条低分条目。
- 这与用户反馈完全一致："只能通过知识库回答，知识库没有时不会用 LLM 自己回答"。**修法不是加一个兜底，而是加一道相关性闸门。**

### 1.3 记忆：契约层就没有会话
- Python `models.py:98-101`：`ChatRequest{question, category, user_id}` —— 没有 `history`、没有 `session_id`。
- Java `AiChatRequest` 同构；`AiProxyService.chat` 每次都是独立一问一答。
- `llm.py` 只有 `chat()` / `chat_with_system(system, user)`，**没有多轮 messages 接口**。
- 前端 `ChatTab.tsx:20-33`：注释明写"对话历史只放在 `useState`，配合 `Tabs` 的 `destroyOnHidden`，切走再回来就是一段新对话"——连"同一屏内多轮"也只做到 UI 展示，**后端完全不知道上文**。
- 结论：用户看到的是"每问一句都是全新的对话"，追问（"那这个动作做几组？"）必然答非所问。

### 1.4 主动问询：现有 AI 接口全是被动
`AIController` 现有 5 个接口：`/ai/summary`（训练总结）、`/ai/recommend`（动作推荐）、`/ai/pose-evaluate`（姿态）、`/ai/chat`（问答）、`/ai/knowledge/health`。

全部是"用户点按钮 → 系统出结果"。用户录完体测（`t_body_metric`：`weight_kg / waist_cm / arm_cm / leg_cm / body_fat_pct`）之后，系统不会主动结合趋势追问（"这周腰围涨了 1.5cm，饮食有变化吗？"），也不会给风险提示。

---

## 2. 目标与验收标准

| 目标 | 可验收的标准 |
|:--|:--|
| G1 用户能自己换头像 | 上传 → 刷新页面/换设备登录，头像仍在；非法文件被拒且有明确提示；旧头像文件被清理，不留垃圾 |
| G2 知识库覆盖不到时由大模型兜底 | 问一个知识库没有的话题：回答内容与问题相关（非无关资料拼装），且界面明确标注"未使用知识库 / 通用知识回答" |
| G3 多轮对话有上下文 | 同一会话内第二问省略主语（"那这个做几组？"）能被正确理解；点"新对话"后不再指代上文 |
| G4 录完身体状态有主动问询 | 保存体测后能一键拿到"基于我最近数据"的追问与建议；数据不足/无 LLM 时有规则化兜底且如实标注 |
| G5 不倒退 | 现有 632 项测试全绿；`/api/ai/chat` 在"高相关命中"场景下行为与响应字段**完全不变** |

---

## 3. 方案设计

### 3.1 头像自定义（G1）

**存储选型**：本地磁盘 + 控制器读文件返回（详见 §9 决策点 1/2）。

| 层 | 改动 |
|:--|:--|
| DDL | `t_user` 增 `avatar_url VARCHAR(255) NULL COMMENT '头像访问路径（含 cache-busting 版本号）'`；同步改 `sql/init.sql` **与**存量库迁移脚本（见 §5） |
| 实体 | `User` 增 `avatarUrl`，按项目既有约定写显式 `@Column(name = "avatar_url")`（`EntityDdlContractTest` 会校验列名物理映射，写错直接红灯） |
| 新增服务 | `AvatarStorageService`：`store(userId, MultipartFile) → avatarUrl`、`load(userId) → 字节+ContentType`、`deleteQuietly(path)` |
| 上传接口 | `POST /api/v1/user/avatar`（`multipart/form-data`，字段 `file`），需登录，返回 `{avatarUrl}`（HTTP 200 信封） |
| 读取接口 | `GET /api/v1/user/avatar/{userId}` → 图片字节流 |
| 白名单 | **必须**把 `/api/v1/user/avatar/**` 加入 `WebConfig` 的 JWT 白名单：浏览器 `<img src>` 不会带 `Authorization` 头，不放行就是"头像永远 401"。安全性由"只暴露头像、不含任何隐私字段"承担 |
| 响应字段 | `UserProfileResponse.avatarUrl`、`LoginResponse.UserBrief.avatarUrl`（**加性字段**，前端旧代码不受影响） |
| 校验 | ① ≤2MB；② 魔数校验（复用 `ImageCompressor.detectFormat`）只允许 jpg/png/webp；③ `ImageIO.read` 必须真能解码（挡掉"改扩展名的假图"）；④ 文件名由服务端生成 `{userId}-{epochMillis}.jpg`，**不含任何用户输入**（防路径穿越） |
| 处理 | 复用 `ImageCompressor.compress(raw, 300*1024, 256)` → 256px 内、≤300KB 的 JPEG。**副作用需在文档写明**：透明 PNG 会被转为白底 JPEG |
| 缓存 | `avatarUrl = /api/v1/user/avatar/{userId}?v={epochMillis}`；响应加 `Cache-Control: public, max-age=86400`。`?v=` 保证换头像后不被旧缓存挡住 |
| 磁盘卫生 | 换头像成功后删除旧文件；目录由配置项 `app.avatar.dir`（默认 `./data/avatars`）决定并加入 `.gitignore` |
| 错误码 | 新增 `AVATAR_INVALID(1004, "头像格式或大小不合法")`（用户模块 1001-1099 区间） |
| 前端 | `ProfilePage` 头像区套 `Upload`（`beforeUpload` 前置校验 + `showUploadList={false}`）；成功后 `userStore` 更新 `avatarUrl` 并 `message.success`；`AppLayout` 头部 `<Avatar src={user?.avatarUrl}>`，无头像回退现有图标；`types/user.ts` 增 `avatarUrl?: string`；`userApi.uploadAvatar()` |

**边界**：未上传 → 接口 404，前端回退图标（不报错）；上传中途失败 → 旧头像保持可用（先写临时文件、成功再改 DB + 删旧文件）。

### 3.2 问答降级：知识库没覆盖时改用通用知识（G2）

#### 3.2.1 先说一个被实测推翻的设计（重要）

初版计划想用「余弦阈值」判知识库有没有覆盖该问题：`best_score < 0.75` 就判定"没覆盖"。
**开工前先做了标定实测，结论是这个闸门不能用**（真实 DashScope Embedding + 200 条知识库，top1 余弦）：

| 问题 | 知识库是否覆盖 | top1 余弦 |
|:--|:--|:--|
| 增肌期每天应该吃多少蛋白质？ | 是 | 0.9135 |
| 新手一周练几次比较合适？ | 是 | 0.7877 |
| 肌酸怎么吃才有效？ | 是 | 0.7727 |
| 深蹲时膝盖可以超过脚尖吗？ | 是 | **0.7511** |
| 训练后肌肉酸痛还能继续练吗？ | 是 | **0.7327** |
| **碳水循环具体怎么安排？** | **否** | **0.8206** |
| 感冒发烧期间可以去健身房吗？ | 否 | 0.6179 |
| 帮我写一首关于健身的诗 | 否 | 0.5507 |

- 「没被覆盖」的**碳水循环（0.8206）比两条正经问题的分数还高** → 阈值放行它，等于照样把无关资料喂给大模型；
- 「被覆盖」的**训练后酸痛（0.7327）低于 0.75** → 阈值会误杀它，让本该有知识库依据的回答被判成"没覆盖"。

**根因**：同一领域内的密向量检索天生分不开"相关/不相关"（所有问题都落在健身语义空间里）。
所以：**阈值只能当地板，不能当判决。**

#### 3.2.2 实际实现的方案

三条机制叠加，判不出来时保守降级：

1. **地板分 0.55（`rag.RAG_CONTEXT_FLOOR`）**：低于它连资料都不注入 Prompt（例如"写首诗" 0.5507），
   这是纯污染，直接按"没有资料"处理；
2. **让大模型自报有没有用上知识库**：系统提示要求回答**第一行**必须是
   `[[KB:USED]]` 或 `[[KB:MISS]]`（`rag.KB_USED_MARKER` / `KB_MISS_MARKER`）——
   语义判断只有模型自己能做。0.8206 的"碳水循环"正是靠这一步被正确识别为"资料无关"；
   资料分数低于相关性阈值时，还会在 Prompt 里插一句
   `LOW_RELEVANCE_CONTEXT_WARNING`（"资料相关度较低，请先判断能否回答问题"）；
3. **保守兜底**：模型没返回标记、或自称 USED 但根本没给资料 → 一律判为**未使用知识库**
   （宁可把一次真 RAG 回答标成"未使用"，也不能把一份基于无关资料的回答标成"有依据"）。

`_split_kb_marker()` 负责剥标记（大小写不敏感、只看开头 200 字符、剥完不能把标记留在正文里）。

| 层 | 条件 | 行为 | 标记 |
|:--|:--|:--|:--|
| L1 | 命中且模型回 `[[KB:USED]]` | LLM + context（与旧版一致） | `milvus`, `degraded=false`, 来源 `cosine` |
| **L2（新）** | 命中但模型回 `[[KB:MISS]]`（或没回标记） | 丢弃来源，改用大模型通用健身知识回答；正文顶部加 `LOW_RELEVANCE_NOTICE` | `data_source="llm_only"`, `degraded=true`, `sources=[]`, 原因里带最高相似度 |
| L2' | 命中分低于地板分 | 资料**不注入** Prompt，按没覆盖处理 | 同上（原因里注明"未交给大模型"） |
| L2'' | `hits` 为空 / 检索抛异常 | 空 context + `DEGRADED_NOTICE` | `data_source="none"`, `degraded=true` |
| L3 | 有命中但无 LLM Key / `MOCK_MODE=true` | 现有本地拼装 | `milvus`, `degraded=true`（未经润色），分数仍 `cosine` |
| L4 | 检索也不可用 | 内置 18 条兜底 | `builtin`, 分数 `heuristic` |

**关键取舍**：判定"没用知识库"时**来源列表直接清空** —— 用户看到 5 条来源就会认为回答有依据，
这正是要消灭的假象；"不展示 + 明确告知"比"展示但提醒分数低"更不容易误读。

**医疗免责保留**：`_needs_medical_note(question)` 命中的问题在 L2 仍会追加"建议就医"——
恰恰因为这一层没有知识库背书，这条更重要。

#### 3.2.3 改动与测试（已落地）

- `rag.py`：`RAG_CONTEXT_FLOOR` / `LOW_RELEVANCE_NOTICE` / `KB_USED_MARKER` / `KB_MISS_MARKER`
  （标定数据表直接写进 `RAG_CONTEXT_FLOOR` 的注释，避免后人再来一次"为什么不用阈值"的疑问）；
- `agent.py`：`RAG_SYSTEM_PROMPT` 增标记规则、`LOW_RELEVANCE_CONTEXT_WARNING`、
  `_split_kb_marker()`、`_chat_with_real_rag` 四层重构、`chat_with_rag` docstring 重写；
- `models.py` / `types/ai.ts`：`data_source` 取值加 `llm_only` 并写明语义；
- `ChatTab.tsx`：`llm_only` 显示「通用知识回答（未使用知识库）」；
- 测试 +5 例（核心一例直接用 0.8206 这个"高分的无关问题"作为用例），Python 由 401 → **405**，
  前端渲染测试 +1 例（提示可见且**不出现**来源列表）。

### 3.3 对话记忆（G3）

**架构（按拍板结论）**：**MySQL 长期 + Redis 热层**，两级都放在 **Java** 侧 —— Python 保持无状态（不持有跨请求状态，便于横向扩容），符合 BFF 分工。

| 层 | 改动 |
|:--|:--|
| 契约 | `AiChatRequest` 增 `sessionId`（可选）；`AiChatResponse` 增 `sessionId`（加性）；Python `ChatRequest` 增 `history: [{role, content}]` |
| **新表** | `t_ai_chat_history`（见 §5）—— 长期保存每一轮问答，**assistant 行同时记录 `data_source` / `degraded`**，使回看历史时仍能显示"这轮是降级回答"的标记 |
| Redis 热层 | 新 key `fitness:cache:ai:chat:session:{userId}:{sessionId}`（String/JSON 数组），**TTL 2 小时**，每次追加刷新 TTL；单次送模型的窗口上限 **6 轮 / 12 条消息 / 4000 字符**，超限丢最早 |
| 写穿透 | 每轮问答结束把 user + assistant 两条**尽力而为**落库（`try/catch` 吞异常 + warn 日志）：落库失败绝不能影响用户拿到回答 |
| 读回填（read-through） | 取历史时先读 Redis；**miss 则从表里捞最近 6 轮回填 Redis** —— 这样 Redis 重启/过期后不会立刻失忆，这正是选"表 + 热层"而非"只 Redis"的价值 |
| 新服务 | `AiChatSessionService`：`load(userId, sessionId)` / `appendTurn(...)` / `clearHot(userId, sessionId)`；`AiChatHistoryRepository` |
| 归属校验 | Redis key 与 DB 查询都带 `user_id`，且 userId **只从 JWT 取**，绝不接受请求体里的 userId；`sessionId` 必须是 UUID 格式（非法格式直接当新会话，避免拿别人猜到的 id 串会话） |
| 保留期 | 每用户只保留最近 **200 条 / 90 天**：新增每日清理定时任务（沿用 `lock:scheduled:` 防重锁，与现有定时任务同一套机制），超期物理删除 |
| Python | `llm.py` 新增 `chat_with_messages(messages, temperature, max_tokens)`；`agent.py::chat_with_rag(..., history=None)` 组装 `[system, *history, user]`，并对 history 做条数/长度截断与角色白名单校验（防注入） |
| 前端 | `ChatTab` 用 `sessionStorage` 保存 `sessionId`（刷新页面同一会话可续）；"清空"改为"新对话"语义：清 UI + 换新 `sessionId`（**只清 Redis 热层，DB 历史保留**，为后续"历史会话"能力留口子）；`aiApi.chat()` 带上 sessionId |
| 错误码 | 无需新增（无新失败路径：记忆失败一律降级为"无记忆"，不报错） |
| 文档 | 说明两级取舍：TTL 2h 只管热窗口、90 天表里可回看；并说明"本轮仍不做历史会话列表 UI"（§8） |

**验收**：
1. 同一会话内 「深蹲膝盖能超过脚尖吗」→「那做几组」 第二问能正确指代；
2. 点「新对话」后同样两问 → 第二问不再指代（证明会话真被隔离）；
3. **手动 `redis-cli DEL` 掉热 key 后再问 → 仍能指代上文**（证明 read-through 生效，这是"表 + 热层"方案的关键验收点）；
4. 停掉 Redis（或让 DB 抛异常）→ 问答仍正常返回，只是失去记忆，日志有 warn。

### 3.4 基于身体状态的主动问询（G4）

**定位**：把 AI 从"问答机器人"升级为"会追问的教练"。数据由 **Java 组装**（BFF 原则：Python 不查库），Python 只做大模型调用与规则兜底。

**接口**：`POST /api/v1/ai/body-consult`（Java，需登录）→ Python `POST /agent/v1/body-consult`

**Java 侧输入快照**（新 `BodyConsultRequest`，全部来自现有服务，无新表）：
```
user_id, profile{gender, birthDate, height, weight, trainingGoal, trainingLevel, injuryRecord},
latest_metric{recordDate, weightKg, waistCm, armCm, legCm, bodyFatPct},
prev_metric{...},                                   // 上一次体测，用于对比
trend7d{weightDelta, waistDelta, weightAvg7d, samples},
training7d{sessions, totalVolume, avgRpe, muscles[]},
diet{daysRecorded}
```

**Python 输出**（新 `BodyConsultData`）：
```
assessment        : "本周整体判断"（2-3 句）
trend_summary     : 趋势要点（体重/围度方向，含具体数值）
questions[3]      : {id, text, why}   ← 主动追问，why 说明"为什么问这个"
suggestions[3]    : {title, detail}
risk_flags[]      : {level: info|warn|high, text}
data_source       : "llm" | "rule_based"
degraded, degradation_reason, generated_at
```

**规则兜底（无 LLM Key / MOCK_MODE，也保证功能可用）**——阈值示例：
| 触发条件 | 追问 / 提示 |
|:--|:--|
| 本次体重较上次变化 >1% | "一周内体重变化 1.2kg，是刻意增/减脂还是水分波动？" |
| 腰围连续 2 次上升 | `risk_flags: warn` + 饮食执行情况追问 |
| 近 7 天训练 0 次 | "连续 7 天没有训练记录，是作息问题还是伤病？" |
| 近 7 天 `avgRpe ≥ 9` | 过度训练预警 + 恢复/睡眠追问 |
| `bodyFatPct` 缺失 | 提示可选补充体脂率以提高判断精度 |
| 体测样本 <2 条 | 只给"多记几次才能看趋势"的信息，不硬编建议（避免瞎判断） |
`data_source="rule_based"` 时前端显示「规则生成（未使用大模型）」标记。

**触发路径（两条，互不冲突）**：
1. **体测保存成功后** → `ProfilePage` 体测卡片出现「🤖 让 AI 看看我的变化」入口（**点击才请求**，省 token；不自动弹窗打扰）。
2. **AI 页新增入口** → `ChatTab` 顶部加一个"根据我的身体状态提问"按钮，把追问作为该会话的**第一轮问题**注入（天然复用 §3.3 的记忆能力：用户回答追问后可以顺着聊下去）。

**缓存**：`fitness:cache:ai:body-consult:{userId}`（JSON + `inputSnapshot` 指纹），TTL 12 小时；指纹（体测 id/训练条数等）变化即失效 —— 直接复用 `AiSummaryService` 已上线并验证过的"双层缓存 + 快照失效"模式，不新造轮子。

**安全**：`risk_flags=high` 或命中医疗关键词 → 强制追加"建议就医"；不给出诊断式结论（提示词里明确"不做医疗诊断"）。

---

## 4. 跨项一致的工程约束（必须遵守，否则会返工）

1. **DDL 权威**：`spring.jpa.hibernate.ddl-auto: none`，`sql/init.sql` 是唯一事实来源。新增列必须**同时**改 `init.sql` + 存量库迁移脚本，并让 `EntityDdlContractTest` 通过。
2. **加性字段原则**：新增响应字段只加不改（上一轮 `data_source` 的先例），保证前端与旧脚本不炸。
3. **Jackson 陷阱**：Java 跨语言 DTO 带 `@JsonIgnoreProperties(ignoreUnknown = true)`，Python 新字段若不在 Java DTO 里显式声明会**静默消失**（本项目已踩过）。每个新字段都要配 `@JsonProperty` + 契约测试。
4. **Redis key 集中管理**：新 key 必须进 `CacheKeys` 并附 TTL 常量与理由注释（规范要求 TTL 不得永不过期）。
5. **错误码登记**：新错误码写进 `ErrorCode` 并说明区间归属；业务失败仍走 HTTP 200 + `{code,msg,data}`。
6. **降级必有标记**：任何兜底/模拟路径都要带 `data_source`/`degraded`/`degradation_reason`，且前端可见——这是上一轮的整改成果，不能被新功能破坏。
7. **文档同步**：README、`python-agent/README.md`、对应周说明文档随代码一起更新（避免又出现"文档说 Mock、代码已真实"的漂移）。

---

## 5. 数据与迁移

**本次共 2 处 DDL 变更**（均为新增，不改动任何既有列）：

```sql
-- ① t_user 加头像列（sql/migration-20260921-experience.sql）
ALTER TABLE t_user
  ADD COLUMN avatar_url VARCHAR(255) NULL COMMENT '头像访问路径（含版本号，无则为空）'
  AFTER training_level;

-- ② 新增 AI 问答历史表（长期记忆的持久层）
CREATE TABLE IF NOT EXISTS t_ai_chat_history (
    id          BIGINT       AUTO_INCREMENT  PRIMARY KEY,
    user_id     BIGINT       NOT NULL        COMMENT '用户ID',
    session_id  VARCHAR(36)  NOT NULL        COMMENT '会话ID（UUID）',
    msg_role    VARCHAR(16)  NOT NULL        COMMENT '角色：user / assistant',
    content     TEXT         NOT NULL        COMMENT '消息内容',
    data_source VARCHAR(20)                  COMMENT 'assistant 行的来源标记：milvus/llm_only/builtin/none',
    degraded    TINYINT      DEFAULT 0       COMMENT 'assistant 行是否为降级回答：0-否 1-是',
    created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX       idx_user_session (user_id, session_id, id),
    INDEX       idx_user_created (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI问答历史（长期记忆）';
```

**两个刻意的列名决定**：
- 角色列叫 `msg_role` 而不是 `role` —— `ROLE` 是 MySQL 8.0 的保留字（`CREATE ROLE` 语句引入），用 `role` 会让所有 SQL 必须写反引号，且 ORM 生成的语句容易漏。
- `data_source` / `degraded` 冗余进历史表：回看历史时前端仍能显示"这轮是降级回答"，不必回头再算一遍 —— 与本项目"降级必须可见"的原则一致。

**迁移执行方式**：
- `t_user` 的加列：MySQL 8 的 `ADD COLUMN` **不支持 `IF NOT EXISTS`**（MariaDB 才支持）。迁移脚本用 `information_schema.COLUMNS` 判断 + 预处理语句，做到可重复执行，避免"第二次执行直接报错"。
- 新表用 `CREATE TABLE IF NOT EXISTS`，天然可重复执行。
- `sql/init.sql` 同步加列 + 加表（新装库直接带全）。
- 无数据回填：`avatar_url` 为 `NULL` 即"未设置头像"；历史表从零开始积累。
- `EntityDdlContractTest` 会自动覆盖 `avatar_url`（实体 ↔ init.sql 列名契约）；历史表若建实体，也纳入该测试。

---

## 6. 测试与验收矩阵

| 层 | 新增测试 | 目标数 |
|:--|:--|:--|
| Python 单测 | ① 相关性闸门（低相关→`llm_only`、命中→不变、无 LLM→L3）；② `history` 组装/截断/角色校验；③ 规则引擎阈值边界（6 条规则逐条）；④ 医疗免责在 L2 生效 | +14 左右 |
| Java 单测 | ① 头像上传（正常/超限/假图/路径穿越尝试）；② 头像读取 404 与 Content-Type；③ 会话热层读写 + **Redis 挂掉不阻断问答**；④ **read-through**（热层 miss → 回表回填）；⑤ **落库失败不影响返回回答**；⑥ 90 天清理任务边界；⑦ `body-consult` 快照组装（含"无体测数据"路径）；⑧ `EntityDdlContractTest` 覆盖新列 | +18 左右 |
| 前端测试 | ① `llm_only` 提示可见且不显示来源；② `rule_based` 标记；③ 头像上传成功后面板更新；④ "新对话"清空并换 sessionId | +5 左右 |
| 端到端脚本 | 扩 `verify_java_ai_endpoints.py`：新增 `/ai/body-consult` 与低相关问题两例；`_marker_check.py` 式临时脚本验证 `llm_only` 标记跨语言往返不丢；**多轮指代脚本**（连问两轮 → 第二轮体现上文）+ **DEL 热 key 后再问**（验证 read-through） | +6 左右 |

**验收口径**：现有 632 项（Java 208 / Python 401 / 前端 23）全绿 + 新增 ≈35 项全绿 + 真实联调（Java+Python+Milvus）跑通 G1–G4 各一条主路径 + 至少 2 次"临时破坏验证"（例如把相关性闸门去掉，确认新测试会失败——沿用上一轮 `test_high_scoring_fallback_still_carries_the_notice` 的做法，证明测试真的锁得住）。

---

## 7. 实施顺序与工作量

| 批次 | 内容 | 依赖 | 估算 |
|:--|:--|:--|:--|
| **A** | 问答降级闸门（G2） | 无 | 0.5 天 |
| **B** | 头像自定义（G1） | 无 | 0.5–1 天 |
| **C** | 对话记忆（G3，表 + 热层 + 清理任务） | 建议在 A 之后（同一函数区域，避免冲突） | 1–1.5 天 |
| **D** | 身体状态主动问询（G4） | 复用 C 的会话能力 | 1–1.5 天 |
| **E** | 文档同步 + 全量回归 | A–D | 0.5 天 |

合计 **约 3.5–4.5 个工作日**（相比初版 +0.5 天，因会话改为"表 + 热层"两级）。每批独立可交付、可回滚、可单独演示；按 **A → B → C → D → E** 推进（A 改动最小、直接消除"回答其实是编的"这一答辩风险点，性价比最高）。

---

## 8. 明确不做（避免范围蔓延）

- 头像裁剪/滤镜/多尺寸缩略图 —— 只做"上传一张、按 256px 存"。
- **历史会话列表 UI**（会话切换、重命名、删除）—— 本轮只按拍板结论**建好表**，让 `t_ai_chat_history` 具备长期回看能力；列表界面留作后续独立批次，避免本轮范围失控。
- 对话历史的**导出/分享**。
- 知识库扩容到覆盖更多话题 —— 与"LLM 兜底"是两个方向；本计划选择用兜底解决覆盖不足，扩容另立专题。
- 前端全套 UI 改版 —— 只加必要的入口与提示。

---

## 9. 决策记录（已拍板）

| # | 决策 | **结论** | 影响 |
|:--|:--|:--|:--|
| 1 | 头像存哪 | ✅ **本地磁盘 + 控制器读文件** | 零外部依赖；磁盘卫生由"换头像即删旧文件"保证；目录 `./data/avatars` 进 `.gitignore` |
| 2 | 头像怎么读 | ✅ **加进 JWT 白名单，`<img src>` 直连** | 必须做，否则浏览器不带 `Authorization` 头会导致头像永远 401；安全性由"只暴露头像、不含隐私"承担 |
| 3 | 会话存哪 | ✅ **新增 `t_ai_chat_history` 表 + Redis 热层**（写穿透 + 读回填） | 比"仅 Redis"多一张表 + 一个清理任务；换来"Redis 过期/重启后仍能指代上文"与"历史可回看" |
| 4 | 主动问询何时生成 | ✅ **用户点击才生成** | 体测保存后只出现入口，不自动调用大模型，不打扰用户、不浪费 token |
| 5 | LLM 兜底默认开关 | ✅ **默认开启**（低相关即走通用知识，并明确标注"未使用知识库"） | 直接解决"只会背知识库"的体验问题；诚实性由标记保证 |

---

## 10. 复核清单（开工前请确认）

- [x] §9 五个决策点已拍板
- [x] 采纳"低相关来源**不展示**、只告知未使用知识库"的取舍（§3.2）
- [x] 确认头像统一转 JPEG（透明 PNG 会变白底）可接受
- [x] 确认本次 DDL 仅新增：`t_user.avatar_url` 一列 + `t_ai_chat_history` 一张表
- [x] 确认按 A→B→C→D→E 顺序推进

# AI 健身私教 & 体态管家

面向健身人群的智能训练管理系统：**Java 负责业务与鉴权、Python 负责 AI、Milvus 负责语义检索、RabbitMQ 负责异步复盘**。
本科毕业设计项目，采用「Java BFF + Python AI Agent」跨语言架构：前端只与 Java 交互，Java 通过 HTTP 调 Python，耗时任务走消息队列异步回调。

---

## 一、功能概览

**Java 业务层（7 个模块）**

| 模块 | 能力 |
|:---|:---|
| 用户 | 注册、登录（JWT）、档案 CRUD、改密、登出、Token 刷新 |
| 训练记录 | 单条/批量新增、按日期与动作查询、修改删除、自动计算训练容量 |
| 身体数据 | 体重/围度/体脂录入、趋势查询、7 日滑动平均 |
| 饮食记录 | 内置食物热量库、按日统计热量 |
| 训练计划模板 | 三分化/推拉腿/五分化模板，套用生成本周安排 |
| 统计与周计划 | Dashboard 统计卡片、本周统计、最新周计划查询 |
| 定时任务 | 每日 20:00 未训练提醒、每周日 20:00 周统计、**每周日 21:00 触发 AI 复盘消息** |

**AI 能力（Python，5 个同步接口 + 1 条异步链路）**

| 能力 | 实现 |
|:---|:---|
| 训练智能总结 | DeepSeek 生成 100-150 字总结（数字由入参真实计算） |
| 动作智能推荐 | 规则引擎筛候选集 → DeepSeek 生成 → **动作名白名单校验**，失败回退规则引擎 |
| 动作姿态评估 | **通义千问 VL 真实看图**（`qwen-vl-max`）→ 严格 JSON → 本地校验 |
| 健身知识库 RAG 问答 | Embedding → Milvus Top-5 检索 → LLM 生成（**带引用来源**），三层降级 |
| 知识库健康检查 | 真实 Milvus 连接状态、文档数、索引参数、最后更新时间 |
| **每周复盘（异步）** | Java 发 MQ → Python pika 消费 → LLM 生成下周计划 → HMAC 签名回调 Java 落库 |

---

## 二、技术栈

| 层 | 技术 |
|:---|:---|
| 后端 | Java 17+（实测 JDK 21）、Spring Boot 3.2.5、Spring Data JPA、Spring AMQP、Maven 3.9 |
| 鉴权 | JWT（jjwt 0.12.6，含 `jti`/`iat`）+ BCrypt |
| 数据库 | MySQL 8（InnoDB / utf8mb4） |
| 缓存与锁 | Redis 7（缓存、分布式锁、Token 黑名单、MQ 重试计数） |
| 消息队列 | RabbitMQ 3.x（topic exchange + TTL + 死信队列） |
| AI 服务 | Python 3.12、FastAPI 0.139、pydantic v2、uvicorn |
| 大模型 | DeepSeek `deepseek-chat`（文本）、阿里云百炼 `qwen-vl-max`（多模态） |
| 向量库 | Milvus v2.6.0（Docker）+ pymilvus 2.6.16，`IVF_FLAT + COSINE`，知识库 200 条 |
| Embedding | 阿里云百炼 `text-embedding-v3`（768 维；可切本地 `text2vec-base-chinese`） |
| 前端（第 8 周） | React 18 + TypeScript + Vite 5 + Ant Design 5 + ECharts + Zustand |

---

## 三、系统架构

```
┌──────────────────────────── 本机 ────────────────────────────┐
│                                                              │
│  React+TS 前端 :5173 ──HTTP──▶ Java Spring Boot :8080         │
│   （第 8 周）                   │  BFF：业务 + 鉴权 + 聚合       │
│                                 ├──HTTP──▶ Python FastAPI :8000│
│                                 │           （AI Agent）       │
│                                 │              │ pymilvus      │
│                                 │              ▼               │
│                                 │        Milvus :19530 (Docker)│
│                                 │        └ etcd + MinIO        │
│                                 │                              │
│                                 └──RabbitMQ──▶ Python 消费者    │
│                                    ai.weekly.plan   │          │
│                                                     │ HMAC 回调 │
│                                                     ▼          │
│                                          POST /api/ai/callback │
└──────────────────────────────────────────────────────────────┘
                    │                        │
                    ▼                        ▼
        ┌────────────────────┐   ┌──────────────────────┐
        │ 虚拟机 192.168.199.128 │   │ 外部 API              │
        │  MySQL :3306         │   │  DeepSeek / 百炼      │
        │  Redis :6379         │   └──────────────────────┘
        │  RabbitMQ :5672      │
        └────────────────────┘
```

**为什么这样分工**：前端完全不知道 Python 与 Milvus 的存在（Java 作为 BFF）；
实时 AI 走 HTTP 同步返回，耗时复盘走 MQ 异步 + 回调，避免请求线程被大模型拖死。

---

## 四、目录结构

```
agent2/
├── pom.xml                       # Maven 构建（Spring Boot 3.2.5）
├── docker-compose.yml            # 本地 Milvus（含 etcd + MinIO）
├── .env.example                  # Java 侧环境变量模板 → 复制为 .env
├── sql/init.sql                  # 10 张表的建表脚本（含索引与注释）
├── src/main/java/com/fitness/    # Java 源码
│   ├── controller/  service/  repository/  entity/  dto/
│   ├── config/                   # JWT / Redis / RabbitMQ / CORS / RestClient
│   ├── client/                   # AiPythonClient（跨语言唯一出口）
│   ├── cache/                    # 缓存封装、锁、Token 黑名单
│   ├── task/                     # 定时任务 + 死信队列监控
│   └── util/                     # JWT、HMAC 验签、图片压缩
├── src/test/java/com/fitness/    # Java 测试（203 项）
├── python-agent/                 # Python AI 服务（详见其 README）
│   ├── app/                      # 路由 / Agent / LLM / 多模态 / Milvus / MQ 消费者
│   ├── data/seed_knowledge.json  # 知识库种子数据（200 条，5 大分类）
│   ├── scripts/                  # Key 探活、入库工具、端到端验收脚本
│   └── tests/                    # pytest（385 项）
├── 检查报告-第1-3周.md
├── 第4周-AI链路打通说明.md
└── 第5周-真实AI与RAG说明.md
```

> 说明：开发规范 `提示词.txt`（需求与接口契约的原始依据）属个人材料，**未包含在本仓库中**。
> 代码注释里出现的「规范第 N 行」均指该文件，克隆者看不到，属预期；需求要点已在各周说明与本文档中复述。

---

## 五、环境要求

| 依赖 | 要求 | 说明 |
|:---|:---|:---|
| JDK | 17+ | 本机实测 JDK 21（`JAVA_HOME=E:\develop\jdk\jdk21`） |
| Maven | 3.9+ | 本机实测 3.9.4 |
| Python | 3.12（推荐） | 本机用 Anaconda base 环境：`E:\Anaconde\python.exe` |
| Docker | 可运行 `docker compose` | 仅用于启动 Milvus |
| 中间件 | MySQL 8 / Redis 7 / RabbitMQ 3.x | 部署在虚拟机 `192.168.199.128`，**不在本机编排** |
| Node.js | 18+ | 第 8 周前端使用 |

> ⚠️ **Python 只能用 `E:\Anaconde\python.exe`**（本项目依赖装在该环境里）。
> 机器上还有 `E:\conda\python.exe`（3.13）等其它解释器，**没有**项目依赖，直接用会报 `ModuleNotFoundError`。

---

## 六、快速开始

### 0. 准备环境变量（两处，都必须做）

```powershell
Copy-Item .env.example .env                          # Java 侧：数据库/Redis/MQ 密码、JWT/HMAC 密钥
Copy-Item python-agent\.env.example python-agent\.env # Python 侧：大模型/Embedding/多模态 Key
# 然后按注释填入真实值。.env 已被 .gitignore 忽略，不会进版本库。
```

### 1. 初始化数据库

```powershell
mysql -h 192.168.199.128 -u root -p < sql/init.sql
```

### 2. 启动 Milvus（本机 Docker）

```powershell
docker compose up -d
docker compose ps          # 等 milvus 变成 healthy（首次约 1 分钟）
```

> 若拉取镜像超时，见 `docker-compose.yml` 顶部的镜像加速器说明。

### 3. 安装 Python 依赖并启动 AI 服务

```powershell
cd python-agent
E:\Anaconde\python.exe -m pip install -r requirements.txt
E:\Anaconde\python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

启动时会自动检查并初始化知识库（200 条，幂等）。首次接入真实 AI 前建议先探活：

```powershell
E:\Anaconde\python.exe scripts/check_api_keys.py     # 大模型 / Embedding / 多模态 三项
```

### 4. 启动 Java 服务（另开终端，工作目录必须是项目根目录）

```powershell
mvn spring-boot:run
# 或 IDE 直接运行 com.fitness.FitnessApplication
```

验证：<http://localhost:8080/api/v1/health>　接口文档：<http://localhost:8080/doc.html>

### 5. 启动周计划消费者（第 7 周异步链路，另开终端长驻）

```powershell
cd python-agent
E:\Anaconde\python.exe -m app.mq_consumer
```

### 6. 前端（第 8 周，尚未实现）

```powershell
npm create vite@latest fitness-frontend -- --template react-ts
```

---

## 七、测试与验收

```powershell
# Java：203 项
mvn test

# Python：385 项（不联网）
cd python-agent; E:\Anaconde\python.exe -m pytest tests -q
```

**端到端验收脚本**（需对应服务在线）：

| 脚本 | 验证内容 | 前置 |
|:---|:---|:---|
| `scripts/check_api_keys.py` | 大模型 / Embedding / 多模态 Key 三项探活 | 联网 |
| `scripts/verify_pose_multimodal.py` | 姿态评估真实多模态（可传自己的照片 `--image`） | 百炼 Key |
| `scripts/verify_summary_layers.py` | AI 总结双层缓存与变更失效 | Python + Redis |
| `scripts/verify_java_ai_endpoints.py` | Java 5 个 `/api/ai/*` 接口 | Java + Python |
| `scripts/ingest_knowledge.py --stats` | 知识库统计与索引参数一致性 | Milvus |
| `scripts/verify_weekly_plan_chain.py` | **每周复盘异步全链路**（发送→消费→回调→验签→落库） | Java + RabbitMQ |

---

## 八、当前进度

| 周次 | 内容 | 状态 |
|:---|:---|:---|
| 第 1-3 周 | Java 业务层：用户/训练/身体数据/饮食/模板 + 缓存 + 定时任务 | ✅ |
| 第 4 周 | Python FastAPI 骨架 + 跨语言 HTTP 链路打通 | ✅ |
| 第 5 周 | Milvus + 知识库 200 条 + AI 训练总结 + RAG 问答 | ✅ |
| 第 6 周 | 真实多模态姿态评估 + 动作推荐接 LLM + 知识入库工具 | ✅ |
| 第 7 周 | RabbitMQ 异步闭环：发送 → 消费 → LLM → 回调 → 验签落库 | ✅ |
| 第 8 周 | React+TS 前端联调 + Compose 编排 + 文档收尾 | ⏳ 待开始 |

---

## 九、关键设计（答辩要点）

| 主题 | 做法 |
|:---|:---|
| JWT 失效 | 黑名单按 **jti** 粒度存（多设备互不覆盖）+ `iat` 水位线实现「改密即全端失效」 |
| 回调安全 | HMAC 签名原文 = `method\npath\ntimestamp\nsha256Hex(rawBody)`，**Java 与 Python 有跨语言黄金向量锁死一致性**；±5 分钟防重放 + Redis 幂等锁 + DB 唯一索引兜底 |
| 消息可靠性 | 只有「LLM 成功 + 回调成功」才 ACK；失败 nack 重投，重试 >3 次进死信；队列 TTL 1 小时（不是 7 天）；死信深度监控告警 |
| 姿态评估图片 | Java 先压缩到 ≤1MB 再 Base64（原图 Base64 会膨胀 33%）；Python 侧做解码体积防御 |
| 检索参数 | `nlist=16`（经验值 ≈√N，N=200）、`nprobe=4`；启动时校验索引参数与配置是否一致 |
| 业务口径 | 7 日滑动平均「缺失日期不补 0、样本 <3 返回 null」；连续训练天数「今日未练不算断」 |
| AI 降级 | 入参问题（9003）原样返回给用户；服务不可用（6001/6002）才套兜底文案 |

---

## 十、注意事项

1. **`.env` 不入库**：项目根目录与 `python-agent/` 各有一份，含真实密钥，已被 `.gitignore` 忽略。首次克隆请从 `.env.example` 复制并填入自己的值。
   - ⚠️ **回调签名密钥（`HMAC_SECRET`）必须在两侧保持一致**（Java 根目录 `.env` ↔ `python-agent/.env`），且**不要**用示例值：回调接口在 JWT 白名单里，验签是它唯一的安全边界。
   - 同理，请勿把真实密码/密钥写回 `.env.example`——那个文件是要提交进 Git 的模板。
2. **依赖不在仓库里**：Python 依赖由 `python-agent/requirements.txt` 声明，Java 依赖由 `pom.xml` 管理，Milvus 由 `docker-compose.yml` 编排——这是正常的，克隆后按第六节步骤即可跑起来。
3. **任务书/开题报告等 `.docx` 已被排除**（含学号与姓名）。需要提交时可注释掉 `.gitignore` 中的 `*.docx`。
4. **开发规范 `提示词.txt` 未包含在本仓库**（个人需求材料）。代码注释里的「规范第 N 行」均指该文件，克隆者看不到，属预期。
5. 各周详细说明见 `检查报告-第1-3周.md`、`第4周-AI链路打通说明.md`、`第5周-真实AI与RAG说明.md`，Python 侧细节见 `python-agent/README.md`。

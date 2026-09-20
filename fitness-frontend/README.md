# fitness-frontend — AI 健身私教 & 体态管家 前端

React 18 + TypeScript（**强制开启**，strict 模式）+ Vite 5 构建的单页应用。

> **只与 Java 后端交互**：所有请求打到同源的 `/api`，由 Vite dev server 代理到
> `http://localhost:8080`。前端完全不知道 Python、FastAPI、Milvus 的存在
> —— 这是刻意的「BFF 边界」，跨语言与 AI 的复杂度全部收在 Java 里。

---

## 1. 技术栈

| 关注点 | 选型 |
|:---|:---|
| 构建 | Vite 5（`@vitejs/plugin-react`） |
| 语言 | TypeScript 5.5，`strict: true` + `noUnusedLocals/Parameters` |
| UI | Ant Design 5 + `@ant-design/icons`（中文 locale `zhCN`） |
| 样式 | Tailwind CSS 3（**只承担布局辅助类**，组件外观交给 antd） |
| 路由 | React Router v6（`createBrowserRouter`） |
| 状态 | Zustand 4（登录态 + 今日训练本地状态） |
| 数据请求 | TanStack Query 5 + Axios（统一实例与拦截器） |
| 图表 | ECharts 5（`echarts-for-react`） |
| 内容渲染 | react-markdown 9 + remark-gfm |
| 时间 | dayjs（已设 `zh-cn`） |

---

## 2. 快速开始

```powershell
npm install
npm run dev          # http://localhost:5173
```

**前置**：Java 后端必须已在 8080 运行（AI 相关页面还要求 Python 在 8000）。
Vite 只做代理，不会替你启动后端。

其它脚本：

```powershell
npm run typecheck    # tsc --noEmit（严格模式）
npm run build        # 先 tsc 再 vite build，产物在 dist/
npm run preview      # 预览构建产物（4173）
```

---

## 3. 目录结构

```
src/
├── api/                      # 与后端一一对应的请求层
│   ├── client.ts             # Axios 单例 + 请求/响应拦截器（含 Token 静默刷新）
│   ├── userApi.ts            # 用户 7 个接口
│   ├── trainingApi.ts        # 训练记录 7 个接口
│   ├── bodyMetricApi.ts      # 身体数据 4 个接口
│   ├── dietApi.ts            # 饮食 4 个接口
│   ├── foodLibraryApi.ts     # 食物库 3 个接口
│   ├── workoutApi.ts         # 训练计划 2 个接口
│   ├── aiApi.ts              # AI 代理 5 个接口
│   └── statsApi.ts           # 统计 + 周计划 3 个接口
├── types/                    # 与后端字段逐一对齐的类型（含枚举与错误码）
├── store/                    # Zustand：userStore / trainingStore
├── router/                   # 路由表 + ProtectedRoute 守卫
├── components/
│   ├── common/               # AppBridge / ErrorBoundary / MarkdownView
│   ├── layout/               # AppLayout（Sider + Drawer 响应式）
│   ├── auth/                 # AuthTabs / LoginForm / RegisterForm
│   ├── dashboard/            # DateHeader / StatsCards / QuickRecordForm / TodayRecordsTable
│   └── ai/                   # SummaryTab / RecommendTab / PoseTab / ChatTab
├── pages/                    # LoginPage / DashboardPage / AIAssistantPage / TrendsPage / ProfilePage
├── utils/                    # bridge（React↔非React 桥） / tokenStorage
├── App.tsx                   # RouterProvider
└── main.tsx                  # ConfigProvider + AntdApp + QueryClientProvider + 启动恢复登录态
```

---

## 4. 几个关键设计（答辩可能被问到）

### 4.1 错误处理为什么全在「响应成功」分支里

后端**业务失败也返回 HTTP 200**（包括鉴权失败 9001），错误码只体现在 `code` 字段。
所以 axios 的 `catch` 分支只处理网络层问题（断网 / 超时 / 5xx），
业务错误一律在响应拦截器里按 `code` 分流：

| code | 处理 |
|:---|:---|
| 0 | 拆掉信封 `{code,msg,data}`，把 `data` 交给调用方 |
| 9001 | 清 store 与 storage → 提示「登录已过期」→ 跳 `/login`（**2 秒去抖**，避免并发请求弹一串提示） |
| 9002 | 提示「请求校验失败」并 `console.error`（属内部回调链路异常，前端一般遇不到） |
| 9003 | `message.warning(后端 msg)`，不跳转 |
| 1002 / 1003 / 2003 / 3001 / 4001 / 6001-6003 | **静默**，由页面自己 catch 展示 —— 否则会出现「拦截器弹一次 + 页面再弹一次」的双提示 |
| 其它非 0 | `message.error(msg)` |

> 拦截器里用的 `message` 来自 `App.useApp()`，通过 `utils/bridge` 注入 ——
> antd v5 的静态 `message.xxx` 拿不到 ConfigProvider 的上下文（主题/语言会失效）。

### 4.2 Token 静默刷新与去抖

拦截器解析 JWT 的 `exp`：

- 剩余有效期 **> 24h** → 原样发送
- 剩余 **< 24h** → 先 `POST /api/v1/user/refresh`，成功后再发原请求（用刷新后的 Token）
- 已过期或刷新失败 → 不阻断原请求，真失效了由 9001 统一处理

并发请求共享同一个刷新 Promise，保证只刷一次；刷新请求走**不带拦截器的裸实例**，
否则会递归触发自己。

### 4.3 「记住我」的存储选择

规范要求 Token 按「记住我」落 `localStorage` 或 `sessionStorage`。
zustand 的 `persist` 中间件在创建时就固定了 storage，无法在运行期二选一，
因此持久化收敛到 `utils/tokenStorage`（纯函数、React 之外也能用，拦截器依赖它），
store 只把结果镜像成 React 状态。

### 4.4 乐观更新的回滚策略

提交训练记录时先插入占位行（**负数临时 id**，与服务端自增 id 不会冲突），
接口成功后用后端返回的权威记录（含后端算出的 `volume`）替换；
失败则恢复 mutation 之前的**整表快照**——一次批量提交可能插入多条占位行，
逐条撤销要处理「哪几条成功了」的分支，快照回滚更简单且不会漏。

### 4.5 类型对齐：不让类型说谎

- 登录接口只返回 `{id,nickname,gender,trainingGoal}`，因此 store 里 `user` 的类型是
  `UserBrief` 而**不是** `UserProfile` —— 否则页面读 `phone`/`height` 时类型检查会通过、
  运行时却是 `undefined`。完整档案由 Profile 页面自己拉。
- 后端有若干返回 `Map<String,Object>` 的接口（dashboard、weekly、by-action 等），
  `src/types/stats.ts` 等按 Service 里 `result.put(...)` 的真实键名逐一建模。
- 时间字段统一是 `yyyy-MM-dd` / `yyyy-MM-dd HH:mm:ss` 字符串（不是 ISO 的 `T` 格式）。

### 4.6 体重有两套口径（最容易搞混）

| 字段 | 含义 | 何时更新 |
|:---|:---|:---|
| `t_user.weight` | 注册时填的**初始体重** | 仅用户手动改档案时 |
| `t_body_metric.weight_kg` | 日常**跟踪体重** | 每次体测录入 |

Dashboard 与 Trends 的「最新体重」**始终取 `t_body_metric`**；两者独立维护、不自动同步。
Profile 页面把这两件事拆成两个互不影响的表单。

---

## 5. 与规范的偏差（都是有意的，逐条说明理由）

| # | 规范原文 | 实际实现 | 原因 |
|:---|:---|:---|:---|
| 1 | 快捷录入的每一行是「第 N 组」，只含重量/次数/RPE | 每一行是**一个动作条目**（动作名/组数/次数/重量/RPE），一次提交走批量接口 | 后端一条记录是「一个动作 + 一个 `sets` 标量」，没有「一组一行」的模型。逐组建记录会让今日列表碎成「1组×10次」；强行合并又会丢各组不同重量。当前实现两种用法都支持且不丢数据 |
| 2 | 注册页「手机号失焦时调用接口判断是否已注册」 | 失焦只做格式校验，提交时后端返回 1001 再提示并切到登录 Tab | 后端**没有**「手机号查重」接口；照字面实现只能去调注册接口，那会真的创建用户 |
| 3 | `persist` 中间件持久化 Token | 自建 `tokenStorage` | 见 4.3，persist 无法按「记住我」在运行期切换 storage |
| 4 | 用户列表页展示 `user: UserProfile` | store 存 `UserBrief` | 见 4.5，避免类型说谎 |
| 5 | AI 接口沿用全局 15s 超时 | AI 接口单独放宽（普通 60s、姿态评估 90s） | 姿态评估要走多模态真实推理，实测可达数十秒；15s 会让用户只看到「请求超时」 |
| 6 | 动作推荐页的 `count` 固定 | 传 5 | 规范图中展示 5 张卡片，后端默认也是 5 |
| 7 | 行内编辑可改动作名 | 只开放组数/次数/重量/RPE | 后端 `TrainingRecordUpdateRequest` **没有** `actionName` 字段，放一个改了无效的输入框更糟 |

---

## 6. 已知限制

- 对话历史只存在组件 `useState` 中，离开 AI 页面即清空（规范的安全要求，不是缺陷）。
- 趋势页的「每周容量」按周逐个调用 `/v1/stats/weekly`（后端没有多周聚合接口），
  区间上限约 13 周，避免一次打出几十个请求。
- 未做单元测试与 E2E（规范对前端只要求「页面跑通全流程」），
  质量由 `tsc` 严格模式 + 生产构建 + 对真实后端的联调冒烟覆盖。

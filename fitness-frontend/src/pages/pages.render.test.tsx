import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { App as AntdApp, ConfigProvider } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import type { ReactElement } from 'react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * 页面渲染冒烟测试
 *
 * <h3>为什么需要它</h3>
 * `tsc` 只能证明「类型对」、`vite build` 只能证明「模块能打包」，
 * 两者都抓不到**首帧运行时错误**：Hook 用错位置、`App.useApp()` 取不到 Context、
 * 解构了 `undefined` 的响应、在渲染期访问未初始化的状态……
 * 这类问题在浏览器里表现为整页白屏，而构建是绿的。
 *
 * 本文件把每个页面在 jsdom 里真实挂载一次，断言关键文案出现，
 * 以此替代「人眼点一遍」中最关键的那部分：**页面能不能渲染出来**。
 *
 * 接口全部打桩（不联网、不依赖后端），因此它是可重复、可进 CI 的。
 */

// ==================== 接口打桩 ====================

const mockDashboard = {
  date: '2026-09-20',
  todayActionCount: 3,
  todayTotalVolume: 3390,
  consecutiveTrainingDays: 1,
  weeklyTrainingDays: 1,
  latestWeight: 70.5,
  weightChange7d: null,
  weightAvg7d: null,
}

vi.mock('@/api/statsApi', () => ({
  default: {
    getDashboard: vi.fn(() => Promise.resolve(mockDashboard)),
    getWeeklyStats: vi.fn(() =>
      Promise.resolve({
        weekStart: '2026-09-14',
        weekEnd: '2026-09-20',
        trainingDays: 3,
        totalActions: 9,
        totalVolume: 16800,
        avgRpe: 7.5,
        topActions: [{ actionName: '杠铃卧推', totalVolume: 2400, count: 1 }],
        bodyMetrics: { startWeight: 70, endWeight: 71, weightChange: 1 },
        avgCalories: 1800,
      }),
    ),
    getLatestWeeklyPlan: vi.fn(() => Promise.resolve(null)),
  },
}))

vi.mock('@/api/trainingApi', () => ({
  default: {
    getTodayRecords: vi.fn(() =>
      Promise.resolve([
        {
          id: 1,
          userId: 1,
          trainingDate: '2026-09-20',
          actionName: '杠铃卧推',
          sets: 4,
          reps: 10,
          weightKg: 60,
          durationMin: null,
          rpe: 8,
          volume: 2400,
          remark: null,
          createdAt: '2026-09-20 16:00:00',
          updatedAt: '2026-09-20 16:00:00',
        },
      ]),
    ),
    addRecord: vi.fn(),
    batchAddRecords: vi.fn(),
    updateRecord: vi.fn(),
    deleteRecord: vi.fn(),
    queryByAction: vi.fn(),
    queryByDateRange: vi.fn(() => Promise.resolve({ list: [], total: 0, page: 1, size: 50 })),
  },
}))

vi.mock('@/api/workoutApi', () => ({
  default: {
    listTemplates: vi.fn(() =>
      Promise.resolve({
        list: [
          {
            id: 1,
            templateName: '三分化',
            description: null,
            splitType: '三分化',
            targetLevel: '新手',
            exercises: [
              {
                dayOfCycle: 1,
                dayLabel: '胸+三头',
                actionName: '杠铃卧推',
                targetMuscle: '胸',
                recommendedSets: '3-4',
                recommendedReps: '8-12',
                notes: null,
              },
            ],
          },
        ],
      }),
    ),
    applySchedule: vi.fn(),
  },
}))

vi.mock('@/api/bodyMetricApi', () => ({
  default: {
    getLatest: vi.fn(() => Promise.resolve(null)),
    getTrend: vi.fn(() => Promise.resolve({ list: [], latestWeight: null, weightChange: null, totalRecords: 0 })),
    addMetric: vi.fn(),
    updateMetric: vi.fn(),
  },
}))

vi.mock('@/api/userApi', () => ({
  default: {
    getProfile: vi.fn(() =>
      Promise.resolve({
        id: 1,
        nickname: '测试用户',
        gender: 1,
        birthDate: null,
        height: 175,
        weight: 70,
        trainingGoal: '增肌',
        trainingLevel: '新手',
        avatarUrl: null,
        injuryRecord: [],
        phone: '138****8000',
        createdAt: '2026-09-20 16:00:00',
      }),
    ),
    updateProfile: vi.fn(),
    changePassword: vi.fn(),
    login: vi.fn(),
    register: vi.fn(),
    logout: vi.fn(),
    refresh: vi.fn(),
    uploadAvatar: vi.fn(),
  },
}))

vi.mock('@/api/aiApi', () => ({
  default: {
    generateSummary: vi.fn(),
    recommend: vi.fn(),
    evaluatePose: vi.fn(),
    chat: vi.fn(),
    newChatSession: vi.fn(),
    knowledgeHealth: vi.fn(),
    // 批次 D：身体状态主动问询。新增的 api 方法必须登记在这里，
    // 否则调用处拿到的是 undefined，报错会落在被测代码上而不是测试上
    bodyConsult: vi.fn(),
  },
}))

vi.mock('@/api/dietApi', () => ({
  default: { addRecord: vi.fn(), queryByDate: vi.fn(), getDailyStats: vi.fn(), getStatsByRange: vi.fn() },
}))

// 页面必须在打桩之后再导入（vi.mock 会被提升，但显式放在下面更直观）
import AIAssistantPage from '@/pages/AIAssistantPage'
import DashboardPage from '@/pages/DashboardPage'
import LoginPage from '@/pages/LoginPage'
import NotFoundPage from '@/pages/NotFoundPage'
import ProfilePage from '@/pages/ProfilePage'
import TrendsPage from '@/pages/TrendsPage'

// ==================== 与 main.tsx 一致的 Provider 栈 ====================

/**
 * 与 main.tsx 一致的 Provider 栈
 *
 * @param route 初始路径。**路由相关用例必须显式传入**：
 *   `MemoryRouter` 默认从 `/` 开始，而 `/` 既不匹配 `/dashboard` 也不匹配 `/login`，
 *   结果是「什么都不渲染」，测试会以「找不到元素」失败 —— 那是测试自己写错了，
 *   不是被测代码的问题。
 */
function renderWithProviders(ui: ReactElement, route = '/') {
  // retry: false —— 失败立刻暴露，而不是被 react-query 重试掩盖
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <ConfigProvider locale={zhCN} theme={{ token: { colorPrimary: '#1677ff' } }}>
      <AntdApp>
        <QueryClientProvider client={queryClient}>
          <MemoryRouter initialEntries={[route]}>{ui}</MemoryRouter>
        </QueryClientProvider>
      </AntdApp>
    </ConfigProvider>,
  )
}

beforeEach(() => {
  // 每个用例都从未登录状态开始，避免用例间相互影响
  window.localStorage.clear()
  window.sessionStorage.clear()
})

// ==================== 用例 ====================

describe('页面渲染冒烟', () => {
  it('登录页：渲染标题与登录/注册双 Tab', async () => {
    renderWithProviders(<LoginPage />)

    expect(await screen.findByText(/AI健身私教/)).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: '登录' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: '注册' })).toBeInTheDocument()
    // 登录表单的核心字段
    expect(screen.getByPlaceholderText('请输入手机号')).toBeInTheDocument()
    expect(screen.getByPlaceholderText('请输入密码')).toBeInTheDocument()
    expect(screen.getByText(/记住我/)).toBeInTheDocument()
  })

  it('训练看板：渲染统计卡片、快捷录入与今日记录', async () => {
    renderWithProviders(<DashboardPage />)

    // 统计卡片标题（数字来自打桩接口）
    expect(await screen.findByText('今日训练动作数')).toBeInTheDocument()
    expect(screen.getByText('今日总容量')).toBeInTheDocument()
    expect(screen.getByText('连续训练天数')).toBeInTheDocument()
    expect(screen.getByText('最新体重')).toBeInTheDocument()

    // 快捷录入表单
    expect(screen.getByText('快捷录入')).toBeInTheDocument()
    expect(screen.getByText('提交训练记录')).toBeInTheDocument()
    // 行数上限提示（后端 @Size(max=20)）
    expect(screen.getByText('1 / 20 个动作')).toBeInTheDocument()

    // 今日记录表格：标题与打桩数据里的动作名
    expect(screen.getByText('今日已记录')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByText('杠铃卧推')).toBeInTheDocument())
  })

  it('AI 助手：渲染四个 Tab 标题', async () => {
    renderWithProviders(<AIAssistantPage />)

    for (const label of ['训练总结', '动作推荐', '姿态评估', '💬 健身问答']) {
      expect(await screen.findByRole('tab', { name: label })).toBeInTheDocument()
    }
    // 默认激活的是训练总结，应有生成按钮
    expect(screen.getByText('生成今日AI总结')).toBeInTheDocument()
  })

  it('数据趋势：渲染时间范围选择器与图表容器', async () => {
    renderWithProviders(<TrendsPage />)

    expect(await screen.findByText('最近7天')).toBeInTheDocument()
    expect(screen.getByText('30天')).toBeInTheDocument()
    expect(screen.getByText('90天')).toBeInTheDocument()
    expect(screen.getByText('自定义')).toBeInTheDocument()
  })

  it('个人档案：渲染档案表单与体测表单（均来自打桩接口）', async () => {
    renderWithProviders(<ProfilePage />)

    expect(await screen.findByDisplayValue('测试用户')).toBeInTheDocument()
    expect(screen.getByText('编辑资料')).toBeInTheDocument()
    expect(screen.getByText('记录身体数据')).toBeInTheDocument()
    // 手机号是脱敏只读展示
    expect(screen.getByDisplayValue('138****8000')).toBeInTheDocument()
  })

  it('404 页：渲染 Result 与返回首页按钮', async () => {
    renderWithProviders(<NotFoundPage />)

    expect(await screen.findByText('页面未找到')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '返回首页' })).toBeInTheDocument()
  })
})

describe('错误边界', () => {
  it('子组件渲染抛异常时展示兜底 UI，而不是整页白屏', async () => {
    const { default: ErrorBoundary } = await import('@/components/common/ErrorBoundary')

    // React 会把渲染期异常打到 console.error，这里静音以免污染测试输出
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {})

    function Boom(): ReactElement {
      throw new Error('渲染期故意抛错')
    }

    renderWithProviders(
      <ErrorBoundary>
        <Boom />
      </ErrorBoundary>,
    )

    expect(await screen.findByText('页面出错了')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '刷新页面' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '返回首页' })).toBeInTheDocument()

    spy.mockRestore()
  })
})

describe('路由守卫', () => {
  it('未登录访问受保护路由 → 重定向到登录页', async () => {
    const { default: ProtectedRoute } = await import('@/router/ProtectedRoute')
    const { default: useUserStore } = await import('@/store/userStore')

    // 显式清成未登录（模块级注册过 authBridge，但登录态在 store 里）
    useUserStore.getState().clearSession()

    renderWithProviders(
      <Routes>
        <Route element={<ProtectedRoute />}>
          <Route path="/dashboard" element={<div>受保护内容</div>} />
        </Route>
        <Route path="/login" element={<div>登录页占位</div>} />
      </Routes>,
      '/dashboard',
    )

    expect(await screen.findByText('登录页占位')).toBeInTheDocument()
    expect(screen.queryByText('受保护内容')).not.toBeInTheDocument()
  })

  it('已登录访问受保护路由 → 渲染子路由内容', async () => {
    const { default: ProtectedRoute } = await import('@/router/ProtectedRoute')
    const { default: useUserStore } = await import('@/store/userStore')

    useUserStore.getState().login('fake-token', {
      id: 1,
      nickname: '测试用户',
      gender: 1,
      trainingGoal: '增肌',
    })

    renderWithProviders(
      <Routes>
        <Route element={<ProtectedRoute />}>
          <Route path="/dashboard" element={<div>受保护内容</div>} />
        </Route>
        <Route path="/login" element={<div>登录页占位</div>} />
      </Routes>,
      '/dashboard',
    )

    expect(await screen.findByText('受保护内容')).toBeInTheDocument()
    expect(screen.queryByText('登录页占位')).not.toBeInTheDocument()
  })
})

// ==================== 头像（批次 B） ====================

describe('头像自定义', () => {
  // 模块级 mock 的实现会跨用例累积调用次数，这里清一下调用记录
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('档案页提供"更换头像"入口（此前完全没有这个功能）', async () => {
    const { container } = renderWithProviders(<ProfilePage />)

    await screen.findByText('测试用户')
    // 可点的 file input + 头像上的「更换头像」提示，两者缺一用户就不知道头像能换
    // （不断言 Tooltip 文案：antd 的 Tooltip 只在 hover 后才进 DOM）
    expect(container.querySelector('input[type="file"]')).not.toBeNull()
    expect(screen.getByText('更换头像')).toBeInTheDocument()
    // accept 限定只让 JPG/PNG 进入选择框，浏览器层面就先挡一道
    expect(container.querySelector('input[type="file"]')).toHaveAttribute(
      'accept',
      'image/jpeg,image/png',
    )
  })

  it('选中图片后调用上传接口，并把文件原样交给它', async () => {
    const userApi = (await import('@/api/userApi')).default
    vi.mocked(userApi.uploadAvatar).mockResolvedValue({
      avatarUrl: '/api/v1/user/avatar/1?v=1789999999999',
    })

    const { container } = renderWithProviders(<ProfilePage />)
    await screen.findByText('测试用户')

    const input = container.querySelector('input[type="file"]') as HTMLInputElement
    await userEvent.upload(input, new File(['x'], 'a.png', { type: 'image/png' }))

    // beforeUpload 返回 false → antd 不会自己发请求，必须由我们的 mutation 发出去
    await waitFor(() => expect(userApi.uploadAvatar).toHaveBeenCalledTimes(1))
    expect(vi.mocked(userApi.uploadAvatar).mock.calls[0][0]).toMatchObject({ name: 'a.png' })
  })

  it('超过 2MB 的图片在前端就被拦下，不发请求（服务端仍会独立校验一遍）', async () => {
    const userApi = (await import('@/api/userApi')).default

    const { container } = renderWithProviders(<ProfilePage />)
    await screen.findByText('测试用户')

    const input = container.querySelector('input[type="file"]') as HTMLInputElement
    // 注意：类型不合法（如 gif）的用例在这里模拟不出来 —— `accept` 属性会让
    // userEvent/browser 直接把文件过滤掉，永远走不到 beforeUpload。所以这里测大小上限。
    const tooBig = new File([new Uint8Array(2 * 1024 * 1024 + 1)], 'big.png', {
      type: 'image/png',
    })
    await userEvent.upload(input, tooBig)

    await waitFor(() =>
      expect(screen.getByText('头像最大 2MB，请压缩后再上传')).toBeInTheDocument(),
    )
    expect(userApi.uploadAvatar).not.toHaveBeenCalled()
  })

  it('档案同步到登录态时带上 avatarUrl（否则刷新页面后顶栏头像会丢）', async () => {
    const { useUserStore } = await import('@/store')
    const url = '/api/v1/user/avatar/1?v=1789999999999'

    useUserStore.getState().syncFromProfile({
      id: 1,
      nickname: '测试用户',
      gender: 1,
      birthDate: null,
      height: 175,
      weight: 70,
      trainingGoal: '增肌',
      trainingLevel: '新手',
      avatarUrl: url,
      injuryRecord: [],
      phone: '138****8000',
      createdAt: '2026-09-20 16:00:00',
    })

    expect(useUserStore.getState().user?.avatarUrl).toBe(url)
  })

  // 说明：这里不断言页面上的 <img src>。antd 的 Avatar 只在图片 onLoad 之后才渲染 <img>，
  // 而 jsdom 不加载图片资源 —— 断言它只会写出一条永远"通过"或永远"失败"的假用例。
  // 真正要守的两件事分别在「上传接口被调用」与「store 带上 avatarUrl」两个用例里。
})

// ==================== 降级 / 模拟标记在界面上的可见性 ====================

describe('AI 标记的界面可见性', () => {
  it('问答走内置兜底时：显示「降级回答」与原因，并把分数标明为合成分数', async () => {
    // 旧实现的问题：内置 18 条兜底返回的 sources[].score 是启发式合成值
    //（0.62 + 0.08*命中数 + 0.15*重合度），却与真实余弦相似度同形；
    // 而且「命中的好」时连降级提示都没有 —— 界面上与真实 RAG 完全一致。
    const aiApi = (await import('@/api/aiApi')).default
    vi.mocked(aiApi.chat).mockResolvedValue({
      question: '深蹲时膝盖可以超过脚尖吗？',
      answer: '## 可以\n\n适度超过脚尖是正常的。',
      sources: [
        {
          category: '动作要领',
          title: '深蹲时膝盖与脚尖的位置关系',
          content: '膝盖沿脚尖方向外推即可。',
          score: 0.97,
          scoreType: 'heuristic',
        },
      ],
      dataSource: 'builtin',
      degraded: true,
      degradationReason: '知识库检索不可用，已退化为内置知识条目（相关度为启发式估计值）',
      sessionId: '3f1c8b9e-6a2d-4f5b-9c7e-1d2a3b4c5d6e',
      generatedAt: '2026-09-21 12:00:00',
    })

    const user = userEvent.setup()
    renderWithProviders(<AIAssistantPage />)

    await user.click(await screen.findByRole('tab', { name: '💬 健身问答' }))
    await user.click(
      await screen.findByRole('button', { name: '深蹲时膝盖可以超过脚尖吗？' }),
    )

    // 1) 降级标记可见
    expect(await screen.findByText('降级回答（内置知识条目）')).toBeInTheDocument()
    expect(screen.getByText(/已退化为内置知识条目/)).toBeInTheDocument()

    // 2) 分数口径被如实标注，而不是伪装成余弦相似度
    expect(screen.getByText(/相关度为启发式合成分数，非向量相似度/)).toBeInTheDocument()
    expect(screen.getByText(/合成分数 0.97/)).toBeInTheDocument()
  })

  it('真实 RAG 回答：不出现任何降级标记，分数按余弦相似度展示', async () => {
    const aiApi = (await import('@/api/aiApi')).default
    vi.mocked(aiApi.chat).mockResolvedValue({
      question: '深蹲时膝盖可以超过脚尖吗？',
      answer: '## 可以\n\n真实检索结果生成的回答。',
      sources: [
        {
          category: '动作要领',
          title: '深蹲站距与脚尖外展',
          content: '个体化选择。',
          score: 0.7502,
          scoreType: 'cosine',
        },
      ],
      dataSource: 'milvus',
      degraded: false,
      degradationReason: null,
      sessionId: '3f1c8b9e-6a2d-4f5b-9c7e-1d2a3b4c5d6e',
      generatedAt: '2026-09-21 12:00:00',
    })

    const user = userEvent.setup()
    renderWithProviders(<AIAssistantPage />)

    await user.click(await screen.findByRole('tab', { name: '💬 健身问答' }))
    await user.click(
      await screen.findByRole('button', { name: '深蹲时膝盖可以超过脚尖吗？' }),
    )

    expect(await screen.findByText(/分数为 Milvus 余弦相似度/)).toBeInTheDocument()
    expect(screen.queryByText(/降级回答/)).not.toBeInTheDocument()
    // 余弦分数保留 4 位小数（与真实接口一致的展示口径）
    expect(screen.getByText('0.7502')).toBeInTheDocument()
  })

  it('知识库没覆盖该问题时：显示「通用知识回答（未使用知识库）」且不展示来源', async () => {
    // 这是用户实际反馈的体验问题：问到知识库没覆盖的话题（例如碳水循环）时，
    // 旧实现会把无关资料塞给大模型并要求"基于资料回答"，界面上却看不出任何异常，
    // 来源列表还挂着几条低分条目 —— 等于让用户以为回答有知识库依据。
    const aiApi = (await import('@/api/aiApi')).default
    vi.mocked(aiApi.chat).mockResolvedValue({
      question: '训练后肌肉酸痛怎么办？',
      answer: '## 结论\n\n知识库中没有相关资料，以下基于通用健身知识。',
      sources: [],
      dataSource: 'llm_only',
      degraded: true,
      degradationReason:
        '知识库中未检索到与该问题相关的资料（大模型判定给定资料与问题无关，最高相似度 0.8206）',
      sessionId: '3f1c8b9e-6a2d-4f5b-9c7e-1d2a3b4c5d6e',
      generatedAt: '2026-09-21 12:00:00',
    })

    const user = userEvent.setup()
    renderWithProviders(<AIAssistantPage />)

    await user.click(await screen.findByRole('tab', { name: '💬 健身问答' }))
    await user.click(await screen.findByRole('button', { name: '训练后肌肉酸痛怎么办？' }))

    // 1) 必须明确告知「没用知识库」
    expect(await screen.findByText('通用知识回答（未使用知识库）')).toBeInTheDocument()
    expect(screen.getByText(/最高相似度 0.8206/)).toBeInTheDocument()

    // 2) 不能展示任何来源（否则用户会以为回答有依据）
    expect(screen.queryByText(/参考来源/)).not.toBeInTheDocument()
  })
})

// ==================== 对话记忆（批次 C） ====================

describe('对话记忆（sessionId）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('首轮不带 sessionId，之后每轮都带上后端返回的会话 id（否则追问会失忆）', async () => {
    const aiApi = (await import('@/api/aiApi')).default
    vi.mocked(aiApi.chat).mockResolvedValue({
      question: '深蹲时膝盖可以超过脚尖吗？',
      answer: '## 结论\n可以适度超过。',
      sources: [],
      dataSource: 'llm_only',
      degraded: true,
      degradationReason: '知识库中未检索到与该问题相关的资料',
      sessionId: '3f1c8b9e-6a2d-4f5b-9c7e-1d2a3b4c5d6e',
      generatedAt: '2026-09-22 19:00:00',
    })

    const user = userEvent.setup()
    renderWithProviders(<AIAssistantPage />)

    await user.click(await screen.findByRole('tab', { name: '💬 健身问答' }))
    await user.click(await screen.findByRole('button', { name: '深蹲时膝盖可以超过脚尖吗？' }))
    await waitFor(() => expect(aiApi.chat).toHaveBeenCalledTimes(1))

    // 第一轮：不带 sessionId（后端会新建一个并返回）
    expect(vi.mocked(aiApi.chat).mock.calls[0][0].sessionId).toBeUndefined()

    // 第二轮必须把上一轮返回的 sessionId 带回去 —— 这一步漏了，"记忆"就是假的
    const box = await screen.findByPlaceholderText(/输入问题/)
    await user.type(box, '那做几组？')
    await user.keyboard('{Enter}')
    await waitFor(() => expect(aiApi.chat).toHaveBeenCalledTimes(2))
    expect(vi.mocked(aiApi.chat).mock.calls[1][0].sessionId).toBe(
      '3f1c8b9e-6a2d-4f5b-9c7e-1d2a3b4c5d6e',
    )
  })

  it('「新对话」清空气泡并让后端清掉旧会话（只清界面不换会话 = 名不副实）', async () => {
    const aiApi = (await import('@/api/aiApi')).default
    vi.mocked(aiApi.chat).mockResolvedValue({
      question: '深蹲时膝盖可以超过脚尖吗？',
      answer: '## 结论\n可以适度超过。',
      sources: [],
      dataSource: 'milvus',
      degraded: false,
      degradationReason: null,
      sessionId: '3f1c8b9e-6a2d-4f5b-9c7e-1d2a3b4c5d6e',
      generatedAt: '2026-09-22 19:00:00',
    })
    vi.mocked(aiApi.newChatSession).mockResolvedValue(undefined)

    const user = userEvent.setup()
    renderWithProviders(<AIAssistantPage />)

    await user.click(await screen.findByRole('tab', { name: '💬 健身问答' }))
    await user.click(await screen.findByRole('button', { name: '深蹲时膝盖可以超过脚尖吗？' }))
    await screen.findByText(/可以适度超过/)

    await user.click(screen.getByRole('button', { name: /新对话/ }))
    // modal.confirm 里的确认按钮（与触发按钮同名，取最后一个）
    const buttons = await screen.findAllByRole('button', { name: '新对话' })
    await user.click(buttons[buttons.length - 1])

    await waitFor(() =>
      expect(aiApi.newChatSession).toHaveBeenCalledWith('3f1c8b9e-6a2d-4f5b-9c7e-1d2a3b4c5d6e'),
    )
    // 气泡被清空：回到快捷提问的初始态
    expect(await screen.findByText('试试问我这些问题')).toBeInTheDocument()
    expect(window.sessionStorage.getItem('fitness-ai-chat-session')).toBeNull()
  })
})

// ==================== 身体状态主动问询（批次 D） ====================

describe('AI 身体状态问询（批次 D）', () => {
  // 模块级 mock 的实现与调用记录会跨用例累积，这里清掉调用次数，
  // 否则「点击前不应该调用」这类断言会被上一个用例的调用记录污染
  beforeEach(() => {
    vi.clearAllMocks()
  })

  /** 正常（真实大模型）返回：用来和 rule_based 对比「不该出现标记」 */
  const llmResponse = {
    assessment: '整体判断：近 30 天体重与围度基本稳定，训练频率正常。',
    trendSummary: '近 7 天体重从 71.2 kg 降至 70.4 kg（-0.8 kg），腰围减少 1.0 cm。',
    questions: [
      { id: 'q-1', text: '最近一周的饮食有变化吗？', why: '体重变化速度与热量摄入直接相关。' },
    ],
    suggestions: [{ title: '保持蛋白质摄入', detail: '每天按 1.6-2.0 g/kg 体重摄入。' }],
    riskFlags: [],
    dataSource: 'llm',
    degraded: false,
    degradationReason: null,
    generatedAt: '2026-09-23 09:00:00',
    cached: false,
  }

  it('点击「让 AI 看看我的变化」才调用接口（不是自动 fetch），并渲染整体判断', async () => {
    // 这条用例守的是「省 token、不打扰」这个产品决定：
    // 若哪天有人把 bodyConsult 挂到 useQuery 上自动跑，一进档案页就会烧一次大模型调用，
    // 界面看起来完全正常 —— 只有这里能拦住
    const aiApi = (await import('@/api/aiApi')).default
    vi.mocked(aiApi.bodyConsult).mockResolvedValue(llmResponse)

    const user = userEvent.setup()
    renderWithProviders(<ProfilePage />)
    await screen.findByText('测试用户')

    expect(screen.getByText('🤖 AI 主动追问')).toBeInTheDocument()
    expect(aiApi.bodyConsult).not.toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: '让 AI 看看我的变化' }))

    await waitFor(() => expect(aiApi.bodyConsult).toHaveBeenCalledTimes(1))
    expect(await screen.findByText(/整体判断：近 30 天体重与围度基本稳定/)).toBeInTheDocument()
    expect(screen.getByText(/近 7 天体重从 71.2 kg 降至 70.4 kg/)).toBeInTheDocument()
    // 追问与理由、建议也都要真的渲染出来
    expect(screen.getByText('最近一周的饮食有变化吗？')).toBeInTheDocument()
    expect(screen.getByText('体重变化速度与热量摄入直接相关。')).toBeInTheDocument()
    expect(screen.getByText('保持蛋白质摄入')).toBeInTheDocument()
  })

  it('dataSource 为 rule_based 时，卡片顶部标注「规则生成（未使用大模型）」并给出原因', async () => {
    // 硬规矩：规则兜底的结果必须在界面上看得见。
    // 否则用户会把后端模板拼出来的句子当成 AI 的分析结论 —— 这正是「编造数据」的另一种形式
    const aiApi = (await import('@/api/aiApi')).default
    vi.mocked(aiApi.bodyConsult).mockResolvedValue({
      ...llmResponse,
      dataSource: 'rule_based',
      degraded: true,
      degradationReason: '身体数据不足 7 天，未调用大模型，已改用规则模板生成',
    })

    const user = userEvent.setup()
    renderWithProviders(<ProfilePage />)
    await screen.findByText('测试用户')

    await user.click(screen.getByRole('button', { name: '让 AI 看看我的变化' }))

    expect(await screen.findByText('规则生成（未使用大模型）')).toBeInTheDocument()
    expect(screen.getByText(/未调用大模型，已改用规则模板生成/)).toBeInTheDocument()
  })

  it('riskFlags 里的 high 级别风险文本可见（不能被吞掉）', async () => {
    const aiApi = (await import('@/api/aiApi')).default
    vi.mocked(aiApi.bodyConsult).mockResolvedValue({
      ...llmResponse,
      riskFlags: [
        { level: 'info', text: '体脂率 18.5%，处于健康区间。' },
        {
          level: 'high',
          text: '近 7 天体重下降 3.2 kg，超出安全区间，建议尽快评估饮食与训练量。',
        },
      ],
    })

    const user = userEvent.setup()
    renderWithProviders(<ProfilePage />)
    await screen.findByText('测试用户')

    await user.click(screen.getByRole('button', { name: '让 AI 看看我的变化' }))

    expect(await screen.findByText(/超出安全区间，建议尽快评估饮食与训练量/)).toBeInTheDocument()
    expect(screen.getByText(/体脂率 18.5%，处于健康区间/)).toBeInTheDocument()
  })

  it('真实大模型且未降级：不出现任何降级/兜底提示', async () => {
    const aiApi = (await import('@/api/aiApi')).default
    vi.mocked(aiApi.bodyConsult).mockResolvedValue(llmResponse)

    const user = userEvent.setup()
    renderWithProviders(<ProfilePage />)
    await screen.findByText('测试用户')

    await user.click(screen.getByRole('button', { name: '让 AI 看看我的变化' }))
    await screen.findByText(/整体判断：近 30 天体重与围度基本稳定/)

    // 反向断言同样重要：若标记逻辑写成「无条件显示」，这条用例是唯一会红的
    expect(screen.queryByText(/降级回答/)).not.toBeInTheDocument()
    expect(screen.queryByText(/规则生成/)).not.toBeInTheDocument()
    expect(screen.queryByText(/结果来源：/)).not.toBeInTheDocument()
  })

  it('问答页：点「让 AI 根据我的身体状态提问」会把第一条追问直接发出去（复用会话记忆）', async () => {
    const aiApi = (await import('@/api/aiApi')).default
    vi.mocked(aiApi.bodyConsult).mockResolvedValue(llmResponse)
    vi.mocked(aiApi.chat).mockResolvedValue({
      question: '最近一周的饮食有变化吗？',
      answer: '## 结论\n先从总热量看起。',
      sources: [],
      dataSource: 'milvus',
      degraded: false,
      degradationReason: null,
      sessionId: '3f1c8b9e-6a2d-4f5b-9c7e-1d2a3b4c5d6e',
      generatedAt: '2026-09-23 09:05:00',
    })

    const user = userEvent.setup()
    renderWithProviders(<AIAssistantPage />)

    await user.click(await screen.findByRole('tab', { name: '💬 健身问答' }))
    await user.click(
      await screen.findByRole('button', { name: '让 AI 根据我的身体状态提问' }),
    )

    await waitFor(() => expect(aiApi.bodyConsult).toHaveBeenCalledTimes(1))
    // 追问不是自己发请求，而是走已有的 send()：这条用例就是「真的接了会话记忆」的证据
    await waitFor(() => expect(aiApi.chat).toHaveBeenCalledTimes(1))
    expect(vi.mocked(aiApi.chat).mock.calls[0][0].question).toBe('最近一周的饮食有变化吗？')
  })

  it('问答页：没有可追问的问题时不发送空问题，只给一句提示', async () => {
    const aiApi = (await import('@/api/aiApi')).default
    vi.mocked(aiApi.bodyConsult).mockResolvedValue({ ...llmResponse, questions: [] })

    const user = userEvent.setup()
    renderWithProviders(<AIAssistantPage />)

    await user.click(await screen.findByRole('tab', { name: '💬 健身问答' }))
    await user.click(
      await screen.findByRole('button', { name: '让 AI 根据我的身体状态提问' }),
    )

    expect(
      await screen.findByText('暂时没有可追问的问题，先记录几天身体数据吧'),
    ).toBeInTheDocument()
    // 关键：不能把空字符串当问题发出去 —— 那会白白烧一次问答的大模型调用
    expect(aiApi.chat).not.toHaveBeenCalled()
  })
})

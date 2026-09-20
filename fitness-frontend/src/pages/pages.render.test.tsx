import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
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
  },
}))

vi.mock('@/api/aiApi', () => ({
  default: {
    generateSummary: vi.fn(),
    recommend: vi.fn(),
    evaluatePose: vi.fn(),
    chat: vi.fn(),
    knowledgeHealth: vi.fn(),
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

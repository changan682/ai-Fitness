import { Suspense, lazy } from 'react'
import { createBrowserRouter, Navigate, Outlet } from 'react-router-dom'
import { Spin } from 'antd'
import AppBridge from '@/components/common/AppBridge'
import AppLayout from '@/components/layout/AppLayout'
import AIAssistantPage from '@/pages/AIAssistantPage'
import DashboardPage from '@/pages/DashboardPage'
import LoginPage from '@/pages/LoginPage'
import NotFoundPage from '@/pages/NotFoundPage'
import ProfilePage from '@/pages/ProfilePage'
import ProtectedRoute from './ProtectedRoute'

/**
 * 趋势页懒加载
 * <p>
 * 它依赖 ECharts（约 1MB），而首屏（登录 → 看板）完全用不到图表。
 * 懒加载后这部分代码只在用户真的点开「数据趋势」时才下载；
 * 配合 vite.config.ts 的 manualChunks，它会被拆成独立的 chart 分片。
 */
const TrendsPage = lazy(() => import('@/pages/TrendsPage'))

/** 懒加载分片的占位（居中转圈，撑满内容区高度避免布局跳动） */
function LazyFallback() {
  return (
    <div className="flex min-h-[50vh] items-center justify-center">
      <Spin size="large" tip="加载中…">
        <div className="h-16 w-32" />
      </Spin>
    </div>
  )
}

/**
 * 根壳组件
 *
 * <p>只做一件事：挂载 `AppBridge`。它必须在 Router **内部**（要用 `useNavigate`），
 * 同时又要在所有页面之上（拦截器随时可能需要跳转）。
 */
function RootShell() {
  return (
    <>
      <AppBridge />
      <Outlet />
    </>
  )
}

/**
 * 路由表
 *
 * <p>层级：RootShell（挂桥接） → ProtectedRoute（鉴权） → AppLayout（侧边栏布局） → 页面。
 * 这样「未登录」时连布局都不会渲染，不会出现「侧边栏 + 登录页」的怪状态。
 *
 * <p>子路由一律用**相对路径**（`dashboard` 而不是 `/dashboard`）：
 * React Router v6 对 pathless 父路由下的绝对路径有额外约束，相对写法在任何版本都成立。
 */
export const router = createBrowserRouter([
  {
    element: <RootShell />,
    children: [
      // 公开路由
      { path: 'login', element: <LoginPage /> },

      // 受保护路由：先过鉴权，再套布局
      {
        element: <ProtectedRoute />,
        children: [
          {
            element: <AppLayout />,
            children: [
              { path: 'dashboard', element: <DashboardPage /> },
              { path: 'ai', element: <AIAssistantPage /> },
              {
                path: 'trends',
                element: (
                  <Suspense fallback={<LazyFallback />}>
                    <TrendsPage />
                  </Suspense>
                ),
              },
              { path: 'profile', element: <ProfilePage /> },
            ],
          },
        ],
      },

      { index: true, element: <Navigate to="/dashboard" replace /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
])

export default router

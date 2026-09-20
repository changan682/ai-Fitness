import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { App as AntdApp, ConfigProvider } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import dayjs from 'dayjs'
import 'dayjs/locale/zh-cn'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App.tsx'
import './index.css'
import { useUserStore } from './store'

// dayjs 中文化：DatePicker / 相对时间等组件依赖它
dayjs.locale('zh-cn')

/**
 * 启动即恢复登录态
 * <p>
 * 必须**在渲染之前**同步完成：否则 `ProtectedRoute` 首帧读到的
 * `isAuthenticated` 还是 false，会把已登录用户直接踢到 /login
 * （表现为刷新页面后总被登出）。
 */
useUserStore.getState().restoreSession()

/**
 * TanStack Query 全局配置
 * <p>
 * - retry=1：默认 3 次重试对 AI 接口（20-30s 才失败）体验太差，降到 1 次
 * - refetchOnWindowFocus=false：训练记录类页面在切回标签页时不该无条件重新拉取
 * - staleTime=30s：短期内重复进入同一页面直接吃缓存，减少无谓请求
 */
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      refetchOnWindowFocus: false,
      staleTime: 30_000,
    },
  },
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    {/* ConfigProvider：规范要求全局 zhCN + 主题色；包在 RouterProvider 外层 */}
    <ConfigProvider locale={zhCN} theme={{ token: { colorPrimary: '#1677ff' } }}>
      {/* AntdApp 提供 message/Modal/notification 的静态方法上下文，
          使其能在 React 树内正确取到主题与国际化 */}
      <AntdApp>
        <QueryClientProvider client={queryClient}>
          <App />
        </QueryClientProvider>
      </AntdApp>
    </ConfigProvider>
  </StrictMode>,
)

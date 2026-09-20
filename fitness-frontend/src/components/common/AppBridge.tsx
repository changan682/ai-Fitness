import { App } from 'antd'
import { useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { registerMessageApi, registerNavigate } from '@/utils/bridge'

/**
 * React ↔ 非 React 的桥接组件（不渲染任何内容）
 *
 * <p>它做两件事：
 * 1. 把 antd 的 `message` 实例注册给 axios 拦截器
 *    （antd v5 的静态 `message.xxx` 拿不到 ConfigProvider 的上下文，
 *    官方推荐用 `App.useApp()` 取实例后再在组件外使用）
 * 2. 把 react-router 的 `navigate` 注册给拦截器，使 9001 能走 SPA 跳转而不是整页刷新
 *
 * <p>注册写在**渲染期**而不是 `useEffect` 里：页面组件的首个请求在自身 effect 中发出，
 * 若等 effect 再注册，理论上存在「请求先于注册」的窗口。
 * 这里只是幂等的模块级赋值，重复注册无副作用。
 */
export default function AppBridge() {
  const { message } = App.useApp()
  const navigate = useNavigate()

  registerMessageApi({
    success: (c) => void message.success(c),
    error: (c) => void message.error(c),
    warning: (c) => void message.warning(c),
    info: (c) => void message.info(c),
  })
  registerNavigate((to, options) => navigate(to, options))

  useEffect(() => {
    return () => {
      // 卸载时摘掉，避免持有已失效的 navigate / message
      registerMessageApi(null)
      registerNavigate(null)
    }
  }, [])

  return null
}

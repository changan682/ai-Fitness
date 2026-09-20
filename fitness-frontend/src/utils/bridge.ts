/**
 * React ↔ 非 React 的双向桥
 *
 * <h3>为什么需要它</h3>
 * axios 拦截器、以及一些工具函数都在 React 树之外运行，但它们需要：
 * - 读取当前 Token（来自 userStore）
 * - 在 9001 时跳转登录页（需要 react-router 的 navigate）
 * - 弹全局提示（antd v5 的静态 message 拿不到 ConfigProvider 的上下文，
 *   官方推荐用 `App.useApp()` 取实例后再在外部使用）
 *
 * <h3>为什么不用「client.ts 直接 import userStore」</h3>
 * 那会形成循环依赖：userStore → userApi → client → userStore。
 * 这里改成依赖倒置：本模块只持有接口，由 React 侧（`AppBridge` 组件）在挂载时注册实现。
 * 在注册之前调用会走安全兜底（不抛异常），因此测试或首屏极早期也不会崩。
 */

export interface MessageApi {
  success: (content: string) => void
  error: (content: string) => void
  warning: (content: string) => void
  info: (content: string) => void
}

export type NavigateFn = (
  to: string,
  options?: { replace?: boolean; state?: unknown },
) => void

export interface AuthBridge {
  /** 取当前 Token（来自 userStore） */
  getToken: () => string | null
  /** Token 被静默刷新后同步回 store 与 storage */
  onTokenRefreshed: (token: string) => void
  /** 登录态失效：清 store + 提示 + 跳登录页 */
  onUnauthorized: (message: string) => void
}

// ==================== 内部持有 ====================

let navigateImpl: NavigateFn | null = null
let messageImpl: MessageApi | null = null
let authImpl: AuthBridge | null = null

// ==================== 注册（React 侧调用） ====================

export function registerNavigate(fn: NavigateFn | null): void {
  navigateImpl = fn
}

export function registerMessageApi(api: MessageApi | null): void {
  messageImpl = api
}

export function registerAuthBridge(bridge: AuthBridge | null): void {
  authImpl = bridge
}

// ==================== 使用（非 React 侧调用） ====================

/**
 * 跳转路由
 * <p>
 * 未注册 navigate（极早期或单测环境）时退回整页跳转：
 * 会丢失 SPA 状态，但「会话失效跳登录」这个场景本来就需要重置前端状态，可以接受。
 */
export function navigateTo(
  to: string,
  options?: { replace?: boolean; state?: unknown },
): void {
  if (navigateImpl) {
    navigateImpl(to, options)
    return
  }
  if (typeof window !== 'undefined') {
    window.location.assign(to)
  }
}

/**
 * 全局提示
 * <p>
 * 未注册时退回 console —— 宁可只留日志，也不让「提示组件还没挂载」把请求流程打断。
 */
export const notify: MessageApi = {
  success: (c) => (messageImpl ? messageImpl.success(c) : console.info('[message]', c)),
  error: (c) => (messageImpl ? messageImpl.error(c) : console.error('[message]', c)),
  warning: (c) => (messageImpl ? messageImpl.warning(c) : console.warn('[message]', c)),
  info: (c) => (messageImpl ? messageImpl.info(c) : console.info('[message]', c)),
}

/** 读取当前 Token；未注册时返回 null（拦截器据此跳过注入） */
export function currentToken(): string | null {
  return authImpl ? authImpl.getToken() : null
}

/** 通知 store：Token 已被静默刷新 */
export function emitTokenRefreshed(token: string): void {
  authImpl?.onTokenRefreshed(token)
}

/** 通知 store：登录态失效 */
export function emitUnauthorized(message: string): void {
  if (authImpl) {
    authImpl.onUnauthorized(message)
  } else {
    notify.warning(message)
    navigateTo('/login')
  }
}

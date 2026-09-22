import { create } from 'zustand'
import { userApi } from '@/api/userApi'
import type { UserBrief, UserProfile } from '@/types'
import { navigateTo, notify, registerAuthBridge } from '@/utils/bridge'
import { clearToken, readToken, readUser, saveToken, saveUser } from '@/utils/tokenStorage'

/**
 * 登录态 Store
 *
 * <h3>为什么 user 的类型是 `UserBrief` 而不是完整的 `UserProfile`</h3>
 * 登录接口只返回 `{id, nickname, gender, trainingGoal}` 这几个字段
 * （完整档案要另外调 `GET /v1/user/profile`）。
 * 若这里声明成 `UserProfile`，页面读 `phone`/`height` 时类型检查会通过、运行时却是
 * `undefined` —— 典型的「类型说谎」。因此这里如实声明精简类型，
 * 完整档案由 Profile 页面自己用 useQuery 拉取。
 *
 * <h3>为什么不使用 zustand 的 persist 中间件</h3>
 * 规范要求「Token 按『记住我』选择 localStorage 或 sessionStorage」，
 * 而 persist 在创建时就固定了 storage，无法在运行期按用户勾选切换。
 * 因此持久化交给 `utils/tokenStorage`（纯函数、可在 React 之外调用，
 * axios 拦截器也依赖它），store 只负责把它的结果镜像成 React 状态。
 */

interface UserState {
  // ==================== 状态 ====================
  token: string | null
  user: UserBrief | null
  /** token 与 user 均存在才算已登录 */
  isAuthenticated: boolean

  // ==================== 计算属性 ====================
  userId: () => number | null

  // ==================== Actions ====================
  /** 登录成功后写入登录态；remember 决定落 localStorage 还是 sessionStorage */
  login: (token: string, user: UserBrief, remember?: boolean) => void
  /** 登出：先尽力通知后端作废 Token，再清本地（后端失败也要清，否则用户无法退出） */
  logout: () => Promise<void>
  /** 更新用户信息（改档案后同步 Sider 上的昵称） */
  setUser: (user: UserBrief) => void
  /** 静默刷新 Token 后回写 */
  updateToken: (token: string) => void
  /** 清空本地登录态（9001 失效时由 bridge 调用） */
  clearSession: () => void
  /** 应用启动时从 storage 恢复；返回是否恢复成功 */
  restoreSession: () => boolean
  /** 用最新档案覆盖（Profile 页面保存后用完整档案更新精简信息） */
  syncFromProfile: (profile: UserProfile) => void
}

export const useUserStore = create<UserState>((set, get) => ({
  token: null,
  user: null,
  isAuthenticated: false,

  userId: () => get().user?.id ?? null,

  login: (token, user, remember = false) => {
    saveToken(token, remember)
    saveUser(user, remember)
    set({ token, user, isAuthenticated: true })
  },

  logout: async () => {
    try {
      // 后端不可达时不应阻止用户退出：本地清理才是关键
      await userApi.logout()
    } catch (e) {
      console.warn('[userStore] 登出接口调用失败，仍会清理本地登录态', e)
    } finally {
      get().clearSession()
    }
  },

  setUser: (user) => {
    set({ user, isAuthenticated: Boolean(get().token) })
    saveUser(user, Boolean(readToken()))
  },

  updateToken: (token) => {
    saveToken(token, Boolean(readToken()))
    set({ token })
  },

  clearSession: () => {
    clearToken()
    set({ token: null, user: null, isAuthenticated: false })
  },

  restoreSession: () => {
    const token = readToken()
    const user = readUser<UserBrief>()
    if (token && user) {
      set({ token, user, isAuthenticated: true })
      return true
    }
    // 只残留一半（例如手工删过 storage）时视为未登录，并清干净避免半登录态
    if (token || user) clearToken()
    set({ token: null, user: null, isAuthenticated: false })
    return false
  },

  syncFromProfile: (profile) => {
    const brief: UserBrief = {
      id: profile.id,
      nickname: profile.nickname,
      gender: profile.gender,
      trainingGoal: profile.trainingGoal,
      // 头像也要带上：否则改完头像刷新页面，顶栏又从默认图标变回"没换过"的样子
      avatarUrl: profile.avatarUrl,
    }
    set({ user: brief })
    saveUser(brief, Boolean(readToken()))
  },
}))

/**
 * 把登录态接到 axios 拦截器 / 路由（依赖倒置，避免 client ↔ store 循环依赖）
 * <p>
 * 在模块加载时注册：axios 拦截器首次发请求前必然已经 import 过本模块
 * （所有 api 模块都从 client 出发，而页面/store 会先加载 store）。
 */
registerAuthBridge({
  getToken: () => useUserStore.getState().token,

  onTokenRefreshed: (token) => {
    useUserStore.getState().updateToken(token)
  },

  onUnauthorized: (message) => {
    useUserStore.getState().clearSession()
    notify.warning(message)
    // 带上来源路径，登录成功后可跳回原页面
    navigateTo('/login', {
      replace: true,
      state: { from: window.location.pathname + window.location.search },
    })
  },
})

export default useUserStore

/**
 * Token 本地存储 —— 由「记住我」决定落在 localStorage 还是 sessionStorage
 * <p>
 * 为什么不直接用 zustand 的 persist 中间件：
 * persist 只能选一个 storage，「记住我」却要求在运行期二选一，
 * 且需要在 **React 之外**（axios 拦截器）读写，因此这里做成一个纯工具模块。
 * userStore 只是把它的结果镜像到 React 状态里。
 *
 * 存储键刻意用 `token` / `user`（规范原文即如此），便于人工在 DevTools 里排查。
 */

const TOKEN_KEY = 'token'
const USER_KEY = 'user'
/** 「记住我」的标记与上次登录的手机号，始终放 localStorage，用于登录页自动回填 */
const REMEMBER_KEY = 'fitness_remember'
const PHONE_KEY = 'fitness_last_phone'

/** storage 在隐私模式/配额满时会抛异常，全部包一层兜底，避免登录流程被存储问题打断 */
function safeGet(storage: Storage | undefined, key: string): string | null {
  try {
    return storage ? storage.getItem(key) : null
  } catch {
    return null
  }
}

function safeSet(storage: Storage | undefined, key: string, value: string): void {
  try {
    storage?.setItem(key, value)
  } catch {
    /* 忽略：存储不可用不应导致登录失败 */
  }
}

function safeRemove(storage: Storage | undefined, key: string): void {
  try {
    storage?.removeItem(key)
  } catch {
    /* 同上 */
  }
}

const local = (): Storage | undefined => (typeof window === 'undefined' ? undefined : window.localStorage)
const session = (): Storage | undefined => (typeof window === 'undefined' ? undefined : window.sessionStorage)

/** 写入 Token：只写选中的那份，并清掉另一份，避免两份不一致导致「登出后仍能请求」 */
export function saveToken(token: string, remember: boolean): void {
  if (remember) {
    safeSet(local(), TOKEN_KEY, token)
    safeRemove(session(), TOKEN_KEY)
  } else {
    safeSet(session(), TOKEN_KEY, token)
    safeRemove(local(), TOKEN_KEY)
  }
  safeSet(local(), REMEMBER_KEY, remember ? '1' : '0')
}

/** 读取 Token：sessionStorage 优先（同标签页刚登录的态最新），再退回 localStorage */
export function readToken(): string | null {
  return safeGet(session(), TOKEN_KEY) ?? safeGet(local(), TOKEN_KEY)
}

/** 清空 Token 与用户信息（登出 / 9001 失效时调用） */
export function clearToken(): void {
  safeRemove(session(), TOKEN_KEY)
  safeRemove(local(), TOKEN_KEY)
  safeRemove(session(), USER_KEY)
  safeRemove(local(), USER_KEY)
}

/** 持久化用户信息（首屏刷新后不必等接口就能渲染昵称/头像） */
export function saveUser(user: unknown, remember: boolean): void {
  const raw = JSON.stringify(user)
  if (remember) {
    safeSet(local(), USER_KEY, raw)
    safeRemove(session(), USER_KEY)
  } else {
    safeSet(session(), USER_KEY, raw)
    safeRemove(local(), USER_KEY)
  }
}

/** 读取持久化的用户信息；解析失败返回 null（旧版本数据结构变了也不会白屏） */
export function readUser<T>(): T | null {
  const raw = safeGet(session(), USER_KEY) ?? safeGet(local(), USER_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as T
  } catch {
    return null
  }
}

/** 上次是否勾选了「记住我」 */
export function isRemembered(): boolean {
  return safeGet(local(), REMEMBER_KEY) === '1'
}

/** 记住上次登录的手机号（勾选「记住我」时），用于登录表单自动回填 */
export function saveLastPhone(phone: string): void {
  safeSet(local(), PHONE_KEY, phone)
}

export function readLastPhone(): string | null {
  return safeGet(local(), PHONE_KEY)
}

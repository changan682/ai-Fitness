import axios, {
  type AxiosError,
  type AxiosRequestConfig,
  type InternalAxiosRequestConfig,
} from 'axios'
import { ErrorCode } from '@/types/common'
import {
  currentToken,
  emitTokenRefreshed,
  emitUnauthorized,
  notify,
} from '@/utils/bridge'
import { saveToken, isRemembered } from '@/utils/tokenStorage'

/**
 * 全局 Axios 单例 + 拦截器
 *
 * <h3>三个必须知道的约定</h3>
 * 1. **后端业务失败也返回 HTTP 200**（含鉴权失败 9001）。因此错误处理全部在
 *    「响应成功」分支里按 `code` 分流，`catch` 分支只处理网络层问题（超时/断网/5xx）。
 * 2. **响应拦截器会拆信封**：调用方拿到的已经是 `data` 里的内容，不是 `{code,msg,data}`。
 * 3. **Token 静默刷新**：剩余有效期 < 24h 时先调 refresh，成功后再发原请求。
 */
const BASE_URL = '/api'

/** 规范要求的全局超时 */
const DEFAULT_TIMEOUT = 15_000

/** 允许静默刷新的时间窗：剩余有效期 < 24h（与 Java 侧 REFRESH_WINDOW_MS 一致） */
const REFRESH_WINDOW_MS = 24 * 60 * 60 * 1000

/** 慢请求告警阈值 */
const SLOW_REQUEST_MS = 3_000

/** 请求元信息（用于计算耗时），挂在 config 上 */
interface TimedConfig extends InternalAxiosRequestConfig {
  metadata?: { start: number }
}

/** 由 axios 抛出的业务错误：页面可据 `code` 分支处理（如 1002 密码错误、6001 AI 超时） */
export class ApiError extends Error {
  readonly code: number
  readonly msg: string

  constructor(code: number, msg: string) {
    super(msg)
    this.name = 'ApiError'
    this.code = code
    this.msg = msg
  }
}

/**
 * 不做全局提示、交给页面自行 catch 的业务码
 * <p>
 * 这些是「表单级/页面级」错误：登录页要在密码框下提示、AI 页面要展示兜底文案卡片、
 * 列表页要用 Result 组件。若拦截器也弹一次全局 toast，用户会看到重复提示。
 */
const SILENT_CODES = new Set<number>([
  ErrorCode.PASSWORD_ERROR, // 1002 登录页处理
  ErrorCode.USER_NOT_FOUND, // 1003 登录页提示去注册
  ErrorCode.NO_TRAINING_RECORD, // 2003 AI 总结页用 Result 展示
  ErrorCode.TRAINING_RECORD_NOT_FOUND,
  ErrorCode.BODY_METRIC_DUPLICATE, // 3001 体测表单处理
  ErrorCode.BODY_METRIC_NOT_FOUND,
  ErrorCode.FOOD_NOT_FOUND, // 4001 饮食表单处理
  ErrorCode.TEMPLATE_NOT_FOUND,
  ErrorCode.AI_TIMEOUT, // 6001 AI 兜底文案
  ErrorCode.AI_RESPONSE_ERROR, // 6002 同上
  ErrorCode.MILVUS_UNAVAILABLE, // 6003 问答页错误卡片
  ErrorCode.EMBEDDING_ERROR,
])

// ==================== 实例 ====================

const client = axios.create({
  baseURL: BASE_URL,
  timeout: DEFAULT_TIMEOUT,
  headers: { 'Content-Type': 'application/json' },
})

/**
 * 专供「刷新 Token」使用的裸实例：不挂任何拦截器。
 * 若复用 client，刷新请求自己又会触发刷新逻辑，形成递归。
 */
const rawClient = axios.create({ baseURL: BASE_URL, timeout: DEFAULT_TIMEOUT })

// ==================== JWT 解析 ====================

/** 从 JWT 里读 exp（毫秒）。仅用于决定「要不要提前刷新」，不做任何安全判断 */
function readTokenExpMs(token: string): number | null {
  const parts = token.split('.')
  if (parts.length !== 3) return null
  try {
    const base64 = parts[1].replace(/-/g, '+').replace(/_/g, '/')
    const padded = base64 + '='.repeat((4 - (base64.length % 4)) % 4)
    const json = decodeURIComponent(
      atob(padded)
        .split('')
        .map((c) => '%' + c.charCodeAt(0).toString(16).padStart(2, '0'))
        .join(''),
    )
    const exp = JSON.parse(json).exp
    return typeof exp === 'number' ? exp * 1000 : null
  } catch {
    return null
  }
}

// ==================== Token 静默刷新 ====================

/** 并发请求只触发一次刷新（其余请求共享同一个 Promise） */
let refreshing: Promise<string | null> | null = null

async function refreshTokenIfNeeded(token: string): Promise<string | null> {
  const expMs = readTokenExpMs(token)
  if (expMs === null) return token

  const remaining = expMs - Date.now()
  // 还早 / 已过期：前者无需刷新，后者刷新接口本身也会被拒（9001），交给统一失效处理
  if (remaining > REFRESH_WINDOW_MS || remaining <= 0) return token

  if (!refreshing) {
    refreshing = (async () => {
      try {
        const res = await rawClient.post<{
          code: number
          data?: { token?: string }
        }>('/v1/user/refresh', null, {
          headers: { Authorization: `Bearer ${token}` },
        })
        const fresh = res.data?.data?.token
        if (res.data?.code === ErrorCode.SUCCESS && fresh) {
          // 刷新成功后按原来的「记住我」偏好写回，避免把长效 Token 降级成会话级
          saveToken(fresh, isRemembered())
          emitTokenRefreshed(fresh)
          return fresh
        }
        return token
      } catch {
        // 刷新失败不阻断原请求：原请求大概率仍能用，真失效了会由 9001 统一处理
        return token
      } finally {
        refreshing = null
      }
    })()
  }
  return refreshing
}

// ==================== 请求拦截器 ====================

client.interceptors.request.use(
  async (config: TimedConfig) => {
    config.metadata = { start: Date.now() }

    const token = currentToken()
    if (token) {
      const usable = await refreshTokenIfNeeded(token)
      if (usable) {
        config.headers.set('Authorization', `Bearer ${usable}`)
      }
    }

    // FormData 必须让浏览器自己带 boundary，写死 application/json 会导致后端解析不到文件
    if (typeof FormData !== 'undefined' && config.data instanceof FormData) {
      config.headers.delete('Content-Type')
    } else if (config.data !== undefined && !config.headers.get('Content-Type')) {
      config.headers.set('Content-Type', 'application/json')
    }

    return config
  },
  (error: unknown) => Promise.reject(error),
)

// ==================== 9001 去抖 ====================

/**
 * 一次失效可能同时打在多个并发请求上，
 * 若每个都提示+跳转，用户会看到一串 toast 且路由被反复覆盖。
 */
let lastUnauthorizedAt = 0

function handleUnauthorized(msg: string): void {
  const now = Date.now()
  if (now - lastUnauthorizedAt < 2_000) return
  lastUnauthorizedAt = now
  emitUnauthorized(msg || '登录已过期，请重新登录')
}

// ==================== 响应拦截器 ====================

client.interceptors.response.use(
  (response) => {
    const config = response.config as TimedConfig
    const started = config.metadata?.start
    if (started) {
      const cost = Date.now() - started
      if (cost > SLOW_REQUEST_MS) {
        console.warn(
          `[慢请求] ${config.method?.toUpperCase()} ${config.url} 耗时 ${cost}ms（阈值 ${SLOW_REQUEST_MS}ms）`,
        )
      }
    }

    const body = response.data as { code?: number; msg?: string; data?: unknown } | undefined

    // 非信封响应（例如文件流）：原样返回，避免把正常数据当成错误
    if (!body || typeof body.code !== 'number') {
      return response.data
    }

    if (body.code === ErrorCode.SUCCESS) {
      return body.data
    }

    const code = body.code
    const msg = body.msg || '操作失败'

    switch (code) {
      case ErrorCode.TOKEN_INVALID:
        handleUnauthorized('登录已过期，请重新登录')
        break
      case ErrorCode.SIGNATURE_INVALID:
        // 内部接口（Python → Java 回调）才会出现，前端遇到说明链路配置有问题，需要留痕
        notify.error('请求校验失败')
        console.error('[9002] 签名校验失败，属于内部接口异常，请检查 HMAC 配置', config.url)
        break
      case ErrorCode.PARAM_INVALID:
        notify.warning(msg)
        break
      default:
        if (!SILENT_CODES.has(code)) {
          notify.error(msg)
        }
        break
    }

    return Promise.reject(new ApiError(code, msg))
  },
  (error: AxiosError) => {
    // 走到这里说明是网络层问题：后端业务失败不会产生非 2xx
    if (error.code === 'ECONNABORTED' || error.message?.includes('timeout')) {
      notify.error('请求超时，请稍后重试')
      return Promise.reject(new ApiError(ErrorCode.SYSTEM_ERROR, '请求超时，请稍后重试'))
    }
    if (!error.response) {
      notify.error('网络连接失败，请检查网络')
      return Promise.reject(new ApiError(ErrorCode.SYSTEM_ERROR, '网络连接失败，请检查网络'))
    }
    if (error.response.status >= 500) {
      notify.error('服务器繁忙，请稍后重试')
      return Promise.reject(
        new ApiError(ErrorCode.SYSTEM_ERROR, '服务器繁忙，请稍后重试'),
      )
    }
    const fallback = `请求失败（HTTP ${error.response.status}）`
    notify.error(fallback)
    return Promise.reject(new ApiError(ErrorCode.SYSTEM_ERROR, fallback))
  },
)

// ==================== 类型化快捷方法 ====================

/**
 * 把 axios 的返回值转成「解包后的业务数据」
 * <p>
 * 响应拦截器已经把信封 `{code,msg,data}` 拆掉并返回了 `data`，
 * 但 axios 自身的类型描述的是「原始响应」，两者对不上，
 * 因此这里集中做一次断言 —— 只此一处，调用方拿到的就是干净的 `T`。
 */
async function unwrap<T>(request: Promise<unknown>): Promise<T> {
  return (await request) as T
}

export const http = {
  get: <T>(url: string, config?: AxiosRequestConfig): Promise<T> =>
    unwrap<T>(client.get(url, config)),
  post: <T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> =>
    unwrap<T>(client.post(url, data, config)),
  put: <T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> =>
    unwrap<T>(client.put(url, data, config)),
  delete: <T>(url: string, config?: AxiosRequestConfig): Promise<T> =>
    unwrap<T>(client.delete(url, config)),
}

/** AI 接口专用：模型推理耗时远超 15s，单独放宽（姿态评估含多模态，给到 90s） */
export const AI_TIMEOUT_MS = 60_000
export const AI_MULTIMODAL_TIMEOUT_MS = 90_000

export default client

import { AxiosError, type AxiosAdapter, type InternalAxiosRequestConfig } from 'axios'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import client, { ApiError, http } from './client'
import { registerAuthBridge, registerMessageApi, type MessageApi } from '@/utils/bridge'
import { ErrorCode } from '@/types/common'

/**
 * Axios 拦截器行为测试
 *
 * <h3>为什么测这一个文件</h3>
 * 拦截器是整个前端的「错误处理总闸」，它决定用户看到什么提示、会不会被踢下线。
 * 而它的核心逻辑恰好是**纯逻辑**（按 code 分流 + 并发去抖），非常适合单测：
 * 用自定义 adapter 伪造后端响应，不需要真后端，也不需要浏览器。
 *
 * 另外这也是唯一能验证「拦截器分级策略」的地方 —— 例如 1002 必须**静默**
 * （否则登录页会与拦截器重复弹两次提示），这种约定不写测试就会在重构中丢失。
 */

/** 用给定的响应信封替换 axios 的传输层 */
function stubResponse(envelope: unknown): void {
  const adapter: AxiosAdapter = async (config: InternalAxiosRequestConfig) => ({
    data: envelope,
    status: 200,
    statusText: 'OK',
    headers: {},
    config,
  })
  client.defaults.adapter = adapter
}

/** 用给定的传输层异常替换 adapter（模拟断网 / 超时） */
function stubTransportError(error: AxiosError): void {
  const adapter: AxiosAdapter = async () => {
    throw error
  }
  client.defaults.adapter = adapter
}

let shown: { level: string; content: string }[] = []
let unauthorizedCalls: string[] = []

beforeEach(() => {
  shown = []
  unauthorizedCalls = []

  const fakeMessage: MessageApi = {
    success: (c) => shown.push({ level: 'success', content: c }),
    error: (c) => shown.push({ level: 'error', content: c }),
    warning: (c) => shown.push({ level: 'warning', content: c }),
    info: (c) => shown.push({ level: 'info', content: c }),
  }
  registerMessageApi(fakeMessage)

  registerAuthBridge({
    getToken: () => null, // 不注入 Token，避免触发静默刷新分支
    onTokenRefreshed: () => {},
    onUnauthorized: (msg) => unauthorizedCalls.push(msg),
  })
})

afterEach(() => {
  registerMessageApi(null)
  registerAuthBridge(null)
  vi.restoreAllMocks()
})

describe('成功路径', () => {
  it('code=0 时拆掉信封，直接把 data 交给调用方', async () => {
    stubResponse({ code: 0, msg: 'success', data: { nickname: '测试用户' } })

    await expect(http.get<{ nickname: string }>('/v1/user/profile')).resolves.toEqual({
      nickname: '测试用户',
    })
    expect(shown).toHaveLength(0)
  })

  it('非信封响应（如文件流）原样返回，不误判为错误', async () => {
    // 没有 code 字段 → 视为非业务信封
    stubResponse({ foo: 'bar' })

    await expect(http.get('/v1/whatever')).resolves.toEqual({ foo: 'bar' })
    expect(shown).toHaveLength(0)
  })
})

describe('业务错误码分流', () => {
  it('9001：清登录态并跳登录，且并发多次只提示一次（去抖）', async () => {
    stubResponse({ code: ErrorCode.TOKEN_INVALID, msg: '未登录或Token已过期', data: null })

    const results = await Promise.allSettled([
      http.get('/v1/a'),
      http.get('/v1/b'),
      http.get('/v1/c'),
    ])

    // 三个请求都应被 reject（页面才会走自己的 catch）
    expect(results.every((r) => r.status === 'rejected')).toBe(true)
    // 但「跳登录」只应触发一次，否则路由会被反复覆盖
    expect(unauthorizedCalls).toHaveLength(1)
    expect(unauthorizedCalls[0]).toContain('登录已过期')
    // 9001 走的是跳转而不是 toast，因此不应产生全局提示
    expect(shown).toHaveLength(0)
  })

  it('9002：提示「请求校验失败」并留痕（内部回调链路异常）', async () => {
    stubResponse({ code: ErrorCode.SIGNATURE_INVALID, msg: '签名校验失败', data: null })

    await expect(http.get('/v1/a')).rejects.toBeInstanceOf(ApiError)
    expect(shown).toEqual([{ level: 'error', content: '请求校验失败' }])
  })

  it('9003：warning 提示后端原文，且不跳转', async () => {
    stubResponse({ code: ErrorCode.PARAM_INVALID, msg: '参数校验失败：昵称长度需2-20字符', data: null })

    await expect(http.get('/v1/a')).rejects.toMatchObject({
      code: ErrorCode.PARAM_INVALID,
      msg: '参数校验失败：昵称长度需2-20字符',
    })
    expect(shown).toEqual([{ level: 'warning', content: '参数校验失败：昵称长度需2-20字符' }])
    expect(unauthorizedCalls).toHaveLength(0)
  })

  it('1002（密码错误）：静默 —— 由登录页自行展示，避免重复提示', async () => {
    stubResponse({ code: ErrorCode.PASSWORD_ERROR, msg: '密码错误', data: null })

    await expect(http.get('/v1/a')).rejects.toMatchObject({ code: ErrorCode.PASSWORD_ERROR })
    expect(shown).toHaveLength(0)
  })

  it('6001（AI 超时）：静默 —— 由页面展示兜底文案', async () => {
    stubResponse({ code: ErrorCode.AI_TIMEOUT, msg: 'AI服务超时', data: null })

    await expect(http.get('/v1/a')).rejects.toMatchObject({ code: ErrorCode.AI_TIMEOUT })
    expect(shown).toHaveLength(0)
  })

  it('未知错误码：按 error 提示后端原文', async () => {
    stubResponse({ code: 12345, msg: '某种未预期的错误', data: null })

    await expect(http.get('/v1/a')).rejects.toMatchObject({ code: 12345 })
    expect(shown).toEqual([{ level: 'error', content: '某种未预期的错误' }])
  })

  it('非 0 但 msg 为空时回退为「操作失败」', async () => {
    stubResponse({ code: 12345, msg: '', data: null })

    await expect(http.get('/v1/a')).rejects.toBeInstanceOf(ApiError)
    expect(shown).toEqual([{ level: 'error', content: '操作失败' }])
  })
})

describe('网络层错误（后端业务失败不会走到这里）', () => {
  it('断网：提示网络连接失败', async () => {
    stubTransportError(new AxiosError('Network Error', 'ERR_NETWORK'))

    await expect(http.get('/v1/a')).rejects.toMatchObject({ msg: '网络连接失败，请检查网络' })
    expect(shown).toEqual([{ level: 'error', content: '网络连接失败，请检查网络' }])
  })

  it('超时：提示请求超时', async () => {
    stubTransportError(new AxiosError('timeout of 15000ms exceeded', 'ECONNABORTED'))

    await expect(http.get('/v1/a')).rejects.toMatchObject({ msg: '请求超时，请稍后重试' })
    expect(shown).toEqual([{ level: 'error', content: '请求超时，请稍后重试' }])
  })

  it('HTTP 5xx：提示服务器繁忙', async () => {
    const err = new AxiosError('Internal Server Error', 'ERR_BAD_RESPONSE')
    err.response = { status: 500, statusText: 'Internal Server Error', data: null, headers: {}, config: {} as never }
    stubTransportError(err)

    await expect(http.get('/v1/a')).rejects.toMatchObject({ msg: '服务器繁忙，请稍后重试' })
    expect(shown).toEqual([{ level: 'error', content: '服务器繁忙，请稍后重试' }])
  })
})

/**
 * 通用类型与错误码
 * <p>
 * 与 Java 侧严格对齐：
 * - `ApiResponse<T>` ↔ `com.fitness.common.Result<T>`
 * - `PaginatedData<T>` ↔ `com.fitness.common.PageResult<T>`
 * - `ErrorCode` ↔ `com.fitness.exception.ErrorCode`（逐值对齐，改动必须两边同时改）
 */

/** 统一后端返回结构（code=0 表示成功，非 0 为业务错误码） */
export interface ApiResponse<T = null> {
  code: number
  msg: string
  data: T
}

/** 分页返回结构 */
export interface PaginatedData<T> {
  list: T[]
  total: number
  page: number
  size: number
}

/** 分页请求参数（默认 page=1、size=20） */
export interface PageParams {
  page?: number
  size?: number
}

/** 成功码 —— 与 Result.ok() 一致 */
export const SUCCESS_CODE = 0

/**
 * 业务错误码
 * <p>
 * 注意：Java 侧在<b>业务失败时也返回 HTTP 200</b>，错误码只体现在 `code` 字段，
 * 因此 axios 的 `catch` 分支拿不到它 —— 必须在响应拦截器里解包判断。
 */
export enum ErrorCode {
  SUCCESS = 0,

  // ==================== 系统 / 鉴权 ====================
  /** 未登录或 Token 已过期 → 全局拦截，清 Token 并跳登录页 */
  TOKEN_INVALID = 9001,
  /** 回调签名校验失败（前端一般遇不到，属内部接口异常） */
  SIGNATURE_INVALID = 9002,
  /** 参数校验失败 → 全局 warning 提示，不跳转 */
  PARAM_INVALID = 9003,
  /** Token 剩余有效期 > 24h 或已刷新过，无需刷新 */
  TOKEN_REFRESH_NOT_ALLOWED = 9004,
  SYSTEM_ERROR = 9999,

  // ==================== 用户 1001-1099 ====================
  PHONE_REGISTERED = 1001,
  /** 密码错误 → 登录页自行处理，全局不弹提示 */
  PASSWORD_ERROR = 1002,
  /** 用户不存在 → 登录页自行处理（提示去注册） */
  USER_NOT_FOUND = 1003,

  // ==================== 训练记录 2001-2099 ====================
  TRAINING_RECORD_NOT_FOUND = 2001,
  DATE_RANGE_INVALID = 2002,
  /** 该日期无训练记录 → Dashboard / AI 总结页用 Result 组件展示，不用全局提示 */
  NO_TRAINING_RECORD = 2003,

  // ==================== 身体数据 3001-3099 ====================
  BODY_METRIC_DUPLICATE = 3001,
  BODY_METRIC_NOT_FOUND = 3002,

  // ==================== 饮食 4001-4099 ====================
  FOOD_NOT_FOUND = 4001,

  // ==================== 训练计划 5001-5099 ====================
  TEMPLATE_NOT_FOUND = 5001,

  // ==================== AI 6001-6099 ====================
  /** AI 超时 → 页面展示兜底文案 */
  AI_TIMEOUT = 6001,
  /** AI 返回异常 → 页面展示兜底文案 */
  AI_RESPONSE_ERROR = 6002,
  /** Milvus 不可用 / 知识库未初始化 → 问答页展示错误卡片 */
  MILVUS_UNAVAILABLE = 6003,
  EMBEDDING_ERROR = 6004,
}

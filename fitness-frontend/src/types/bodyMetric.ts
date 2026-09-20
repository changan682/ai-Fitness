/**
 * 身体数据（体测）类型
 * <p>
 * 体重口径（规范明确要求，别看错）：
 * - `UserProfile.weight` = 注册时的初始体重，仅在手动改档案时更新
 * - `BodyMetric.weightKg` = 日常跟踪体重
 * Dashboard / Trends 的「最新体重」**始终取 BodyMetric**，两者不自动同步。
 */

/** 体测记录 */
export interface BodyMetric {
  id: number
  userId: number
  /** yyyy-MM-dd */
  recordDate: string
  weightKg: number | null
  waistCm: number | null
  armCm: number | null
  legCm: number | null
  bodyFatPct: number | null
  /** yyyy-MM-dd HH:mm:ss */
  createdAt: string
}

/** 新增体测请求 —— 体重必填（30-300），其余可选；同一用户同一天只能有一条 */
export interface BodyMetricRequest {
  /** yyyy-MM-dd，不传默认当天 */
  recordDate?: string
  /** 30-300，必填 */
  weightKg: number
  /** 20-200 */
  waistCm?: number
  /** 10-100 */
  armCm?: number
  /** 20-150 */
  legCm?: number
  /** 3-60 */
  bodyFatPct?: number
}

/** 修改体测请求 —— 全部可选 */
export interface BodyMetricUpdateRequest {
  weightKg?: number
  waistCm?: number
  armCm?: number
  legCm?: number
  bodyFatPct?: number
}

/** 趋势图上的一个点 */
export interface BodyMetricTrendPoint {
  id: number
  /** yyyy-MM-dd */
  recordDate: string
  weightKg: number | null
  waistCm: number | null
  armCm: number | null
  legCm: number | null
  bodyFatPct: number | null
  /**
   * 7 日滑动平均
   * <p>
   * ⚠️ 后端口径：缺失日期**不补 0**，样本数 < 3 时返回 `null`。
   * ECharts 遇到 `null` 要断线，**不能填 0**，否则曲线会出现断崖式下跌。
   */
  weightAvg7d: number | null
}

/** 趋势响应 */
export interface BodyMetricTrendResponse {
  list: BodyMetricTrendPoint[]
  latestWeight: number | null
  /** 窗口内「最后一个 - 第一个」体重差；样本不足时为 null */
  weightChange: number | null
  totalRecords: number
}

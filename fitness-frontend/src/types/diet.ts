import type { MealType } from './enums'

/** 饮食记录 */
export interface DietRecord {
  id: number
  userId: number
  /** yyyy-MM-dd */
  recordDate: string
  mealType: MealType | string
  foodName: string
  weightG: number
  /** 热量 = 该食物每 100g 热量 × 重量 / 100，**由后端计算** */
  caloriesKcal: number
  /** yyyy-MM-dd HH:mm:ss */
  createdAt: string
}

/** 新增饮食记录请求 —— 食物必须在热量库中存在，否则返回 4001 */
export interface DietRecordRequest {
  /** yyyy-MM-dd，不传默认当天 */
  recordDate?: string
  mealType: MealType
  foodName: string
  /** 至少 1 克 */
  weightG: number
}

/** 按日查询响应（不是裸数组：前端今日饮食卡片需要当日总热量） */
export interface DietRecordListResponse {
  list: DietRecord[]
  /** yyyy-MM-dd */
  date: string
  totalCalories: number
}

/** 单个餐次的汇总 */
export interface MealSummary {
  itemCount: number
  calories: number
}

/** 每日饮食统计响应 */
export interface DietDailyStats {
  /** yyyy-MM-dd */
  date: string
  totalCalories: number
  records: DietRecord[]
  breakfast: MealSummary
  lunch: MealSummary
  dinner: MealSummary
  snack: MealSummary
}

/** 区间统计里的单日项 */
export interface DailyCalorieStat {
  /** yyyy-MM-dd */
  date: string
  totalCalories: number
  mealCount: number
}

/**
 * 区间每日热量统计响应（`GET /v1/diet/stats/daily?startDate&endDate`）
 * <p>
 * 供 Trends 页面的热量趋势使用。
 */
export interface DietStatsDaily {
  list: DailyCalorieStat[]
  /** 区间日均热量，**仅统计有记录的天数**（不是除以区间天数） */
  avgCalories: number | null
}

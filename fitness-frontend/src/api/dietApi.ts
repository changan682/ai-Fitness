import { http } from './client'
import type {
  DietDailyStats,
  DietRecord,
  DietRecordListResponse,
  DietRecordRequest,
  DietStatsDaily,
} from '@/types'

/**
 * 饮食记录模块 API
 * <p>
 * 热量不从前端传：后端按食物库的「每 100g 热量 × 重量 / 100」自动换算。
 * 食物名必须在热量库中存在（精确匹配），否则返回 4001。
 */
export const dietApi = {
  /** 4.1 录入饮食记录 */
  addRecord: (data: DietRecordRequest): Promise<DietRecord> =>
    http.post<DietRecord>('/v1/diet/record', data),

  /** 4.2 按日期查询（返回 {list, date, totalCalories}） */
  queryByDate: (date: string): Promise<DietRecordListResponse> =>
    http.get<DietRecordListResponse>('/v1/diet/records', { params: { date } }),

  /** 按餐次汇总的当日统计 */
  getDailyStats: (date: string): Promise<DietDailyStats> =>
    http.get<DietDailyStats>('/v1/diet/daily-stats', { params: { date } }),

  /** 4.3 区间每日热量统计（趋势图用） */
  getStatsByRange: (startDate: string, endDate: string): Promise<DietStatsDaily> =>
    http.get<DietStatsDaily>('/v1/diet/stats/daily', { params: { startDate, endDate } }),
}

export default dietApi

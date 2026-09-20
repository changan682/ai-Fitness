import { http } from './client'
import type { DashboardStats, WeeklyPlan, WeeklyStats } from '@/types'

/**
 * 统计与周计划 API
 * <p>
 * 注意 `/api/v1/weekly-plan/latest` 在没有记录时返回 `data: null`（不是 404、也不是错误码），
 * 页面据此展示 Empty 状态。
 */
export const statsApi = {
  /** 9.1 Dashboard 统计卡片（date 不传则统计今天） */
  getDashboard: (date?: string): Promise<DashboardStats> =>
    http.get<DashboardStats>('/v1/stats/dashboard', {
      params: date ? { date } : undefined,
    }),

  /** 9.3 本周训练统计（weekStart 不传则统计今天所在周；传任意一天会归一到该周周一） */
  getWeeklyStats: (weekStart?: string): Promise<WeeklyStats> =>
    http.get<WeeklyStats>('/v1/stats/weekly', {
      params: weekStart ? { weekStart } : undefined,
    }),

  /** 9.2 最新周计划（含 AI 建议；未生成时 suggestionText 为空串，无记录时为 null） */
  getLatestWeeklyPlan: (): Promise<WeeklyPlan | null> =>
    http.get<WeeklyPlan | null>('/v1/weekly-plan/latest'),
}

export default statsApi

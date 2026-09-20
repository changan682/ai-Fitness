import { http } from './client'
import type {
  BodyMetric,
  BodyMetricRequest,
  BodyMetricTrendResponse,
  BodyMetricUpdateRequest,
} from '@/types'

/** 身体数据模块 API */
export const bodyMetricApi = {
  /** 3.1 录入身体数据（同一天只能有一条，重复会返回 3001） */
  addMetric: (data: BodyMetricRequest): Promise<BodyMetric> =>
    http.post<BodyMetric>('/v1/body-metric', data),

  /** 3.1.1 修改当天身体数据（只能改当天的，历史记录不可改） */
  updateMetric: (id: number, data: BodyMetricUpdateRequest): Promise<BodyMetric> =>
    http.put<BodyMetric>(`/v1/body-metric/${id}`, data),

  /**
   * 3.2 查询趋势（每个点带 7 日滑动平均体重）
   * <p>
   * startDate / endDate 均为**必填**（后端没有默认区间）。
   */
  getTrend: (startDate: string, endDate: string): Promise<BodyMetricTrendResponse> =>
    http.get<BodyMetricTrendResponse>('/v1/body-metric/trend', {
      params: { startDate, endDate },
    }),

  /** 最新一条体测（可能是 null，页面要处理「尚未记录」） */
  getLatest: (): Promise<BodyMetric | null> =>
    http.get<BodyMetric | null>('/v1/body-metric/latest'),
}

export default bodyMetricApi

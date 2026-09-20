import { http } from './client'
import type {
  ActionHistoryResponse,
  PaginatedData,
  TrainingBatchRequest,
  TrainingBatchResponse,
  TrainingQueryParams,
  TrainingRecord,
  TrainingRecordRequest,
  TrainingRecordUpdateRequest,
  TrainingUpdateResponse,
} from '@/types'

/** 训练记录模块 API */
export const trainingApi = {
  /** 2.1 新增单条训练记录（容量由后端算） */
  addRecord: (data: TrainingRecordRequest): Promise<TrainingRecord> =>
    http.post<TrainingRecord>('/v1/training/record', data),

  /** 2.2 批量新增（单次最多 20 条） */
  batchAddRecords: (data: TrainingBatchRequest): Promise<TrainingBatchResponse> =>
    http.post<TrainingBatchResponse>('/v1/training/records/batch', data),

  /** 2.3 按日期区间分页查询 */
  queryByDateRange: (params: TrainingQueryParams): Promise<PaginatedData<TrainingRecord>> =>
    http.get<PaginatedData<TrainingRecord>>('/v1/training/records', { params }),

  /** 2.4 按动作查询（含该动作历史最大重量/容量） */
  queryByAction: (params: {
    actionName: string
    startDate?: string
    endDate?: string
    page?: number
    size?: number
  }): Promise<ActionHistoryResponse> =>
    http.get<ActionHistoryResponse>('/v1/training/records/by-action', { params }),

  /**
   * 2.5 修改训练记录
   * <p>
   * ⚠️ 只支持部分字段（组数/次数/重量/时长/RPE/备注），**不能改动作名**。
   */
  updateRecord: (id: number, data: TrainingRecordUpdateRequest): Promise<TrainingUpdateResponse> =>
    http.put<TrainingUpdateResponse>(`/v1/training/record/${id}`, data),

  /** 2.6 删除训练记录 */
  deleteRecord: (id: number): Promise<void> =>
    http.delete<void>(`/v1/training/record/${id}`),

  /** 今日训练记录（Dashboard 快捷接口，返回裸数组） */
  getTodayRecords: (): Promise<TrainingRecord[]> =>
    http.get<TrainingRecord[]>('/v1/training/today'),
}

export default trainingApi

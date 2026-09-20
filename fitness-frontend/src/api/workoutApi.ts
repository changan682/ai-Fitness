import { http } from './client'
import type { ApplyScheduleRequest, ApplyScheduleResponse, WorkoutTemplateListResponse } from '@/types'

/** 训练计划模板模块 API */
export const workoutApi = {
  /** 6.1 查看所有模板（含动作明细） */
  listTemplates: (): Promise<WorkoutTemplateListResponse> =>
    http.get<WorkoutTemplateListResponse>('/v1/workout/templates'),

  /** 6.2 套用模板生成一周训练安排 */
  applySchedule: (data: ApplyScheduleRequest): Promise<ApplyScheduleResponse> =>
    http.post<ApplyScheduleResponse>('/v1/workout/schedule/apply', data),
}

export default workoutApi

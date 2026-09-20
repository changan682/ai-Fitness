/**
 * 训练记录类型
 * <p>
 * 注意：后端这几个接口直接返回实体 `TrainingRecord`（不是 DTO），
 * 字段名与实体一致，改实体字段名会直接打断前端。
 */

/** 单条训练记录 */
export interface TrainingRecord {
  id: number
  userId: number
  /** yyyy-MM-dd */
  trainingDate: string
  actionName: string
  sets: number
  reps: number
  weightKg: number
  durationMin: number | null
  /** 主观感受 RPE 1-10 */
  rpe: number | null
  /** 训练容量 = 组数 × 次数 × 重量，**由后端计算**（前端只做输入预览） */
  volume: number
  remark: string | null
  /** yyyy-MM-dd HH:mm:ss */
  createdAt: string
  updatedAt: string
}

/** 新增单条训练记录请求 —— actionName/sets/reps/weightKg 必填 */
export interface TrainingRecordRequest {
  /** yyyy-MM-dd，不传默认当天 */
  trainingDate?: string
  actionName: string
  sets: number
  reps: number
  weightKg: number
  durationMin?: number
  rpe?: number
  remark?: string
}

/**
 * 修改训练记录请求
 * <p>
 * ⚠️ 后端此 DTO **不含 actionName**（规范定义为「所有字段可选」的部分更新），
 * 因此行内编辑只允许改组数/次数/重量/时长/RPE/备注，改动作名只能删了重加。
 */
export interface TrainingRecordUpdateRequest {
  sets?: number
  reps?: number
  weightKg?: number
  durationMin?: number
  rpe?: number
  remark?: string
}

/** 批量录入的子项 */
export interface TrainingBatchItem {
  actionName: string
  sets: number
  reps: number
  weightKg: number
  rpe?: number
  remark?: string
}

/** 批量新增请求（单次最多 20 条） */
export interface TrainingBatchRequest {
  trainingDate?: string
  durationMin?: number
  records: TrainingBatchItem[]
}

/** 批量新增响应 */
export interface TrainingBatchResponse {
  count: number
  totalVolume: number
  records: TrainingRecord[]
}

/** 按日期区间分页查询的参数 */
export interface TrainingQueryParams {
  /** yyyy-MM-dd，必填 */
  startDate: string
  /** yyyy-MM-dd，必填 */
  endDate: string
  page?: number
  /** 默认 50 */
  size?: number
}

/**
 * 按动作查询响应
 * <p>
 * 注意三点（与后端逐字对齐）：
 * - 它**不是** `PaginatedData`：后端不返回 `page`/`size`
 * - `maxWeight`/`maxVolume` 是该动作的**历史最大**值，没有记录时是 `0` 而**不是 null**
 * - 查询不传区间时后端默认从 2000-01-01 查到现在
 */
export interface ActionHistoryResponse {
  list: TrainingRecord[]
  total: number
  actionName: string
  /** 该动作历史最大重量；无记录时为 0 */
  maxWeight: number
  /** 该动作历史最大容量；无记录时为 0 */
  maxVolume: number
}

/** 修改记录响应（后端只回 id 与新容量） */
export interface TrainingUpdateResponse {
  id: number
  volume: number
}

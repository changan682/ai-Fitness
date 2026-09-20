import { create } from 'zustand'
import type { TrainingRecord } from '@/types'

/**
 * 今日训练本地状态
 *
 * <h3>职责边界（和 TanStack Query 不冲突）</h3>
 * - TanStack Query 负责**服务端状态**（拉取、缓存、失效、重试）
 * - 本 store 负责**本地乐观更新**：提交后先把界面更新掉，接口回来再对齐
 *
 * 规范要求「不持久化，每次进入 Dashboard 从 API 重新拉取」——
 * 训练数据属于强一致性场景，缓存到 localStorage 只会带来「昨天数据还在」的错觉。
 *
 * <h3>容量口径</h3>
 * 前端算的 `sets × reps × weightKg` **只用于输入预览与乐观占位**，
 * 最终展示一律以后端返回的 `volume` 为准（规范明确要求）。
 */

/** 本地计算容量（仅乐观占位用，后端会覆盖） */
export function previewVolume(record: Pick<TrainingRecord, 'sets' | 'reps' | 'weightKg'>): number {
  const sets = Number(record.sets) || 0
  const reps = Number(record.reps) || 0
  const weight = Number(record.weightKg) || 0
  // 保留 1 位小数，避免 0.1+0.2 这类浮点噪声显示成 60.300000000000004
  return Math.round(sets * reps * weight * 10) / 10
}

interface TrainingState {
  // ==================== 状态 ====================
  /** 今日训练记录（乐观更新后的本地视图） */
  todayRecords: TrainingRecord[]
  /** 今日总容量（由 todayRecords 汇总，单位 kg） */
  todayTotalVolume: number
  /** 是否有尚未与服务端对齐的本地修改 */
  isDirty: boolean

  // ==================== Actions ====================
  /** 用服务端数据整体覆盖（拉取成功后调用） */
  setTodayRecords: (records: TrainingRecord[]) => void
  /** 乐观新增：接口成功后会再用服务端返回的真实记录替换 */
  addRecord: (record: TrainingRecord) => void
  /** 乐观删除 */
  removeRecord: (id: number) => void
  /** 乐观修改（行内编辑） */
  updateRecord: (id: number, data: Partial<TrainingRecord>) => void
  /** 用服务端返回的权威数据替换本地占位记录（按 id 匹配） */
  replaceRecord: (record: TrainingRecord) => void
  /** 重算总容量 */
  recalculateVolume: () => void
  markDirty: (dirty: boolean) => void
  /** 清空（登出时调用，避免串号） */
  reset: () => void
}

/** 汇总总容量：无 volume 的记录用预览公式兜底 */
function sumVolume(records: TrainingRecord[]): number {
  const total = records.reduce((acc, r) => {
    const v = typeof r.volume === 'number' && !Number.isNaN(r.volume) ? r.volume : previewVolume(r)
    return acc + v
  }, 0)
  return Math.round(total * 10) / 10
}

export const useTrainingStore = create<TrainingState>((set, get) => ({
  todayRecords: [],
  todayTotalVolume: 0,
  isDirty: false,

  setTodayRecords: (records) =>
    set({
      todayRecords: records,
      todayTotalVolume: sumVolume(records),
      isDirty: false,
    }),

  addRecord: (record) => {
    const next = [...get().todayRecords, record]
    set({ todayRecords: next, todayTotalVolume: sumVolume(next), isDirty: true })
  },

  removeRecord: (id) => {
    const next = get().todayRecords.filter((r) => r.id !== id)
    set({ todayRecords: next, todayTotalVolume: sumVolume(next), isDirty: true })
  },

  updateRecord: (id, data) => {
    const next = get().todayRecords.map((r) => (r.id === id ? { ...r, ...data } : r))
    set({ todayRecords: next, todayTotalVolume: sumVolume(next), isDirty: true })
  },

  replaceRecord: (record) => {
    const list = get().todayRecords
    const exists = list.some((r) => r.id === record.id)
    const next = exists
      ? list.map((r) => (r.id === record.id ? record : r))
      : [...list, record]
    set({ todayRecords: next, todayTotalVolume: sumVolume(next), isDirty: false })
  },

  recalculateVolume: () => set({ todayTotalVolume: sumVolume(get().todayRecords) }),

  markDirty: (dirty) => set({ isDirty: dirty }),

  reset: () => set({ todayRecords: [], todayTotalVolume: 0, isDirty: false }),
}))

export default useTrainingStore

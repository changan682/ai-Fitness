import { App } from 'antd'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import dayjs from 'dayjs'
import { useEffect, useMemo, useState } from 'react'
import statsApi from '@/api/statsApi'
import trainingApi from '@/api/trainingApi'
import workoutApi from '@/api/workoutApi'
import DateHeader from '@/components/dashboard/DateHeader'
import QuickRecordForm from '@/components/dashboard/QuickRecordForm'
import StatsCards from '@/components/dashboard/StatsCards'
import TodayRecordsTable from '@/components/dashboard/TodayRecordsTable'
import { previewVolume, useTrainingStore, useUserStore } from '@/store'
import type {
  TrainingBatchRequest,
  TrainingRecord,
  TrainingRecordUpdateRequest,
} from '@/types'

const DASHBOARD_KEY = ['stats', 'dashboard'] as const
const TODAY_KEY = ['training', 'today'] as const
const TEMPLATES_KEY = ['workout', 'templates'] as const

const DATE_TIME = 'YYYY-MM-DD HH:mm:ss'

/**
 * 训练看板（首页）
 *
 * <h3>数据流</h3>
 * - 服务端状态用 TanStack Query（统计卡片、今日记录、模板动作名）
 * - 今日记录同时镜像进 `trainingStore`，用于**乐观更新**：提交后先把界面改掉，
 *   接口成功用服务端返回的权威记录替换占位行；失败则整表回滚到 mutation 前的快照。
 *
 * <h3>为什么乐观更新用快照回滚而不是逐条撤销</h3>
 * 一次批量提交可能插入多条占位记录，逐条撤销要处理「哪几条成功了」的分支；
 * 直接恢复 mutation 前的整表快照既简单又不会漏。
 */
export default function DashboardPage() {
  const { message } = App.useApp()
  const queryClient = useQueryClient()

  const userId = useUserStore((s) => s.userId())
  const todayRecords = useTrainingStore((s) => s.todayRecords)
  const setTodayRecords = useTrainingStore((s) => s.setTodayRecords)
  const addRecordLocal = useTrainingStore((s) => s.addRecord)
  const replaceRecord = useTrainingStore((s) => s.replaceRecord)
  const removeRecordLocal = useTrainingStore((s) => s.removeRecord)
  const updateRecordLocal = useTrainingStore((s) => s.updateRecord)

  const [pendingId, setPendingId] = useState<number | null>(null)

  // ==================== 查询 ====================

  const statsQuery = useQuery({
    queryKey: DASHBOARD_KEY,
    queryFn: () => statsApi.getDashboard(),
  })

  const todayQuery = useQuery({
    queryKey: TODAY_KEY,
    queryFn: () => trainingApi.getTodayRecords(),
  })

  // 动作名候选：模板里的动作（覆盖绝大多数）+ 用户今天已录过的动作
  const templatesQuery = useQuery({
    queryKey: TEMPLATES_KEY,
    queryFn: () => workoutApi.listTemplates(),
    staleTime: 5 * 60 * 1000,
  })

  useEffect(() => {
    if (todayQuery.data) {
      setTodayRecords(todayQuery.data)
    }
  }, [todayQuery.data, setTodayRecords])

  // 离开看板时清空本地镜像，避免下次以另一个账号登录时闪出上一个人的记录
  useEffect(() => {
    return () => {
      useTrainingStore.getState().reset()
    }
  }, [])

  const actionOptions = useMemo(() => {
    const fromTemplates = (templatesQuery.data?.list ?? []).flatMap((t) =>
      t.exercises.map((e) => e.actionName),
    )
    const fromToday = todayRecords.map((r) => r.actionName)
    return Array.from(new Set([...fromTemplates, ...fromToday])).sort()
  }, [templatesQuery.data, todayRecords])

  // ==================== 批量新增（乐观更新） ====================

  const addMutation = useMutation({
    mutationFn: (payload: TrainingBatchRequest) => trainingApi.batchAddRecords(payload),

    onMutate: async (payload) => {
      await queryClient.cancelQueries({ queryKey: TODAY_KEY })
      const previous = useTrainingStore.getState().todayRecords

      const now = dayjs().format(DATE_TIME)
      const date = payload.trainingDate ?? dayjs().format('YYYY-MM-DD')
      payload.records.forEach((r, idx) => {
        // 负数临时 id：与服务端自增 id 不会冲突，且一眼能看出是占位行
        const placeholder: TrainingRecord = {
          id: -(Date.now() + idx),
          userId: userId ?? 0,
          trainingDate: date,
          actionName: r.actionName,
          sets: r.sets,
          reps: r.reps,
          weightKg: r.weightKg,
          durationMin: payload.durationMin ?? null,
          rpe: r.rpe ?? null,
          volume: previewVolume(r),
          remark: r.remark ?? null,
          createdAt: now,
          updatedAt: now,
        }
        addRecordLocal(placeholder)
      })

      return { previous }
    },

    onError: (error, _payload, context) => {
      if (context?.previous) {
        setTodayRecords(context.previous)
      }
      message.error(`保存失败：${error instanceof Error ? error.message : '未知错误'}`)
    },

    onSuccess: (data) => {
      message.success('训练记录已保存')
      // 用服务端返回的权威记录（含后端算出的 volume）替换占位行
      data.records.forEach(replaceRecord)
    },

    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: TODAY_KEY })
      void queryClient.invalidateQueries({ queryKey: DASHBOARD_KEY })
    },
  })

  // ==================== 修改（乐观更新） ====================

  const updateMutation = useMutation({
    mutationFn: ({ id, data }: { id: number; data: TrainingRecordUpdateRequest }) =>
      trainingApi.updateRecord(id, data),

    onMutate: async ({ id, data }) => {
      await queryClient.cancelQueries({ queryKey: TODAY_KEY })
      const previous = useTrainingStore.getState().todayRecords
      setPendingId(id)

      // 本地先按新值算容量，避免等服务端回来才跳动
      const merged = previous.find((r) => r.id === id)
      const optimistic = merged
        ? { ...merged, ...data, volume: previewVolume({ ...merged, ...data }) }
        : undefined
      if (optimistic) updateRecordLocal(id, optimistic)

      return { previous }
    },

    onError: (error, _vars, context) => {
      if (context?.previous) setTodayRecords(context.previous)
      message.error(`修改失败：${error instanceof Error ? error.message : '未知错误'}`)
    },

    onSuccess: () => {
      message.success('记录已更新')
      setPendingId(null)
      // volume 由后端重算，这里必须重新拉取以回填权威值
      void queryClient.invalidateQueries({ queryKey: TODAY_KEY })
      void queryClient.invalidateQueries({ queryKey: DASHBOARD_KEY })
      useTrainingStore.getState().markDirty(false)
    },

    onSettled: () => setPendingId(null),
  })

  // ==================== 删除（乐观删除） ====================

  const deleteMutation = useMutation({
    mutationFn: (id: number) => trainingApi.deleteRecord(id),

    onMutate: async (id) => {
      await queryClient.cancelQueries({ queryKey: TODAY_KEY })
      const previous = useTrainingStore.getState().todayRecords
      setPendingId(id)
      removeRecordLocal(id)
      return { previous }
    },

    onError: (error, _id, context) => {
      if (context?.previous) setTodayRecords(context.previous)
      message.error(`删除失败：${error instanceof Error ? error.message : '未知错误'}`)
    },

    onSettled: () => {
      setPendingId(null)
      void queryClient.invalidateQueries({ queryKey: TODAY_KEY })
      void queryClient.invalidateQueries({ queryKey: DASHBOARD_KEY })
    },
  })

  // ==================== 渲染 ====================

  const handleSubmit = async (payload: TrainingBatchRequest): Promise<void> => {
    await addMutation.mutateAsync(payload)
  }

  const handleUpdate = async (
    id: number,
    data: TrainingRecordUpdateRequest,
  ): Promise<void> => {
    await updateMutation.mutateAsync({ id, data })
  }

  const handleDelete = async (id: number): Promise<void> => {
    await deleteMutation.mutateAsync(id)
  }

  return (
    <div>
      <DateHeader />
      <StatsCards
        data={statsQuery.data}
        isLoading={statsQuery.isLoading}
        isError={statsQuery.isError}
      />
      <QuickRecordForm
        actionOptions={actionOptions}
        submitting={addMutation.isPending}
        onSubmit={handleSubmit}
      />
      <TodayRecordsTable
        records={todayRecords}
        isLoading={todayQuery.isLoading}
        isError={todayQuery.isError}
        onRetry={() => void todayQuery.refetch()}
        onUpdate={handleUpdate}
        onDelete={handleDelete}
        pendingId={pendingId}
      />
    </div>
  )
}

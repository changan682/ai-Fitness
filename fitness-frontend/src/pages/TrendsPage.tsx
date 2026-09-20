import {
  Button,
  Card,
  DatePicker,
  Empty,
  Modal,
  Result,
  Segmented,
  Skeleton,
  Spin,
  Table,
  Tag,
  Tooltip,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useQuery } from '@tanstack/react-query'
import dayjs, { type Dayjs } from 'dayjs'
import ReactECharts from 'echarts-for-react'
import { useMemo, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import bodyMetricApi from '@/api/bodyMetricApi'
import statsApi from '@/api/statsApi'
import trainingApi from '@/api/trainingApi'
import type { BodyMetricTrendPoint, TrainingRecord } from '@/types'

const DATE_FMT = 'YYYY-MM-DD'
/** 规范强制：每个图表区域固定 300px 高（加载骨架与图表共用，切换时不跳动） */
const CHART_HEIGHT = 300

/**
 * 周统计请求数上限
 * <p>
 * 后端**只有 `GET /v1/stats/weekly?weekStart=`（单周）**，没有「一次取多周」的接口，
 * 因此多周柱状图只能一周一个请求。为不让 90 天区间刷出 14+ 个并发请求（周一归一会
 * 让 90 天落到 14 个自然周），这里封顶 13 周，且**保留最近的 13 周**（宁可丢最早的一格，
 * 也不丢区间末端的最新数据）。后端若将来提供多周接口，这里应替换成单次请求。
 */
const MAX_WEEKS = 13

type RangeKey = '7' | '30' | '90' | 'custom'

const RANGE_OPTIONS: { label: string; value: RangeKey }[] = [
  { label: '最近7天', value: '7' },
  { label: '30天', value: '30' },
  { label: '90天', value: '90' },
  { label: '自定义', value: 'custom' },
]

/** ECharts tooltip 回调的参数形状（echarts-for-react 的 option 类型是 any，这里自己收窄便于取值） */
interface TooltipItem {
  seriesName?: string
  marker?: string
  value?: number | null
  dataIndex?: number
}

/** 把日期归一到所在周的**周一**（显式算，不依赖 dayjs locale 的 weekStart 设置） */
function mondayOf(date: string): Dayjs {
  const d = dayjs(date)
  return d.subtract((d.day() + 6) % 7, 'day')
}

/** 非空样本数：`null` 是后端的「无样本」，不能当成 0 计入 */
function countNonNull(values: (number | null)[]): number {
  return values.filter((v) => v !== null).length
}

/** 数值展示：null/undefined 一律 `--`（显示 0 会被误读成真实数值） */
function showNumber(value: number | null | undefined, suffix = ''): string {
  return value === null || value === undefined ? '--' : `${value}${suffix}`
}

/** 加载态：Skeleton 占位（固定 300px）+ 居中 Spin（规范要求两者同时出现） */
function ChartLoading() {
  return (
    <div style={{ height: CHART_HEIGHT, position: 'relative' }}>
      <Skeleton active title={false} paragraph={{ rows: 8 }} />
      <div className="absolute inset-0 flex items-center justify-center">
        <Spin />
      </div>
    </div>
  )
}

/** 失败态：Result + 重试 */
function ChartError({ onRetry }: { onRetry: () => void }) {
  return (
    <Result
      status="error"
      title="数据加载失败"
      subTitle="请检查后端服务与网络后重试"
      extra={
        <Button type="primary" onClick={onRetry}>
          重试
        </Button>
      }
    />
  )
}

/** 「数据较少」提示：样本 < 3 个时挂在图表标题右侧，不改变图表本身的渲染 */
function InsufficientTag() {
  return (
    <Tooltip title="样本少于 3 个：后端在样本不足时会把 7 日滑动平均返回 null，图上表现为断点，趋势线仅供参考">
      <Tag color="warning">数据较少，趋势仅供参考</Tag>
    </Tooltip>
  )
}

/** 无身体数据：引导去个人档案录入 */
function NoBodyData({ onGoProfile }: { onGoProfile: () => void }) {
  return (
    <div style={{ height: CHART_HEIGHT }} className="flex items-center justify-center">
      <Empty description="尚未记录身体数据，去 [个人档案] 页面录入吧">
        <Button type="primary" onClick={onGoProfile}>
          去个人档案
        </Button>
      </Empty>
    </div>
  )
}

/** 无训练数据：与身体数据区分开，避免出现「去录入体测」这种答非所问的引导 */
function NoTrainingData() {
  return (
    <div style={{ height: CHART_HEIGHT }} className="flex items-center justify-center">
      <Empty description="该时间范围内还没有训练记录，去 [训练看板] 页面记录一次吧" />
    </div>
  )
}

interface ChartBodyProps {
  isLoading: boolean
  isError: boolean
  isEmpty: boolean
  onRetry: () => void
  emptyNode: ReactNode
  children: ReactNode
}

/** 图表容器：把 加载 / 失败 / 无数据 / 有数据 四种状态收在一处，避免三个图表各写一遍 */
function ChartBody({ isLoading, isError, isEmpty, onRetry, emptyNode, children }: ChartBodyProps) {
  if (isLoading) return <ChartLoading />
  if (isError) return <ChartError onRetry={onRetry} />
  if (isEmpty) return <>{emptyNode}</>
  return <>{children}</>
}

/** 周明细弹窗的表格列 */
const WEEK_COLUMNS: ColumnsType<TrainingRecord> = [
  { title: '日期', dataIndex: 'trainingDate', key: 'trainingDate', width: 110 },
  { title: '动作名称', dataIndex: 'actionName', key: 'actionName', ellipsis: true },
  {
    title: '组数 × 次数',
    key: 'setsReps',
    width: 110,
    render: (_value, record) => `${record.sets} × ${record.reps}`,
  },
  { title: '重量(kg)', dataIndex: 'weightKg', key: 'weightKg', width: 100 },
  {
    title: '容量(kg)',
    dataIndex: 'volume',
    key: 'volume',
    width: 110,
    render: (value: number) => <b>{value}</b>,
  },
  {
    title: 'RPE',
    dataIndex: 'rpe',
    key: 'rpe',
    width: 80,
    render: (value: number | null) => value ?? '—',
  },
]

/**
 * 数据趋势（/trends）
 *
 * <h3>时间范围与请求的关系</h3>
 * 区间直接进 queryKey，切换范围就是「新 key → 自动发起新请求」，
 * 等价于规范要求的 refetch；`isFetching` 用来在选择器旁显示 loading。
 *
 * <h3>三个图表的数据来源</h3>
 * - 体重趋势、围度变化：`GET /v1/body-metric/trend?startDate&endDate`（两个参数**必填**，
 *   后端没有默认区间），一次请求同时喂两个图
 * - 每周训练容量：`GET /v1/stats/weekly?weekStart=` 每周一次（后端无多周接口），
 *   见 `MAX_WEEKS` 的说明
 */
export default function TrendsPage() {
  const navigate = useNavigate()

  const [rangeKey, setRangeKey] = useState<RangeKey>('30')
  const [customRange, setCustomRange] = useState<[Dayjs, Dayjs] | null>(null)
  /** 被点击的柱子对应的周（null = 弹窗关闭） */
  const [weekModal, setWeekModal] = useState<{ weekStart: string; weekEnd: string } | null>(null)

  // ==================== 时间范围 → 起止日期 ====================

  const [startDate, endDate] = useMemo<[string, string]>(() => {
    const today = dayjs()
    if (rangeKey === 'custom') {
      // 切到「自定义」但还没挑日期：先按最近 30 天请求，避免出现空区间
      // （后端 startDate > endDate 会直接返回 2002 参数错误）
      const from = customRange?.[0] ?? today.subtract(29, 'day')
      const to = customRange?.[1] ?? today
      return [from.format(DATE_FMT), to.format(DATE_FMT)]
    }
    const days = Number(rangeKey)
    // 含今天，因此是 days-1 天前：最近 7 天 = 今天往前数 7 个自然日
    return [today.subtract(days - 1, 'day').format(DATE_FMT), today.format(DATE_FMT)]
  }, [rangeKey, customRange])

  /** 需要请求的周（周一）列表 */
  const weekStarts = useMemo(() => {
    const list: string[] = []
    let cursor = mondayOf(startDate)
    const last = dayjs(endDate)
    // 区间首日可能不在周一，因此从「区间首日所在周的周一」开始，到「区间末日」为止
    while (!cursor.isAfter(last, 'day')) {
      list.push(cursor.format(DATE_FMT))
      cursor = cursor.add(7, 'day')
    }
    // 超上限时保留最新的 13 周（丢掉最早的），保证区间末端始终可见
    return list.length > MAX_WEEKS ? list.slice(-MAX_WEEKS) : list
  }, [startDate, endDate])

  // ==================== 查询 ====================

  const trendQuery = useQuery({
    queryKey: ['bodyMetric', 'trend', startDate, endDate],
    queryFn: () => bodyMetricApi.getTrend(startDate, endDate),
  })

  const weeklyQuery = useQuery({
    queryKey: ['stats', 'weekly-range', startDate, endDate],
    queryFn: () => Promise.all(weekStarts.map((w) => statsApi.getWeeklyStats(w))),
    enabled: weekStarts.length > 0,
  })

  const weekRecordsQuery = useQuery({
    queryKey: ['training', 'week-records', weekModal?.weekStart ?? ''],
    queryFn: () => {
      const target = weekModal
      // enabled=false 时 queryFn 不会被调用；这里返回空页只是为了让 TS 收窄类型
      if (!target) return Promise.resolve({ list: [] as TrainingRecord[], total: 0, page: 1, size: 50 })
      return trainingApi.queryByDateRange({
        startDate: target.weekStart,
        endDate: target.weekEnd,
        page: 1,
        size: 50,
      })
    },
    enabled: weekModal !== null,
  })

  // ==================== 图表数据整理 ====================

  const chartData = useMemo(() => {
    const list: BodyMetricTrendPoint[] = trendQuery.data?.list ?? []
    const dates = list.map((p) => dayjs(p.recordDate).format('MM-DD'))

    // ⚠️ 体重/围度一律**原样保留 null**：后端缺失日期不补 0，7 日平均在样本 < 3 时返回 null。
    // 若在这里填 0，折线会从 70kg 直接掉到 0，画出一条断崖式的假下跌。
    const weightRaw = list.map((p) => p.weightKg)
    const weightAvg = list.map((p) => p.weightAvg7d)
    const waist = list.map((p) => p.waistCm)
    const arm = list.map((p) => p.armCm)
    const leg = list.map((p) => p.legCm)

    // 逐点变化量：后端只给区间级的 weightChange，tooltip 要的「较上次」只能按相邻非空点自己算
    const weightChanges: (number | null)[] = []
    let prevWeight: number | null = null
    for (const p of list) {
      weightChanges.push(
        p.weightKg !== null && prevWeight !== null
          ? Math.round((p.weightKg - prevWeight) * 10) / 10
          : null,
      )
      if (p.weightKg !== null) prevWeight = p.weightKg
    }

    return {
      list,
      dates,
      weightRaw,
      weightAvg,
      waist,
      arm,
      leg,
      weightChanges,
      /** 体重样本数（判定「数据较少」） */
      weightSamples: countNonNull(weightRaw),
      /** 围度样本数：按「当天至少填了一个围度」计数，而不是三条线相加 */
      measureSamples: list.filter(
        (p) => p.waistCm !== null || p.armCm !== null || p.legCm !== null,
      ).length,
    }
  }, [trendQuery.data])

  const weeklyData = useMemo(() => {
    const list = weeklyQuery.data ?? []
    return {
      list,
      labels: list.map((w) => dayjs(w.weekStart).format('MM-DD')),
      volumes: list.map((w) => w.totalVolume),
      /** 规范写的是「训练次数」，但真实接口只给 trainingDays/totalActions，没有「训练次数」字段 */
      days: list.map((w) => w.trainingDays),
      /** 有训练记录的周数（判定「数据较少」） */
      weeksWithTraining: list.filter((w) => w.totalVolume > 0).length,
    }
  }, [weeklyQuery.data])

  // ==================== ECharts option ====================

  const weightOption = useMemo(
    () => ({
      animation: true,
      legend: { data: ['原始体重', '7日滑动平均'] },
      grid: { left: 56, right: 24, top: 48, bottom: 32 },
      tooltip: {
        trigger: 'axis',
        formatter: (params: unknown) => {
          const items = (Array.isArray(params) ? params : [params]) as TooltipItem[]
          const idx = items[0]?.dataIndex ?? 0
          const lines = [`<b>${chartData.list[idx]?.recordDate ?? ''}</b>`]
          items.forEach((it) => {
            lines.push(
              `${it.marker ?? ''}${it.seriesName ?? ''}：${
                it.value === null || it.value === undefined ? '暂无样本' : `${it.value} kg`
              }`,
            )
          })
          const delta = chartData.weightChanges[idx]
          lines.push(
            `较上次：${delta === null || delta === undefined ? '--' : `${delta > 0 ? '+' : ''}${delta} kg`}`,
          )
          return lines.join('<br/>')
        },
      },
      xAxis: { type: 'category', data: chartData.dates, boundaryGap: false },
      yAxis: { type: 'value', name: 'kg', scale: true },
      series: [
        {
          name: '原始体重',
          type: 'line',
          data: chartData.weightRaw,
          // 断点必须真的断开，不能把 null 连起来（否则等于补 0 的另一种画法）
          connectNulls: false,
          symbolSize: 6,
          lineStyle: { type: 'dashed', width: 2, color: '#1677ff' },
          itemStyle: { color: '#1677ff' },
        },
        {
          name: '7日滑动平均',
          type: 'line',
          data: chartData.weightAvg,
          connectNulls: false,
          smooth: true,
          symbol: 'none',
          lineStyle: { type: 'solid', width: 3, color: '#ff4d4f' },
          itemStyle: { color: '#ff4d4f' },
        },
      ],
    }),
    [chartData],
  )

  const weeklyOption = useMemo(
    () => ({
      animation: true,
      legend: { data: ['总容量(kg)', '训练天数'] },
      grid: { left: 60, right: 48, top: 48, bottom: 48 },
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'shadow' },
        formatter: (params: unknown) => {
          const items = (Array.isArray(params) ? params : [params]) as TooltipItem[]
          const idx = items[0]?.dataIndex ?? 0
          // 周区间优先用接口返回的 weekStart（后端会归一到它自己的周一），
          // 只在数据还没回来时退回本地算的周一
          const week = weeklyData.list[idx]?.weekStart ?? weekStarts[idx] ?? ''
          const lines = [`<b>${week} 所在周</b>`]
          items.forEach((it) => {
            lines.push(`${it.marker ?? ''}${it.seriesName ?? ''}：${it.value ?? 0}`)
          })
          return `${lines.join('<br/>')}<br/>点击柱子可查看该周明细`
        },
      },
      xAxis: {
        type: 'category',
        data: weeklyData.labels,
        axisLabel: { rotate: weeklyData.labels.length > 8 ? 45 : 0 },
      },
      // 容量(kg) 与 训练天数 量纲不同，必须双 Y 轴，否则天数会被容量压成一条平线
      yAxis: [
        { type: 'value', name: 'kg' },
        { type: 'value', name: '天', minInterval: 1, splitLine: { show: false } },
      ],
      series: [
        {
          name: '总容量(kg)',
          type: 'bar',
          data: weeklyData.volumes,
          barMaxWidth: 32,
          itemStyle: { color: '#1677ff', borderRadius: [4, 4, 0, 0] },
        },
        {
          name: '训练天数',
          type: 'line',
          yAxisIndex: 1,
          data: weeklyData.days,
          smooth: true,
          symbolSize: 8,
          lineStyle: { color: '#faad14', width: 2 },
          itemStyle: { color: '#faad14' },
        },
      ],
    }),
    [weeklyData, weekStarts],
  )

  const measureOption = useMemo(
    () => ({
      animation: true,
      legend: { data: ['腰围', '臂围', '腿围'] },
      grid: { left: 56, right: 24, top: 48, bottom: 32 },
      tooltip: {
        trigger: 'axis',
        formatter: (params: unknown) => {
          const items = (Array.isArray(params) ? params : [params]) as TooltipItem[]
          const idx = items[0]?.dataIndex ?? 0
          const lines = [`<b>${chartData.list[idx]?.recordDate ?? ''}</b>`]
          items.forEach((it) => {
            lines.push(
              `${it.marker ?? ''}${it.seriesName ?? ''}：${
                it.value === null || it.value === undefined ? '未记录' : `${it.value} cm`
              }`,
            )
          })
          return lines.join('<br/>')
        },
      },
      xAxis: { type: 'category', data: chartData.dates, boundaryGap: false },
      yAxis: { type: 'value', name: 'cm', scale: true },
      series: [
        // 三条线同样保留 null（某天只量了腰围时，臂围/腿围必须是断点而不是 0）
        {
          name: '腰围',
          type: 'line',
          data: chartData.waist,
          connectNulls: false,
          symbolSize: 5,
          lineStyle: { width: 2, color: '#fa8c16' },
          itemStyle: { color: '#fa8c16' },
        },
        {
          name: '臂围',
          type: 'line',
          data: chartData.arm,
          connectNulls: false,
          symbolSize: 5,
          lineStyle: { width: 2, color: '#52c41a' },
          itemStyle: { color: '#52c41a' },
        },
        {
          name: '腿围',
          type: 'line',
          data: chartData.leg,
          connectNulls: false,
          symbolSize: 5,
          lineStyle: { width: 2, color: '#722ed1' },
          itemStyle: { color: '#722ed1' },
        },
      ],
    }),
    [chartData],
  )

  // ==================== 交互 ====================

  const isFetching = trendQuery.isFetching || weeklyQuery.isFetching

  const handleBarClick = (params: { dataIndex?: number }): void => {
    const idx = params?.dataIndex
    if (idx === undefined) return
    const week = weeklyData.list[idx]
    if (!week) return
    // 弹窗区间取接口返回的 weekStart/weekEnd（后端会归一到它自己的周一）：
    // 本地按「周一起算 7 天」推算的区间若与后端口径不一致，明细就会查到别的周
    setWeekModal({
      weekStart: week.weekStart,
      weekEnd: week.weekEnd || dayjs(week.weekStart).add(6, 'day').format(DATE_FMT),
    })
  }

  const goProfile = (): void => {
    void navigate('/profile')
  }

  const retryAll = (): void => {
    void trendQuery.refetch()
    void weeklyQuery.refetch()
  }

  // ==================== 渲染 ====================

  return (
    <div>
      <Card className="mb-4">
        <div className="flex flex-wrap items-center gap-3">
          <span className="text-sm text-gray-500">时间范围</span>
          <Segmented<RangeKey>
            options={RANGE_OPTIONS}
            value={rangeKey}
            // 请求中先禁用，避免连续切换导致多个区间同时在途、图表来回跳
            disabled={isFetching}
            onChange={setRangeKey}
          />
          {/* 规范要求切换范围时选择器上有 loading 标记：Segmented 没有 loading 属性，用相邻 Spin 表达 */}
          {isFetching && <Spin size="small" />}
          {rangeKey === 'custom' && (
            <DatePicker.RangePicker
              value={customRange}
              allowClear
              disabledDate={(current: Dayjs) => current.isAfter(dayjs(), 'day')}
              onChange={(dates) => {
                // 清空时 dates 为 null：回到「未选」状态，由 startDate 的计算兜底成最近 30 天
                setCustomRange(dates && dates[0] && dates[1] ? [dates[0], dates[1]] : null)
              }}
            />
          )}
          <span className="text-xs text-gray-400">
            当前区间：{startDate} ~ {endDate}
          </span>
        </div>
      </Card>

      <Card
        title="体重趋势"
        className="mb-4"
        extra={
          trendQuery.isSuccess && chartData.weightSamples > 0 && chartData.weightSamples < 3 ? (
            <InsufficientTag />
          ) : null
        }
      >
        <ChartBody
          isLoading={trendQuery.isLoading}
          isError={trendQuery.isError}
          isEmpty={chartData.list.length === 0}
          onRetry={retryAll}
          emptyNode={<NoBodyData onGoProfile={goProfile} />}
        >
          {/* 规范要求 responsive: true —— echarts-for-react 没有该属性，对应的是 autoResize（默认 true，监听容器尺寸变化） */}
          <ReactECharts
            option={weightOption}
            style={{ height: CHART_HEIGHT }}
            autoResize
            notMerge
          />
        </ChartBody>
      </Card>

      <Card
        title="每周训练容量"
        className="mb-4"
        extra={<span className="text-xs text-gray-400">点击柱子查看该周训练明细</span>}
      >
        <ChartBody
          isLoading={weeklyQuery.isLoading}
          isError={weeklyQuery.isError}
          isEmpty={weeklyData.weeksWithTraining === 0}
          onRetry={retryAll}
          emptyNode={<NoTrainingData />}
        >
          <ReactECharts
            option={weeklyOption}
            style={{ height: CHART_HEIGHT }}
            autoResize
            notMerge
            onEvents={{ click: handleBarClick }}
          />
        </ChartBody>
        <div className="mt-2 flex items-center justify-between">
          <span className="text-xs text-gray-400">
            叠加线为「训练天数」（接口只有 trainingDays/totalActions，没有规范里写的「训练次数」字段）
          </span>
          <span className="text-xs text-gray-400">
            共 {weeklyData.labels.length} 个周请求（后端无多周统计接口，单次上限 {MAX_WEEKS} 周）
            {weeklyQuery.isSuccess && weeklyData.weeksWithTraining > 0 && weeklyData.weeksWithTraining < 3 ? (
              <span className="ml-2">
                <InsufficientTag />
              </span>
            ) : null}
          </span>
        </div>
      </Card>

      <Card
        title="围度变化"
        extra={
          trendQuery.isSuccess && chartData.measureSamples > 0 && chartData.measureSamples < 3 ? (
            <InsufficientTag />
          ) : null
        }
      >
        <ChartBody
          isLoading={trendQuery.isLoading}
          isError={trendQuery.isError}
          isEmpty={chartData.list.length === 0}
          onRetry={retryAll}
          emptyNode={<NoBodyData onGoProfile={goProfile} />}
        >
          <ReactECharts
            option={measureOption}
            style={{ height: CHART_HEIGHT }}
            autoResize
            notMerge
          />
        </ChartBody>
      </Card>

      <Modal
        open={weekModal !== null}
        title={
          weekModal
            ? `${weekModal.weekStart} ~ ${weekModal.weekEnd} 训练记录`
            : ''
        }
        onCancel={() => setWeekModal(null)}
        width={780}
        footer={
          <Button onClick={() => setWeekModal(null)}>关闭</Button>
        }
      >
        {/* 弹窗内的加载/空/失败状态自成一套：柱状图本身已经加载完了，这里只反映明细请求 */}
        {weekRecordsQuery.isError ? (
          <Result
            status="error"
            title="训练记录加载失败"
            extra={
              <Button type="primary" onClick={() => void weekRecordsQuery.refetch()}>
                重试
              </Button>
            }
          />
        ) : (
          <Table<TrainingRecord>
            rowKey="id"
            size="small"
            loading={weekRecordsQuery.isPending}
            dataSource={weekRecordsQuery.data?.list ?? []}
            columns={WEEK_COLUMNS}
            pagination={false}
            scroll={{ x: 680 }}
            locale={{ emptyText: <Empty description="这一周没有训练记录" /> }}
          />
        )}
        {weekRecordsQuery.data && weekRecordsQuery.data.total > 50 && (
          <div className="mt-2 text-xs text-gray-400">
            共 {weekRecordsQuery.data.total} 条，仅展示前 50 条
          </div>
        )}
      </Modal>

      {/* 区间概览：把后端给的区间级汇总直接展示，省得用户自己数图上的点 */}
      {trendQuery.data && (
        <div className="mt-3 text-xs text-gray-400">
          区间内体测 {trendQuery.data.totalRecords} 条 · 体重变化：
          {showNumber(trendQuery.data.weightChange, ' kg')} · 最新体重：
          {showNumber(trendQuery.data.latestWeight, ' kg')}
        </div>
      )}
    </div>
  )
}

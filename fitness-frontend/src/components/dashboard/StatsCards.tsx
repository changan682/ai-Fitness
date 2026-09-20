import { Card, Col, Row, Skeleton, Statistic, Tooltip } from 'antd'
import CountUp from 'react-countup'
import type { DashboardStats } from '@/types'

interface Props {
  data: DashboardStats | undefined
  isLoading: boolean
  isError: boolean
}

/** 单个统计卡片：数字用 react-countup 做滚动动画 */
function StatCard({
  title,
  value,
  suffix,
  decimals = 0,
  tip,
  loading,
  failed,
}: {
  title: string
  value: number | null | undefined
  suffix?: string
  decimals?: number
  tip?: string
  loading: boolean
  failed: boolean
}) {
  if (loading) {
    return (
      <Card>
        <Skeleton active paragraph={{ rows: 1 }} title={{ width: '60%' }} />
      </Card>
    )
  }

  const titleNode = tip ? (
    <Tooltip title={tip}>
      <span className="cursor-help">{title}</span>
    </Tooltip>
  ) : (
    title
  )

  // 加载失败或数据缺失一律显示 --，而不是显示 0（0 会被误读成「今天没练」）
  const hasValue = !failed && value !== null && value !== undefined

  return (
    <Card>
      <Statistic
        title={titleNode}
        value={hasValue ? undefined : '--'}
        suffix={hasValue ? suffix : undefined}
        formatter={
          hasValue
            ? () => (
                <CountUp
                  end={value as number}
                  decimals={decimals}
                  duration={0.8}
                  separator=","
                />
              )
            : undefined
        }
      />
    </Card>
  )
}

/**
 * Dashboard 统计卡片行
 *
 * <p>一次 `GET /v1/stats/dashboard` 拿全部数据（规范要求），不额外请求。
 * <p>关于「最新体重」：取的是 `t_body_metric` 的最新记录（`latestWeight`），
 * **不是**档案里的初始体重（`t_user.weight`）—— 两者独立维护，规范明确要求。
 */
export default function StatsCards({ data, isLoading, isError }: Props) {
  return (
    <Row gutter={[16, 16]} className="mb-4">
      <Col xs={12} md={6}>
        <StatCard
          title="今日训练动作数"
          value={data?.todayActionCount}
          suffix="个"
          loading={isLoading}
          failed={isError}
        />
      </Col>
      <Col xs={12} md={6}>
        <StatCard
          title="今日总容量"
          value={data?.todayTotalVolume}
          suffix="kg"
          decimals={0}
          loading={isLoading}
          failed={isError}
        />
      </Col>
      <Col xs={12} md={6}>
        <StatCard
          title="连续训练天数"
          value={data?.consecutiveTrainingDays}
          suffix="天"
          tip="今日尚未训练也不会归零（后端已做「今日未练不算断」处理）"
          loading={isLoading}
          failed={isError}
        />
      </Col>
      <Col xs={12} md={6}>
        <StatCard
          title="最新体重"
          value={data?.latestWeight}
          suffix="kg"
          decimals={1}
          tip="取最新一条体测记录；与档案里的初始体重相互独立"
          loading={isLoading}
          failed={isError}
        />
      </Col>
    </Row>
  )
}

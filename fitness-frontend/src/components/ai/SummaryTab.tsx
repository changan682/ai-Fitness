import { ReloadOutlined, RocketOutlined } from '@ant-design/icons'
import { App, Button, Card, DatePicker, Empty, Result, Skeleton, Space, Tag } from 'antd'
import { useMutation } from '@tanstack/react-query'
import dayjs, { type Dayjs } from 'dayjs'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import aiApi from '@/api/aiApi'
import { ApiError } from '@/api/client'
import MarkdownView from '@/components/common/MarkdownView'
import { ErrorCode } from '@/types'
import type { AiSummaryResponse } from '@/types'

/** 后端约定的日期格式 */
const DATE_FORMAT = 'YYYY-MM-DD'

/**
 * Tab 1 — 训练总结
 *
 * <h3>为什么用 useMutation 而不是 useQuery</h3>
 * 生成动作由用户点按钮触发（还带日期参数），不是「进页面就该拿的数据」。
 * 用 query 会导致切换日期时自动请求，白烧大模型额度。
 *
 * <h3>「缓存命中」怎么体现</h3>
 * 后端双层缓存（Redis + MySQL）命中时返回 `cached: true`，并且**日期是历史日期**；
 * 这里据此显示「上次生成于 HH:mm」，让用户知道这份总结不是刚算的。
 */
export default function SummaryTab() {
  const { message } = App.useApp()
  const navigate = useNavigate()
  const [date, setDate] = useState<Dayjs>(dayjs())
  const [result, setResult] = useState<AiSummaryResponse | null>(null)
  /** AI 服务不可用时的兜底文案（与后端 ai.fallback.summary 保持一致） */
  const [fallback, setFallback] = useState<string | null>(null)
  /** 当日无训练记录（后端 2003） */
  const [noRecord, setNoRecord] = useState(false)

  const mutation = useMutation({
    mutationFn: (targetDate: string) => aiApi.generateSummary({ date: targetDate }),
    onMutate: () => {
      // 重新生成前先清掉上一次的三种结果，避免旧内容与新状态并存
      setResult(null)
      setFallback(null)
      setNoRecord(false)
    },
    onSuccess: (data) => {
      setResult(data)
      if (data.cached) {
        message.info('命中缓存，直接返回上次生成的总结')
      } else {
        message.success('训练总结已生成')
      }
    },
    onError: (error) => {
      if (error instanceof ApiError && error.code === ErrorCode.NO_TRAINING_RECORD) {
        setNoRecord(true)
        return
      }
      // 6001/6002/9999 等：显示兜底文案卡片（规范第十一章第 7 条）
      setFallback('AI 教练暂时走神了，请稍后再试 😅')
    },
  })

  const handleGenerate = (): void => {
    mutation.mutate(date.format(DATE_FORMAT))
  }

  return (
    <Card>
      <Space wrap className="mb-4">
        <DatePicker
          value={date}
          onChange={(value) => value && setDate(value)}
          allowClear={false}
          disabled={mutation.isPending}
        />
        <Button
          type="primary"
          icon={<RocketOutlined />}
          loading={mutation.isPending}
          onClick={handleGenerate}
        >
          {mutation.isPending ? 'AI教练正在分析你的训练数据...' : '生成今日AI总结'}
        </Button>
      </Space>

      {mutation.isPending && (
        <div>
          <Skeleton active paragraph={{ rows: 6 }} />
        </div>
      )}

      {!mutation.isPending && noRecord && (
        <Result
          status="warning"
          title="今日暂无训练记录，先去训练吧！"
          subTitle="录入训练记录后即可生成 AI 总结"
          extra={
            <Button type="primary" onClick={() => navigate('/dashboard')}>
              去记录训练
            </Button>
          }
        />
      )}

      {!mutation.isPending && fallback && (
        <Card
          style={{ borderColor: '#faad14', background: '#fffbe6' }}
          styles={{ body: { padding: 16 } }}
        >
          <div className="mb-3 text-gray-700">{fallback}</div>
          <Button icon={<ReloadOutlined />} onClick={handleGenerate}>
            重新生成
          </Button>
        </Card>
      )}

      {!mutation.isPending && result && (
        <div>
          {result.cached && (
            <Tag color="blue" className="mb-3">
              上次生成于 {dayjs(result.generatedAt).format('HH:mm')}
            </Tag>
          )}
          <MarkdownView content={result.summary} />
        </div>
      )}

      {!mutation.isPending && !result && !fallback && !noRecord && (
        <Empty description="点击按钮生成今日训练总结" />
      )}
    </Card>
  )
}

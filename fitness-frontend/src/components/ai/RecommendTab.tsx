import { SearchOutlined } from '@ant-design/icons'
import { Alert, Button, Card, Col, Empty, Row, Select, Skeleton, Space, Tag, Tooltip } from 'antd'
import { useMutation } from '@tanstack/react-query'
import { useState } from 'react'
import aiApi from '@/api/aiApi'
import { MuscleGroup, toOptions } from '@/types'
import type { AiRecommendResponse } from '@/types'

/** 常用器械（AutoComplete 式多选，允许用户自定义输入） */
const EQUIPMENT_OPTIONS = [
  '哑铃',
  '杠铃',
  '绳索',
  '自重',
  '卧推凳',
  '可调节凳',
  '单杠',
  '器械',
]

/**
 * Tab 2 — 动作推荐
 *
 * <p>目标肌群的取值必须落在后端白名单内（胸/背/腿/肩/手臂/核心），
 * 因此直接用 `MuscleGroup` 枚举生成下拉项，不允许自由输入 ——
 * 传别的值后端会返回 9003，属于本可避免的往返。
 */
export default function RecommendTab() {
  const [muscle, setMuscle] = useState<MuscleGroup | undefined>(undefined)
  const [equipment, setEquipment] = useState<string[]>(['哑铃'])
  const [result, setResult] = useState<AiRecommendResponse | null>(null)

  const mutation = useMutation({
    mutationFn: () =>
      aiApi.recommend({
        targetMuscle: muscle as MuscleGroup,
        equipment,
        count: 5,
      }),
    onMutate: () => setResult(null),
    onSuccess: (data) => setResult(data),
  })

  const canSubmit = Boolean(muscle) && equipment.length > 0 && !mutation.isPending

  return (
    <Card>
      <Space wrap className="mb-4" size="middle">
        <span className="text-sm text-gray-600">目标肌群</span>
        <Select
          placeholder="请选择目标肌群"
          style={{ width: 160 }}
          options={toOptions(MuscleGroup)}
          value={muscle}
          onChange={setMuscle}
          disabled={mutation.isPending}
        />

        <span className="text-sm text-gray-600">可用器械</span>
        <Select
          mode="multiple"
          style={{ minWidth: 280 }}
          placeholder="可多选"
          options={EQUIPMENT_OPTIONS.map((e) => ({ label: e, value: e }))}
          value={equipment}
          onChange={setEquipment}
          maxTagCount="responsive"
          disabled={mutation.isPending}
        />

        <Tooltip title={muscle ? '' : '请选择目标肌群'}>
          <Button
            type="primary"
            icon={<SearchOutlined />}
            disabled={!canSubmit}
            loading={mutation.isPending}
            onClick={() => mutation.mutate()}
          >
            获取推荐
          </Button>
        </Tooltip>
      </Space>

      {equipment.length === 0 && <Alert type="warning" showIcon message="请至少选择一种可用器械" className="mb-4" />}

      {mutation.isPending && (
        <Row gutter={[16, 16]}>
          {[0, 1, 2, 3, 4].map((i) => (
            <Col xs={24} sm={12} lg={8} key={i}>
              <Card>
                <Skeleton active paragraph={{ rows: 3 }} />
              </Card>
            </Col>
          ))}
        </Row>
      )}

      {!mutation.isPending && mutation.isError && (
        <Alert
          type="error"
          showIcon
          message="获取推荐失败"
          description="AI 服务可能暂时不可用，请稍后重试"
          action={
            <Button size="small" onClick={() => mutation.mutate()}>
              重试
            </Button>
          }
        />
      )}

      {!mutation.isPending && result && result.recommendations.length > 0 && (
        <Row gutter={[16, 16]}>
          {result.recommendations.map((item, index) => (
            <Col xs={24} sm={12} lg={8} key={`${item.actionName}-${index}`}>
              {/* 依次淡入：animationDelay 按序号递增 */}
              <Card
                title={item.actionName}
                className="h-full"
                style={{ animation: 'fadeInUp .3s ease both', animationDelay: `${index * 70}ms` }}
              >
                <div className="mb-2 text-sm text-gray-600">
                  {item.recommendedSets ?? '—'} 组 × {item.recommendedReps ?? '—'} 次
                  {item.difficulty && (
                    <Tag className="ml-2" color="blue">
                      {item.difficulty}
                    </Tag>
                  )}
                </div>
                {item.targetMuscle && (
                  <div className="mb-2 text-xs text-gray-500">
                    目标肌群：{item.targetMuscle}
                    {item.focusArea ? ` · ${item.focusArea}` : ''}
                  </div>
                )}
                {item.equipment.length > 0 && (
                  <div className="mb-2">
                    {item.equipment.map((e) => (
                      <Tag key={e}>{e}</Tag>
                    ))}
                  </div>
                )}
                {item.notes && (
                  <Alert type="warning" showIcon message={item.notes} style={{ fontSize: 12 }} />
                )}
              </Card>
            </Col>
          ))}
        </Row>
      )}

      {!mutation.isPending && !result && !mutation.isError && (
        <Empty description="选择目标肌群与可用器械后获取推荐" />
      )}
    </Card>
  )
}

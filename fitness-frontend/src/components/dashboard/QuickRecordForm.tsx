import { DeleteOutlined, PlusOutlined } from '@ant-design/icons'
import { App, AutoComplete, Button, Card, Form, InputNumber, Rate, Space, Tooltip } from 'antd'
import { useMemo } from 'react'
import type { TrainingBatchItem, TrainingBatchRequest } from '@/types'

/**
 * 单次可提交的动作条目上限
 * <p>
 * 后端 `TrainingBatchRequest.records` 带 `@Size(max = 20)`，超了直接返回 9003。
 * 在前端就卡住行数，避免用户填完一整屏才被拒。
 */
const MAX_ROWS = 20

interface Props {
  /** 动作名候选项（训练模板里的动作 + 今日已记录过的动作） */
  actionOptions: string[]
  /** 提交中（按钮 loading、表单禁用） */
  submitting: boolean
  /**
   * 提交回调：交给页面层做乐观更新 + 失效刷新
   * <p>返回的 Promise **失败时会被 reject**，本组件据此决定是否重置表单
   * （规范：成功才清空并保留动作名；失败要保持可编辑、不清空已填数据）。
   */
  onSubmit: (payload: TrainingBatchRequest) => Promise<void>
}

interface FormValues {
  records: Partial<TrainingBatchItem>[]
}

/** 容量预览 = 组数 × 次数 × 重量（仅预览，最终以后端为准） */
function rowVolume(row: Partial<TrainingBatchItem> | undefined): number {
  if (!row) return 0
  const v = (row.sets ?? 0) * (row.reps ?? 0) * (row.weightKg ?? 0)
  return Math.round(v * 10) / 10
}

/**
 * 快捷录入表单
 *
 * <h3>与规范 ASCII 稿的一处偏差（有意为之，已与后端模型对齐）</h3>
 * 规范画的每一行是「第N组」，只含 重量/次数/RPE；但后端 `t_training_record`
 * 的一条记录是「一个动作 + 一个 `sets` 标量（组数）」，并没有「一组一行」的模型。
 * 若把每个组行都建成一条记录，今日列表会变成一堆「1组×10次」的碎片；
 * 若强行合并又会丢掉各组不同的重量。
 *
 * 因此这里把**每一行定义为一个动作条目**（动作名/组数/次数/重量/RPE），
 * 一次提交对应批量录入接口（单次 ≤20 条）。这样：
 * - 想一次练多个动作 → 加多行，一次提交（这正是批量接口的设计意图）
 * - 只有重量不同的多组 → 加成多行、组数填 1，数据一点不丢
 *
 * 容量预览仍按「组数 × 次数 × 重量」实时计算（规范要求），最终值以后端返回为准。
 */
export default function QuickRecordForm({ actionOptions, submitting, onSubmit }: Props) {
  const [form] = Form.useForm<FormValues>()
  const { message } = App.useApp()

  // Form.useWatch 取整个 records 数组，用于实时算容量预览
  const records = Form.useWatch('records', form) as Partial<TrainingBatchItem>[] | undefined

  const totalPreview = useMemo(
    () => (records ?? []).reduce((acc, row) => acc + rowVolume(row), 0),
    [records],
  )

  const handleFinish = async (values: FormValues): Promise<void> => {
    const rows = (values.records ?? []).filter((r) => r.actionName && r.sets && r.reps)
    if (rows.length === 0) {
      message.warning('请至少填写一个完整的动作')
      return
    }
    const payload: TrainingBatchRequest = {
      records: rows.map((r) => ({
        actionName: (r.actionName as string).trim(),
        sets: Number(r.sets),
        reps: Number(r.reps),
        weightKg: Number(r.weightKg ?? 0),
        rpe: r.rpe ?? undefined,
      })),
    }

    try {
      await onSubmit(payload)
      // 成功：重置为空但**保留动作名**（规范要求），省得用户重复输入同一个动作
      form.setFieldsValue({
        records: [{ actionName: rows[0]?.actionName, sets: undefined, reps: undefined, weightKg: undefined, rpe: undefined }],
      })
    } catch {
      // 失败：保持可编辑、不清空已填数据（错误提示由页面层的 mutation 负责）
    }
  }

  // Ctrl/Cmd + Enter 快捷提交（规范要求的快捷键）
  const handleKeyDown = (e: React.KeyboardEvent): void => {
    if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
      e.preventDefault()
      form.submit()
    }
  }

  return (
    <Card
      title="快捷录入"
      className="mb-4"
      extra={
        <span className="text-xs text-gray-400">
          容量预览 <b>{totalPreview}</b> kg（最终以后端计算为准） · Ctrl+Enter 提交
        </span>
      }
    >
      <Form
        form={form}
        layout="vertical"
        disabled={submitting}
        initialValues={{ records: [{ sets: 4, reps: 10, weightKg: 20, rpe: 6 }] }}
        onFinish={handleFinish}
      >
        <div onKeyDown={handleKeyDown}>
          <Form.List name="records">
            {(fields, { add, remove }) => (
              <>
                {fields.map((field) => (
                  <Space
                    key={field.key}
                    align="baseline"
                    className="mb-2 flex w-full flex-wrap"
                    size="small"
                  >
                    <Form.Item
                      name={[field.name, 'actionName']}
                      rules={[{ required: true, message: '请选择或输入动作名称' }]}
                      className="mb-1 min-w-[160px] flex-1"
                    >
                      <AutoComplete
                        options={actionOptions.map((a) => ({ value: a }))}
                        placeholder="动作名称"
                        filterOption={(input, option) =>
                          String(option?.value ?? '').includes(input)
                        }
                      />
                    </Form.Item>

                    <Form.Item name={[field.name, 'sets']} className="mb-1">
                      <InputNumber min={1} max={99} placeholder="组数" addonAfter="组" style={{ width: 110 }} />
                    </Form.Item>

                    <Form.Item name={[field.name, 'reps']} className="mb-1">
                      <InputNumber min={1} max={999} placeholder="次数" addonAfter="次" style={{ width: 110 }} />
                    </Form.Item>

                    <Form.Item name={[field.name, 'weightKg']} className="mb-1">
                      <InputNumber min={0} max={999} step={2.5} placeholder="重量" addonAfter="kg" style={{ width: 130 }} />
                    </Form.Item>

                    <Form.Item name={[field.name, 'rpe']} className="mb-1">
                      <Tooltip title="主观用力感受 1-10">
                        <span>
                          <Rate count={10} style={{ fontSize: 14 }} />
                        </span>
                      </Tooltip>
                    </Form.Item>

                    <span className="mb-1 text-xs text-gray-400">
                      {rowVolume(records?.[field.name])} kg
                    </span>

                    <Button
                      type="text"
                      danger
                      icon={<DeleteOutlined />}
                      disabled={fields.length <= 1}
                      onClick={() => remove(field.name)}
                      aria-label="删除该行"
                    />
                  </Space>
                ))}

                <div className="flex gap-2">
                  <Tooltip title={fields.length >= MAX_ROWS ? `单次最多 ${MAX_ROWS} 个动作（后端限制）` : ''}>
                    <Button
                      type="dashed"
                      icon={<PlusOutlined />}
                      disabled={fields.length >= MAX_ROWS}
                      onClick={() => add({ sets: 4, reps: 10, weightKg: 20, rpe: 6 })}
                    >
                      添加一组
                    </Button>
                  </Tooltip>
                  <Button type="primary" htmlType="submit" loading={submitting}>
                    {submitting ? '正在保存...' : '提交训练记录'}
                  </Button>
                  <span className="self-center text-xs text-gray-400">
                    {fields.length} / {MAX_ROWS} 个动作
                  </span>
                </div>
              </>
            )}
          </Form.List>
        </div>
      </Form>
    </Card>
  )
}

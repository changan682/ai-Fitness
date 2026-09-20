import { DeleteOutlined, EditOutlined } from '@ant-design/icons'
import { App, Button, Card, Empty, InputNumber, Popconfirm, Rate, Result, Space, Table, Tooltip } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useState } from 'react'
import type { TrainingRecord, TrainingRecordUpdateRequest } from '@/types'

interface Props {
  records: TrainingRecord[]
  isLoading: boolean
  isError: boolean
  onRetry: () => void
  onUpdate: (id: number, data: TrainingRecordUpdateRequest) => Promise<void>
  onDelete: (id: number) => Promise<void>
  /** 正在保存/删除的记录 id（用于按钮 loading） */
  pendingId: number | null
}

/** 行内编辑的草稿值 */
interface Draft {
  sets: number
  reps: number
  weightKg: number
  rpe: number | null
}

/**
 * 今日训练记录表格
 *
 * <h3>为什么不能改动作名</h3>
 * 后端 `TrainingRecordUpdateRequest` 里**没有** `actionName` 字段
 * （规范把它定义为「所有字段可选」的部分更新），所以行内编辑只开放
 * 组数/次数/重量/RPE —— 改动作名只能删掉重录。这里如实限制，
 * 而不是放一个改了没用的输入框。
 */
export default function TodayRecordsTable({
  records,
  isLoading,
  isError,
  onRetry,
  onUpdate,
  onDelete,
  pendingId,
}: Props) {
  const { message } = App.useApp()
  const [editingId, setEditingId] = useState<number | null>(null)
  const [draft, setDraft] = useState<Draft | null>(null)

  const startEdit = (record: TrainingRecord): void => {
    setEditingId(record.id)
    setDraft({
      sets: record.sets,
      reps: record.reps,
      weightKg: record.weightKg,
      rpe: record.rpe,
    })
  }

  const cancelEdit = (): void => {
    setEditingId(null)
    setDraft(null)
  }

  const saveEdit = async (id: number): Promise<void> => {
    if (!draft) return
    await onUpdate(id, {
      sets: draft.sets,
      reps: draft.reps,
      weightKg: draft.weightKg,
      rpe: draft.rpe ?? undefined,
    })
    cancelEdit()
  }

  const handleDelete = async (id: number): Promise<void> => {
    await onDelete(id)
    message.success('记录已删除')
  }

  const columns: ColumnsType<TrainingRecord> = [
    {
      title: '动作名称',
      dataIndex: 'actionName',
      key: 'actionName',
      ellipsis: true,
    },
    {
      title: '组数',
      dataIndex: 'sets',
      key: 'sets',
      width: 90,
      render: (value: number, record) =>
        editingId === record.id && draft ? (
          <InputNumber
            min={1}
            max={99}
            value={draft.sets}
            onChange={(v) => setDraft({ ...draft, sets: Number(v ?? 1) })}
            style={{ width: 70 }}
          />
        ) : (
          value
        ),
    },
    {
      title: '次数',
      dataIndex: 'reps',
      key: 'reps',
      width: 90,
      render: (value: number, record) =>
        editingId === record.id && draft ? (
          <InputNumber
            min={1}
            max={999}
            value={draft.reps}
            onChange={(v) => setDraft({ ...draft, reps: Number(v ?? 1) })}
            style={{ width: 70 }}
          />
        ) : (
          value
        ),
    },
    {
      title: '重量(kg)',
      dataIndex: 'weightKg',
      key: 'weightKg',
      width: 130,
      render: (value: number, record) =>
        editingId === record.id && draft ? (
          <InputNumber
            min={0}
            max={999}
            step={2.5}
            value={draft.weightKg}
            onChange={(v) => setDraft({ ...draft, weightKg: Number(v ?? 0) })}
            style={{ width: 90 }}
          />
        ) : (
          value
        ),
    },
    {
      title: (
        <Tooltip title="容量 = 组数 × 次数 × 重量，由后端计算">
          <span className="cursor-help">容量(kg)</span>
        </Tooltip>
      ),
      dataIndex: 'volume',
      key: 'volume',
      width: 110,
      render: (value: number) => <b>{value}</b>,
    },
    {
      title: 'RPE',
      dataIndex: 'rpe',
      key: 'rpe',
      width: 170,
      render: (value: number | null, record) =>
        editingId === record.id && draft ? (
          <Rate
            count={10}
            value={draft.rpe ?? 0}
            onChange={(v) => setDraft({ ...draft, rpe: v })}
            style={{ fontSize: 14 }}
          />
        ) : value === null ? (
          <span className="text-gray-300">—</span>
        ) : (
          value
        ),
    },
    {
      title: '操作',
      key: 'action',
      width: 150,
      render: (_value, record) =>
        editingId === record.id ? (
          <Space size="small">
            <Button
              type="link"
              size="small"
              loading={pendingId === record.id}
              onClick={() => void saveEdit(record.id)}
            >
              保存
            </Button>
            <Button type="link" size="small" onClick={cancelEdit}>
              取消
            </Button>
          </Space>
        ) : (
          <Space size="small">
            <Tooltip title="只能改组数/次数/重量/RPE（后端不支持改动作名）">
              <Button
                type="link"
                size="small"
                icon={<EditOutlined />}
                disabled={editingId !== null}
                onClick={() => startEdit(record)}
              >
                编辑
              </Button>
            </Tooltip>
            <Popconfirm
              title="确认删除这条训练记录？"
              okText="删除"
              cancelText="取消"
              okButtonProps={{ danger: true }}
              onConfirm={() => void handleDelete(record.id)}
            >
              <Button type="link" size="small" danger icon={<DeleteOutlined />}>
                删除
              </Button>
            </Popconfirm>
          </Space>
        ),
    },
  ]

  return (
    <Card title="今日已记录">
      {isError ? (
        <Result
          status="error"
          title="训练记录加载失败"
          subTitle="请检查后端服务与网络后重试"
          extra={
            <Button type="primary" onClick={onRetry}>
              重新加载
            </Button>
          }
        />
      ) : (
        <Table<TrainingRecord>
          rowKey="id"
          size="middle"
          loading={isLoading}
          columns={columns}
          dataSource={records}
          pagination={false}
          locale={{
            emptyText: <Empty description="今天还没有训练记录，快开始吧 💪" />,
          }}
          scroll={{ x: 800 }}
        />
      )}
    </Card>
  )
}

import { ClearOutlined, SendOutlined } from '@ant-design/icons'
import { Alert, App, Button, Card, Collapse, Empty, Input, Select, Skeleton, Space, Tag } from 'antd'
import { useEffect, useRef, useState } from 'react'
import { ApiError } from '@/api/client'
import aiApi from '@/api/aiApi'
import MarkdownView from '@/components/common/MarkdownView'
import { ErrorCode, KnowledgeCategory, toOptions } from '@/types'
import type { ChatMessage } from '@/types'

/** 快捷问题（规范里给出的三条示例） */
const QUICK_QUESTIONS = [
  '深蹲时膝盖可以超过脚尖吗？',
  '增肌期每天需要多少蛋白质？',
  '训练后肌肉酸痛怎么办？',
]

/**
 * Tab 4 — 健身问答（Milvus RAG）
 *
 * <h3>对话历史为什么只放在 useState</h3>
 * 规范明确要求：不进 Zustand、离开页面不保留（避免敏感对话残留在全局状态里）。
 * 配合 Tabs 的 `destroyOnHidden`，切走再回来就是一段新对话。
 *
 * <h3>引用来源</h3>
 * 后端返回的 `sources[].score` 是**真实 Milvus 余弦相似度**，
 * 因此这里把分数一并展示出来，便于判断检索质量。
 */
export default function ChatTab() {
  const { message, modal } = App.useApp()
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [input, setInput] = useState('')
  const [category, setCategory] = useState<string | undefined>(undefined)
  const [sending, setSending] = useState(false)
  const bottomRef = useRef<HTMLDivElement | null>(null)

  // 每次回答后滚到底部（规范要求）
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' })
  }, [messages])

  const send = async (raw: string): Promise<void> => {
    const question = raw.trim()
    if (!question || sending) return

    const stamp = Date.now()
    const userMsg: ChatMessage = { id: `u-${stamp}`, role: 'user', content: question }
    const pendingId = `a-${stamp}`

    // 用户气泡立即出现，AI 气泡先占位显示骨架
    setMessages((prev) => [
      ...prev,
      userMsg,
      { id: pendingId, role: 'assistant', content: '', pending: true },
    ])
    setInput('')
    setSending(true)

    try {
      const data = await aiApi.chat({ question, category })
      setMessages((prev) =>
        prev.map((m) =>
          m.id === pendingId
            ? {
                id: pendingId,
                role: 'assistant',
                content: data.answer,
                sources: data.sources,
                generatedAt: data.generatedAt,
              }
            : m,
        ),
      )
    } catch (e) {
      // 三类失败要给出不同的话术：
      // - 9003：问题本身不合法（如超长），后端已给出具体原因，直接转述
      // - 6003：Milvus / 知识库不可用
      // - 其它：AI 服务整体不可用
      const errorText =
        e instanceof ApiError && e.code === ErrorCode.PARAM_INVALID
          ? e.msg
          : e instanceof ApiError && e.code === ErrorCode.MILVUS_UNAVAILABLE
            ? '知识库暂时不可用 🛠️，请稍后再试'
            : 'AI 服务暂时不可用 🛠️，请稍后再试'
      setMessages((prev) =>
        prev.map((m) => (m.id === pendingId ? { id: pendingId, role: 'assistant', content: '', errorText } : m)),
      )
    } finally {
      setSending(false)
    }
  }

  const handleClear = (): void => {
    modal.confirm({
      title: '确认清空当前对话？',
      content: '对话记录只存在于当前页面，清空后无法恢复。',
      okText: '清空',
      cancelText: '取消',
      okButtonProps: { danger: true },
      onOk: () => {
        setMessages([])
        message.success('对话已清空')
      },
    })
  }

  return (
    <Card
      title="💬 健身问答（基于知识库检索）"
      extra={
        <Space>
          <Select
            allowClear
            placeholder="分类过滤：全部"
            style={{ width: 160 }}
            options={toOptions(KnowledgeCategory)}
            value={category}
            onChange={setCategory}
            disabled={sending}
          />
          <Button icon={<ClearOutlined />} onClick={handleClear} disabled={messages.length === 0}>
            清空对话
          </Button>
        </Space>
      }
    >
      <div className="mb-4 max-h-[52vh] min-h-[240px] overflow-y-auto rounded bg-gray-50 p-3">
        {messages.length === 0 && (
          <div className="py-6">
            <Empty description="试试问我这些问题" />
            <div className="mt-3 flex flex-wrap justify-center gap-2">
              {QUICK_QUESTIONS.map((q) => (
                <Button key={q} size="small" onClick={() => void send(q)}>
                  {q}
                </Button>
              ))}
            </div>
            <p className="mt-4 text-center text-xs text-gray-400">
              回答来自 Milvus 知识库检索 + 大模型生成，并附引用来源
            </p>
          </div>
        )}

        {messages.map((m) => (
          <div
            key={m.id}
            className={`mb-3 flex ${m.role === 'user' ? 'justify-end' : 'justify-start'}`}
          >
            <div
              className={`max-w-[85%] rounded-lg px-3 py-2 ${
                m.role === 'user' ? 'bg-blue-500 text-white' : 'border border-gray-200 bg-white'
              }`}
            >
              {m.role === 'user' && <div className="whitespace-pre-wrap text-sm">{m.content}</div>}

              {m.role === 'assistant' && m.pending && (
                <div className="w-[420px] max-w-full">
                  <Skeleton active paragraph={{ rows: 3 }} title={false} />
                  <div className="text-xs text-gray-400">正在检索知识库…</div>
                </div>
              )}

              {m.role === 'assistant' && !m.pending && m.errorText && (
                <div className="w-[420px] max-w-full">
                  <Alert type="warning" showIcon message={m.errorText} />
                </div>
              )}

              {m.role === 'assistant' && !m.pending && !m.errorText && (
                <div>
                  <MarkdownView content={m.content} />

                  {m.sources && m.sources.length > 0 && (
                    <div className="mt-2">
                      <div className="mb-1 text-xs text-gray-500">
                        📚 参考来源（{m.sources.length} 条，分数为 Milvus 相似度）
                      </div>
                      <Collapse
                        size="small"
                        items={m.sources.map((s, i) => ({
                          key: String(i),
                          label: (
                            <span className="text-xs">
                              <Tag color="blue">{s.category}</Tag>
                              {s.title}
                              <span className="ml-2 text-gray-400">{s.score.toFixed(4)}</span>
                            </span>
                          ),
                          children: <div className="text-xs text-gray-600">{s.content}</div>,
                        }))}
                      />
                    </div>
                  )}
                </div>
              )}
            </div>
          </div>
        ))}

        <div ref={bottomRef} />
      </div>

      <Space.Compact style={{ width: '100%' }}>
        <Input.TextArea
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder="输入问题…（Enter 发送，Shift+Enter 换行）"
          autoSize={{ minRows: 1, maxRows: 4 }}
          maxLength={500}
          showCount
          disabled={sending}
          onPressEnter={(e) => {
            if (!e.shiftKey) {
              e.preventDefault()
              void send(input)
            }
          }}
        />
        <Button
          type="primary"
          icon={<SendOutlined />}
          loading={sending}
          onClick={() => void send(input)}
          style={{ height: 'auto' }}
        >
          发送
        </Button>
      </Space.Compact>
    </Card>
  )
}

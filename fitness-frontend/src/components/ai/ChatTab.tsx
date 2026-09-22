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
 * <h3>对话历史怎么存的（体验优化批次 C 后）</h3>
 * 界面上的气泡仍然只放 `useState`（规范要求不进全局状态、离开页面不保留），
 * 但**上下文由后端记住**：请求带上 `sessionId`，Java 侧把该会话最近几轮
 * 从 Redis 热层取出来一起送给大模型。因此：
 * - `sessionId` 存 `sessionStorage`：刷新页面还能接着上一段聊；
 * - 关掉标签页即结束（不落 localStorage，避免敏感对话长期留痕）；
 * - 点「新对话」= 换一个新的 sessionId（并顺手让后端清掉旧会话的热层）。
 *
 * <h3>引用来源</h3>
 * 后端返回的 `sources[].score` 口径由 `scoreType` 声明：
 * `cosine` 才是真实 Milvus 余弦相似度，`heuristic` 是内置兜底的合成值。
 */
const SESSION_KEY = 'fitness-ai-chat-session'

export default function ChatTab() {
  const { message, modal } = App.useApp()
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [input, setInput] = useState('')
  const [category, setCategory] = useState<string | undefined>(undefined)
  const [sending, setSending] = useState(false)
  /** 当前会话 id；首轮为空，由后端生成后回填（之后每轮都要带上，否则会"失忆"） */
  const [sessionId, setSessionId] = useState<string | undefined>(
    () => window.sessionStorage.getItem(SESSION_KEY) ?? undefined,
  )
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
      const data = await aiApi.chat({ question, category, sessionId })

      // 后端可能新建了会话：存下来，下一轮才有上下文
      if (data.sessionId && data.sessionId !== sessionId) {
        setSessionId(data.sessionId)
        window.sessionStorage.setItem(SESSION_KEY, data.sessionId)
      }

      setMessages((prev) =>
        prev.map((m) =>
          m.id === pendingId
            ? {
                id: pendingId,
                role: 'assistant',
                content: data.answer,
                sources: data.sources,
                generatedAt: data.generatedAt,
                // 降级标记一起带进气泡：兜底回答必须在界面上可辨认
                dataSource: data.dataSource,
                degraded: data.degraded,
                degradationReason: data.degradationReason,
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

  /**
   * 「新对话」：切断上下文
   * <p>
   * 三件事缺一不可：
   * 1. 清空界面气泡；
   * 2. 丢掉 sessionId（下一轮后端会新建会话）——
   *    **只清界面不换 sessionId 的话，后端还记得上文，"新对话"名不副实**；
   * 3. 通知后端清掉旧会话的 Redis 热层（尽力而为，失败不影响用户）。
   *    长期历史（t_ai_chat_history）刻意保留：换会话是"断开上下文"，
   *    不等于"删掉聊天记录"。
   */
  const handleClear = (): void => {
    modal.confirm({
      title: '开启新对话？',
      content: '当前对话的上下文会被断开（历史记录仍会保留在服务端）。',
      okText: '新对话',
      cancelText: '取消',
      onOk: () => {
        const previous = sessionId
        setMessages([])
        setSessionId(undefined)
        window.sessionStorage.removeItem(SESSION_KEY)
        if (previous) {
          // 不 await：清理失败也不该拦住用户开始新对话
          void aiApi.newChatSession(previous).catch(() => undefined)
        }
        message.success('已开启新对话')
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
            新对话
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
                  {/*
                    降级标注：后端会在这些情况下打标记 ——
                    ① 知识库没覆盖该问题（llm_only：检索到了资料但回答没用，sources 为空）
                    ② 检索失败/无命中（none）、无 LLM Key 本地拼装、内置 18 条兜底（builtin）。
                    不显示的话，「兜底回答」与真实 RAG 回答在界面上完全一样，
                    而兜底来源的相关度其实是启发式合成分数，不是余弦相似度。
                  */}
                  {m.degraded && (
                    <Alert
                      type={m.dataSource === 'builtin' ? 'warning' : 'info'}
                      showIcon
                      className="mb-2"
                      message={
                        m.dataSource === 'builtin'
                          ? '降级回答（内置知识条目）'
                          : m.dataSource === 'llm_only'
                            ? '通用知识回答（未使用知识库）'
                            : '降级回答'
                      }
                      description={m.degradationReason ?? undefined}
                    />
                  )}

                  <MarkdownView content={m.content} />

                  {m.sources && m.sources.length > 0 && (
                    <div className="mt-2">
                      <div className="mb-1 text-xs text-gray-500">
                        📚 参考来源（{m.sources.length} 条，
                        {m.sources[0].scoreType === 'heuristic'
                          ? '相关度为启发式合成分数，非向量相似度'
                          : '分数为 Milvus 余弦相似度'}
                        ）
                      </div>
                      <Collapse
                        size="small"
                        items={m.sources.map((s, i) => ({
                          key: String(i),
                          label: (
                            <span className="text-xs">
                              <Tag color="blue">{s.category}</Tag>
                              {s.title}
                              <span className="ml-2 text-gray-400">
                                {/* 按口径区分展示：合成分数不能伪装成余弦相似度 */}
                                {s.scoreType === 'heuristic'
                                  ? `合成分数 ${s.score.toFixed(2)}`
                                  : s.score.toFixed(4)}
                              </span>
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

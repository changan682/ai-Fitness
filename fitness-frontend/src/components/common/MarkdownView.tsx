import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

interface Props {
  /** Markdown 原文（AI 返回的总结/周计划/问答答案都是 Markdown） */
  content: string
  className?: string
}

/**
 * Markdown 渲染
 *
 * <p>AI 返回的都是 Markdown（标题/列表/加粗/引用/表格），用 `react-markdown` + `remark-gfm`
 * 渲染。样式不做内联，统一由 `src/index.css` 里的 `.markdown-body` 提供，
 * 这样在问答气泡、总结卡片等不同容器里外观一致。
 */
export default function MarkdownView({ content, className }: Props) {
  return (
    <div className={`markdown-body break-words text-sm leading-relaxed ${className ?? ''}`}>
      <ReactMarkdown remarkPlugins={[remarkGfm]}>{content}</ReactMarkdown>
    </div>
  )
}

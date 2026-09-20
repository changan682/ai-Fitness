import { Tabs } from 'antd'
import ChatTab from '@/components/ai/ChatTab'
import PoseTab from '@/components/ai/PoseTab'
import RecommendTab from '@/components/ai/RecommendTab'
import SummaryTab from '@/components/ai/SummaryTab'

/**
 * AI 助手页 —— 四个 Tab：训练总结 / 动作推荐 / 姿态评估 / 健身问答
 *
 * <p>`destroyOnHidden` 是**有意开启**的：规范要求健身问答的对话历史
 * 「切换 Tab 或离开页面不保留」（避免敏感对话残留在内存/全局状态里）。
 * antd 默认会把访问过的 Tab 面板留在 DOM 中（只是隐藏），
 * 那样对话就不会被清掉。代价是切回其它 Tab 时上一次的生成结果也会丢失 ——
 * 对这几个「可随时重新生成」的功能可以接受。
 */
export default function AIAssistantPage() {
  return (
    <Tabs
      defaultActiveKey="summary"
      destroyOnHidden
      items={[
        { key: 'summary', label: '训练总结', children: <SummaryTab /> },
        { key: 'recommend', label: '动作推荐', children: <RecommendTab /> },
        { key: 'pose', label: '姿态评估', children: <PoseTab /> },
        { key: 'chat', label: '💬 健身问答', children: <ChatTab /> },
      ]}
    />
  )
}

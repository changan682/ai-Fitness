import { AI_MULTIMODAL_TIMEOUT_MS, AI_TIMEOUT_MS, http } from './client'
import type {
  AiRecommendRequest,
  AiRecommendResponse,
  AiSummaryRequest,
  AiSummaryResponse,
  BodyConsultResponse,
  ChatRequest,
  ChatResponse,
  KnowledgeHealth,
  PoseEvaluation,
} from '@/types'

/**
 * AI 代理模块 API（5 个接口）
 *
 * <h3>为什么单独放宽超时</h3>
 * 全局超时是 15s，但 AI 接口要在 Java → Python → 大模型之间串行往返：
 * 实测总结约 1.7s、推荐约 2.2s、问答约 3.2s，而**姿态评估走多模态看图**，
 * 单次可达数十秒。若沿用 15s，用户会看到「请求超时」而不是评估结果，
 * 因此这几个接口单独指定更长的超时（规范也要求同步接口不能让 Java 线程无限挂住）。
 */
export const aiApi = {
  /** 7.1 训练智能总结（MVP 核心） */
  generateSummary: (data: AiSummaryRequest = {}): Promise<AiSummaryResponse> =>
    http.post<AiSummaryResponse>('/ai/summary', data, { timeout: AI_TIMEOUT_MS }),

  /** 7.2 动作智能推荐 */
  recommend: (data: AiRecommendRequest): Promise<AiRecommendResponse> =>
    http.post<AiRecommendResponse>('/ai/recommend', data, { timeout: AI_TIMEOUT_MS }),

  /**
   * 7.3 姿态评估（multipart/form-data）
   * <p>
   * 字段名必须是 `image` + `actionName`（后端 `@RequestPart("image")`）。
   * 不手动设置 Content-Type，交给浏览器带 boundary。
   */
  evaluatePose: (image: File, actionName: string): Promise<PoseEvaluation> => {
    const form = new FormData()
    form.append('image', image)
    form.append('actionName', actionName)
    return http.post<PoseEvaluation>('/ai/pose-evaluate', form, {
      timeout: AI_MULTIMODAL_TIMEOUT_MS,
    })
  },

  /**
   * 7.4 知识库 RAG 问答（带引用来源 + 对话记忆）
   * <p>
   * `sessionId` 决定这次提问能不能接上上文：首轮不传（后端生成并返回），
   * 之后每轮都必须原样带回 —— 漏传的后果不是报错，而是"突然失忆"
   * （追问"那做几组？"会答非所问），这类 bug 很难从报错里发现。
   */
  chat: (data: ChatRequest): Promise<ChatResponse> =>
    http.post<ChatResponse>('/ai/chat', data, { timeout: AI_TIMEOUT_MS }),

  /**
   * 7.6 开启新对话：让后端清掉该会话的 Redis 热层
   * <p>
   * 前端换掉 sessionId 本身就已经"断开上下文"了，这里再调一次是为了顺手释放热层内存；
   * 失败不影响使用，调用方无需处理错误。
   */
  newChatSession: (sessionId: string): Promise<void> =>
    http.post<void>('/ai/chat/new-session', null, { params: { sessionId } }),

  /**
   * 7.7 身体状态主动问询（无请求体，后端按当前登录用户自己取身体数据/训练记录）
   * <p>
   * ⚠️ 只在**用户点击**「让 AI 看看我的变化」时才调用：
   * ① 省 token —— 每次保存体测都自动问一次，用户一天记几条就是几次大模型调用；
   * ② 不打扰 —— 用户录完体重后想要的是「记录成功」，不是突然弹出一屏追问。
   * 因此调用方用 useMutation / `enabled: false` 手动触发，**不要挂在 useQuery 上自动 fetch**。
   */
  bodyConsult: (): Promise<BodyConsultResponse> =>
    http.post<BodyConsultResponse>('/ai/body-consult', null, { timeout: AI_TIMEOUT_MS }),

  /** 7.5 Milvus 知识库健康检查 */
  knowledgeHealth: (): Promise<KnowledgeHealth> =>
    http.get<KnowledgeHealth>('/ai/knowledge/health'),
}

export default aiApi

import { AI_MULTIMODAL_TIMEOUT_MS, AI_TIMEOUT_MS, http } from './client'
import type {
  AiRecommendRequest,
  AiRecommendResponse,
  AiSummaryRequest,
  AiSummaryResponse,
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

  /** 7.4 知识库 RAG 问答（带引用来源） */
  chat: (data: ChatRequest): Promise<ChatResponse> =>
    http.post<ChatResponse>('/ai/chat', data, { timeout: AI_TIMEOUT_MS }),

  /** 7.5 Milvus 知识库健康检查 */
  knowledgeHealth: (): Promise<KnowledgeHealth> =>
    http.get<KnowledgeHealth>('/ai/knowledge/health'),
}

export default aiApi

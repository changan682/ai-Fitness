import type { MuscleGroup, PoseAction } from './enums'

/**
 * AI 能力类型（5 个同步接口）
 * <p>
 * 时间字段说明：Java 侧统一把 `LocalDateTime` 序列化成 `yyyy-MM-dd HH:mm:ss`
 * （不是 ISO8601 的 `T` 格式），因此这里全部按字符串处理、用 dayjs 直接解析。
 */

// ==================== 7.1 训练总结 ====================

/** 生成训练总结的请求 */
export interface AiSummaryRequest {
  /** yyyy-MM-dd，不传默认今天 */
  date?: string
}

/** 训练总结响应 */
export interface AiSummaryResponse {
  /** Markdown 文本，用 react-markdown 渲染 */
  summary: string
  /** 是否来自缓存（Redis 或 MySQL 双层缓存命中） */
  cached: boolean
  /** yyyy-MM-dd HH:mm:ss */
  generatedAt: string
}

// ==================== 7.2 动作推荐 ====================

/** 单条推荐动作 */
export interface ActionRecommendation {
  actionName: string
  targetMuscle: string
  focusArea: string | null
  /** 字符串范围，如 "3-4" */
  recommendedSets: string | null
  recommendedReps: string | null
  difficulty: string | null
  notes: string | null
  equipment: string[]
}

/** 动作推荐请求 */
export interface AiRecommendRequest {
  /** 只能是 胸/背/腿/肩/手臂/核心（后端正则校验） */
  targetMuscle: MuscleGroup
  /** 可用器械，至少一项 */
  equipment: string[]
  /** 1-10，默认 5 */
  count?: number
}

/** 动作推荐响应 */
export interface AiRecommendResponse {
  recommendations: ActionRecommendation[]
  /** yyyy-MM-dd HH:mm:ss */
  generatedAt: string
}

// ==================== 7.3 姿态评估 ====================

/** 姿态评估结果 */
export interface PoseEvaluation {
  /** 0-100 */
  score: number
  /** 优秀/良好/一般/需改进 —— 由后端按 score 严格派生，不采信模型自报 */
  scoreLevel: string
  issues: string[]
  suggestions: string[]
  goodPoints: string[]
  /** yyyy-MM-dd HH:mm:ss */
  evaluatedAt: string
}

/** 姿态评估的 multipart 表单字段 */
export interface PoseEvaluateForm {
  /** 原图（≤10MB）；Java 会先压缩到 ≤1MB 再转发 */
  image: File
  /** 只能是 深蹲/卧推/硬拉/推举/划船 */
  actionName: PoseAction
}

// ==================== 7.4 知识库问答（RAG） ====================

/** 引用来源 */
export interface ChatSource {
  category: string
  title: string
  content: string
  /** 余弦相似度（真实 Milvus 检索分数） */
  score: number
}

/** 问答请求 */
export interface ChatRequest {
  /** 最长 500 字 */
  question: string
  /**
   * 分类过滤（可选）
   * <p>
   * ⚠️ Java 侧<b>不做白名单校验</b>，直接透传给 Python；合法值为
   * 动作要领/营养饮食/恢复与伤病/训练计划/补剂科普。传其它值会退化为全库检索。
   */
  category?: string
}

/** 问答响应 */
export interface ChatResponse {
  question: string
  /** Markdown 回答 */
  answer: string
  sources: ChatSource[]
  /** yyyy-MM-dd HH:mm:ss */
  generatedAt: string
}

/** 前端本地的对话气泡（不持久化，离开页面即清空） */
export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  sources?: ChatSource[]
  /** 回答时间（仅 assistant） */
  generatedAt?: string
  /** 正在检索/生成中（显示骨架屏） */
  pending?: boolean
  /** 出错时的提示文案（显示错误卡片） */
  errorText?: string
}

// ==================== 7.5 知识库健康 ====================

/** Milvus 知识库健康状态 */
export interface KnowledgeHealth {
  milvusConnected: boolean
  collectionName: string
  totalDocuments: number
  /** yyyy-MM-dd HH:mm:ss（读自知识库清单的 built_at） */
  lastUpdated: string | null
  /** 如 "IVF_FLAT(COSINE, nlist=16)" */
  indexType: string
  embeddingDim: number
}

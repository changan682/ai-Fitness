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
  /**
   * 结果来源
   * <p>
   * ⚠️ 必须据此标注：`mock_local` 表示这个分数是本地**模拟打分**（由图片哈希派生），
   * 不是真实多模态推理。不标注就等于把编造的数字当成真实评估结果展示给用户。
   */
  dataSource: 'qwen_vl' | 'mock_local' | string
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
  /** 相关度得分；口径由 {@link ChatSource.scoreType} 声明 */
  score: number
  /**
   * 分数口径
   * <p>
   * - `cosine`：真实 Milvus 余弦相似度（0-1，越大越相关）
   * - `heuristic`：内置兜底时的**启发式合成分数**（由关键词命中与二元组重合度算出），
   *   **不是向量相似度**，不同问题之间也不可比
   *
   * 界面必须按口径区分展示 —— 把启发式分数当余弦相似度显示，与编造数据没有区别。
   */
  scoreType: 'cosine' | 'heuristic' | string
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
  /**
   * 会话 id（可选）—— 对话记忆的钥匙
   * <p>
   * 不传 / 非法格式：后端当作新会话并生成一个返回；
   * 之后每轮都要原样带上，否则后端不知道上文（追问"那做几组"必然答非所问）。
   * 归属由服务端按「当前登录用户 + sessionId」把关，传别人的 id 也只得到自己的空会话。
   */
  sessionId?: string
}

/** 问答响应 */
export interface ChatResponse {
  question: string
  /** Markdown 回答 */
  answer: string
  sources: ChatSource[]
  /**
   * 来源库与「这轮回答到底有没有用知识库」
   * <p>
   * - `milvus`：回答确实基于 200 条真实知识库检索结果
   * - `llm_only`：检索到了资料，但判定它与问题无关 → 回答改用大模型通用知识，
   *   `sources` 为空。**这是「知识库没覆盖该问题」的诚实表达**，界面必须提示
   * - `none`：压根没检索到来源（检索失败/无命中），回答同样来自通用知识
   * - `builtin`：内置 18 条兜底（此时 `sources[].scoreType` 必为 `heuristic`）
   */
  dataSource: 'milvus' | 'llm_only' | 'none' | 'builtin' | string
  /** 是否走了降级路径（知识库没覆盖、检索失败/无命中、无 LLM Key 本地拼装、内置兜底） */
  degraded: boolean
  /** 降级原因（中文说明），未降级时为 null */
  degradationReason: string | null
  /**
   * 本次问答所属的会话 id —— 前端必须把它带回下一次提问，对话才连得上
   * <p>
   * 首轮请求不传，后端生成并返回；此后每次都原样带上。
   */
  sessionId: string
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
  /** 来源库（milvus/llm_only/none/builtin）—— 用于标注「通用知识回答」与「内置条目」 */
  dataSource?: string
  /** 是否为降级回答 */
  degraded?: boolean
  /** 降级原因 —— 直接展示给用户，说明这份回答打了什么折扣 */
  degradationReason?: string | null
}

// ==================== 7.7 身体状态主动问询 ====================

/**
 * 一条「主动追问」
 * <p>
 * `why` 不是装饰：它说明「为什么要问这个」，用户才知道值不值得回答。
 * 缺了它，这三条问题看起来就像随机生成的问卷。
 */
export interface BodyConsultQuestion {
  /** 问题 id（后端生成，前端只用于 key/埋点，不要当业务标识用） */
  id: string
  /** 问题文本；前端会把 `questions[0].text` 直接丢进问答的 send() */
  text: string
  /** 问这个问题的理由（灰色小字展示） */
  why: string
}

/** 一条建议 */
export interface BodyConsultSuggestion {
  title: string
  detail: string
}

/**
 * 一条风险提示
 * <p>
 * `level` 是**语义级别**，不是颜色：由前端决定怎么画（info→蓝色提示、warn→黄色警告、high→红色错误），
 * 后端不该知道界面用什么组件。
 */
export interface BodyConsultRiskFlag {
  level: 'info' | 'warn' | 'high'
  text: string
}

/**
 * 身体状态主动问询响应（POST /ai/body-consult，无请求体）
 * <p>
 * ⚠️ 与问答一样有「诚实标记」的硬要求：`dataSource === 'rule_based'` 表示**这次回答完全由
 * 后端规则拼出来、根本没调用大模型**。界面必须显式标注，否则用户会把规则模板当成 AI 的分析结论。
 */
export interface BodyConsultResponse {
  /** 2-3 句整体判断 */
  assessment: string
  /** 趋势要点（含具体数值，如「近 7 天体重下降 0.8kg」） */
  trendSummary: string
  /** 最多 3 条主动追问 */
  questions: BodyConsultQuestion[]
  /** 最多 3 条建议 */
  suggestions: BodyConsultSuggestion[]
  /** 风险提示（可为空数组，表示没有发现需要提醒的点） */
  riskFlags: BodyConsultRiskFlag[]
  /**
   * 结果来源
   * <p>
   * - `llm`：真实经过大模型
   * - `rule_based`：**未使用大模型**，由后端规则兜底拼装 —— 界面必须标注
   */
  dataSource: 'llm' | 'rule_based' | string
  /** 是否走了降级路径 */
  degraded: boolean
  /** 降级原因（中文说明），未降级时为 null */
  degradationReason: string | null
  /** yyyy-MM-dd HH:mm:ss */
  generatedAt: string
  /** 是否命中服务端缓存（命中说明内容可能不是刚生成的，展示时可提示） */
  cached: boolean
}

// ==================== 7.5 知识库健康 ====================

/**
 * Milvus 知识库健康状态
 * <p>
 * ⚠️ 后端这几个字段都是包装类型（可为 null）。更要注意的是：
 * **Milvus 不可用时后端返回 `code=6003` 且整个 `data` 为 null**
 * （`GlobalExceptionHandler` 对业务异常一律把 data 置空），
 * 而不是像规范示例那样返回 `data:{milvusConnected:false}`。
 * 因此页面必须按 `code === 6003` 判断「知识库不可用」，不能靠读 `data.milvusConnected`。
 */
export interface KnowledgeHealth {
  milvusConnected: boolean | null
  collectionName: string | null
  totalDocuments: number | null
  /** yyyy-MM-dd HH:mm:ss（读自知识库清单的 built_at） */
  lastUpdated: string | null
  /** 如 "IVF_FLAT(COSINE, nlist=16)" */
  indexType: string | null
  embeddingDim: number | null
}

/**
 * 统计与周计划类型
 * <p>
 * 后端这几个接口返回的是 `Map<String,Object>`（不是 DTO），字段名由 Service 里
 * `result.put("...")` 决定，改动后端时这里必须同步。
 */

/** Dashboard 统计卡片数据 */
export interface DashboardStats {
  /** yyyy-MM-dd */
  date: string
  /** 今日训练动作数 */
  todayActionCount: number
  /** 今日总容量(kg) */
  todayTotalVolume: number
  /**
   * 连续训练天数
   * <p>
   * 后端已做「今日未练不算断」处理，因此今天还没训练时不会归零。
   */
  consecutiveTrainingDays: number
  /** 本周训练天数 */
  weeklyTrainingDays: number
  /** 最新体重(kg) —— 取 t_body_metric 的最新一条，不是档案里的初始体重 */
  latestWeight: number | null
  /** 近 7 日体重变化；样本不足为 null */
  weightChange7d: number | null
  /** 近 7 日滑动平均体重；样本 < 3 条时为 null */
  weightAvg7d: number | null
}

/** 本周训练量最高的动作 */
export interface WeeklyTopAction {
  actionName: string
  totalVolume: number
  count: number
}

/** 本周体重变化 */
export interface WeeklyBodyMetrics {
  startWeight: number | null
  endWeight: number | null
  weightChange: number | null
}

/** 本周统计（纯 Java 计算，周日 20:00 定时任务也会写入 t_weekly_plan.week_summary） */
export interface WeeklyStats {
  /** yyyy-MM-dd */
  weekStart: string
  /** yyyy-MM-dd */
  weekEnd: string
  trainingDays: number
  totalActions: number
  totalVolume: number
  avgRpe: number | null
  topActions: WeeklyTopAction[]
  bodyMetrics: WeeklyBodyMetrics
  avgCalories: number | null
}

/**
 * 最新周计划
 * <p>
 * 字段可空性与后端一致（`WeeklyPlanResponse` 全是包装类型）：
 * - 周日 20:00 的统计任务只写 `weekSummary`，此时 `suggestionText` 为**空串**（不是 null）
 * - AI 回调写入前若行是统计任务临时建的，`weekStart`/`createdAt` 一定有值；
 *   但后端 DTO 允许 null，因此这里如实标注，避免页面 `dayjs(null)` 拿到 Invalid Date 还以为是解析问题
 * - 完全没有该周记录时接口返回 `data: null`（不是错误码），页面应展示 Empty
 */
export interface WeeklyPlan {
  id: number
  /** yyyy-MM-dd（周一） */
  weekStart: string | null
  /** AI 生成的 Markdown 建议；未生成时为空串 */
  suggestionText: string | null
  /** 本周数据摘要（JSON **字符串**，不是对象） */
  weekSummary: string | null
  isRead: boolean
  /** yyyy-MM-dd HH:mm:ss */
  createdAt: string | null
}

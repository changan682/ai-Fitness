/** 模板里的一个动作明细 */
export interface TemplateExercise {
  /** 周期内第几天（1-7） */
  dayOfCycle: number
  /** 训练日标签，如「胸+三头」 */
  dayLabel: string
  actionName: string
  targetMuscle: string
  /** 推荐组数范围（字符串，如 "3-4"） */
  recommendedSets: string
  /** 推荐次数范围（字符串，如 "8-12"） */
  recommendedReps: string
  notes: string | null
}

/** 训练计划模板（内置三分化/推拉腿/五分化等） */
export interface WorkoutTemplate {
  id: number
  templateName: string
  description: string | null
  /** 分化方式，如「三分化」 */
  splitType: string
  /** 适用水平：新手/进阶/老手 */
  targetLevel: string
  exercises: TemplateExercise[]
}

/** 模板列表响应 */
export interface WorkoutTemplateListResponse {
  list: WorkoutTemplate[]
}

/** 套用模板生成本周安排 */
export interface ApplyScheduleRequest {
  templateId: number
  /** yyyy-MM-dd，本周起始（周一） */
  startDate: string
}

/** 某一天的安排（actions 已是可读文案，如「杠铃卧推 3-4组×8-12次」） */
export interface ScheduleDay {
  /** yyyy-MM-dd */
  scheduleDate: string
  dayLabel: string
  actions: string[]
}

/** 套用模板的响应 */
export interface ApplyScheduleResponse {
  scheduleId: number
  /** yyyy-MM-dd */
  weekStart: string
  days: ScheduleDay[]
}

/**
 * 用户训练安排（实体 `t_user_workout_schedule` 的形状）
 * <p>
 * 目前没有直接查询它的接口（仅「套用模板」会写入），
 * 这里保留类型定义以便后续扩展「我的安排」页面时不必再翻后端。
 */
export interface WorkoutSchedule {
  id: number
  userId: number
  templateId: number | null
  /** yyyy-MM-dd */
  scheduleDate: string
  actionName: string
  targetMuscle: string
  targetSets: number | null
  targetReps: number | null
  /** 0-未完成 1-已完成 */
  isCompleted: number
  /** yyyy-MM-dd HH:mm:ss */
  completedAt: string | null
}

/**
 * 类型统一导出
 * <p>
 * 页面只从 `@/types` 导入，不直接深入子文件 —— 这样将来拆分/合并类型文件时
 * 不用改一堆 import。
 * <p>
 * 注意用 `export type` 导出纯类型（`isolatedModules` 下必须区分类型与值），
 * 而枚举是**运行时值**，必须用普通 `export`。
 */

// ==================== 值（枚举是运行时对象） ====================
export {
  Gender,
  TrainingGoal,
  TrainingLevel,
  MealType,
  MuscleGroup,
  FoodCategory,
  PoseAction,
  KnowledgeCategory,
  toOptions,
} from './enums'
export { ErrorCode, SUCCESS_CODE } from './common'

// ==================== 纯类型 ====================
export type { ApiResponse, PaginatedData, PageParams } from './common'

export type {
  UserProfile,
  RegisterRequest,
  RegisterResponse,
  LoginRequest,
  LoginResponse,
  UserBrief,
  UpdateProfileRequest,
  ChangePasswordRequest,
  RefreshTokenResponse,
} from './user'

export type {
  TrainingRecord,
  TrainingRecordRequest,
  TrainingRecordUpdateRequest,
  TrainingBatchItem,
  TrainingBatchRequest,
  TrainingBatchResponse,
  TrainingQueryParams,
  ActionHistoryResponse,
  TrainingUpdateResponse,
} from './training'

export type {
  BodyMetric,
  BodyMetricRequest,
  BodyMetricUpdateRequest,
  BodyMetricTrendPoint,
  BodyMetricTrendResponse,
} from './bodyMetric'

export type {
  DietRecord,
  DietRecordRequest,
  DietRecordListResponse,
  MealSummary,
  DietDailyStats,
  DailyCalorieStat,
  DietStatsDaily,
} from './diet'

export type {
  FoodItem,
  FoodLibraryQuery,
  FoodLibraryResponse,
  ReloadCacheResponse,
} from './foodLibrary'

export type {
  TemplateExercise,
  WorkoutTemplate,
  WorkoutTemplateListResponse,
  ApplyScheduleRequest,
  ScheduleDay,
  ApplyScheduleResponse,
  WorkoutSchedule,
} from './workout'

export type {
  AiSummaryRequest,
  AiSummaryResponse,
  ActionRecommendation,
  AiRecommendRequest,
  AiRecommendResponse,
  PoseEvaluation,
  PoseEvaluateForm,
  ChatSource,
  ChatRequest,
  ChatResponse,
  ChatMessage,
  KnowledgeHealth,
} from './ai'

export type {
  DashboardStats,
  WeeklyTopAction,
  WeeklyBodyMetrics,
  WeeklyStats,
  WeeklyPlan,
} from './stats'

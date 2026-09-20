/**
 * 业务枚举
 * <p>
 * 值全部是**后端约定的中文字面量/数字**：这些值会直接发往后端做正则校验
 * （如 `trainingGoal` 只接受 增肌/减脂/保持），因此不能改成英文标识符，
 * 也不能依赖 label 与 value 分离的写法 —— value 必须就是中文本身。
 */

/** 性别：0-未设置 1-男 2-女（后端 TINYINT） */
export enum Gender {
  UNSET = 0,
  MALE = 1,
  FEMALE = 2,
}

/** 训练目标 */
export enum TrainingGoal {
  BULK = '增肌',
  CUT = '减脂',
  MAINTAIN = '保持',
}

/** 训练年限 */
export enum TrainingLevel {
  BEGINNER = '新手',
  INTERMEDIATE = '进阶',
  ADVANCED = '老手',
}

/** 餐次 */
export enum MealType {
  BREAKFAST = '早餐',
  LUNCH = '午餐',
  DINNER = '晚餐',
  SNACK = '加餐',
}

/** 目标肌群 —— 与 Java 侧 AI 推荐接口的可选值一致（注意：不含「全身」） */
export enum MuscleGroup {
  CHEST = '胸',
  BACK = '背',
  LEGS = '腿',
  SHOULDERS = '肩',
  ARMS = '手臂',
  CORE = '核心',
}

/** 食物分类 */
export enum FoodCategory {
  STAPLE = '主食',
  MEAT = '肉类',
  VEGETABLE = '蔬菜',
  FRUIT = '水果',
  DAIRY = '乳制品',
  SNACK = '零食',
  BEVERAGE = '饮品',
}

/** 姿态评估支持的动作 —— 与 Java `AiProxyService.SUPPORTED_POSE_ACTIONS` 严格一致 */
export enum PoseAction {
  SQUAT = '深蹲',
  BENCH_PRESS = '卧推',
  DEADLIFT = '硬拉',
  OVERHEAD_PRESS = '推举',
  ROW = '划船',
}

/** 知识库分类 —— RAG 问答的 category 过滤项（比 FoodCategory 多「补剂科普」少「零食」等） */
export enum KnowledgeCategory {
  TECHNIQUE = '动作要领',
  NUTRITION = '营养饮食',
  RECOVERY = '恢复与伤病',
  PLAN = '训练计划',
  SUPPLEMENT = '补剂科普',
}

/** 生成枚举的下拉选项（Ant Design Select 直接用） */
export const toOptions = (e: Record<string, string>): { label: string; value: string }[] =>
  Object.values(e).map((v) => ({ label: v, value: v }))

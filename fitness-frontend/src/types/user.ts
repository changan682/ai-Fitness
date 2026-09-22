import type { Gender, TrainingGoal, TrainingLevel } from './enums'

/** 用户档案 —— 对应 Java `UserProfileResponse`（手机号已脱敏） */
export interface UserProfile {
  id: number
  nickname: string
  /** 0-未设置 1-男 2-女 */
  gender: number | null
  /** yyyy-MM-dd */
  birthDate: string | null
  /** 身高(cm)。注意：这是**注册时填的初始身高**，不随时间变化 */
  height: number | null
  /**
   * 体重(kg)
   * <p>
   * ⚠️ 这是注册时的初始体重，只在用户手动改档案时更新；
   * 日常体重走 `t_body_metric`。Dashboard/Trends 的「最新体重」一律取体测记录，
   * 两者独立维护、不做自动同步（规范明确要求）。
   */
  weight: number | null
  trainingGoal: TrainingGoal | string | null
  trainingLevel: TrainingLevel | string | null
  /**
   * 头像访问路径，如 `/api/v1/user/avatar/12?v=1789999999999`
   * <p>
   * 为空表示未设置头像 → 界面回退到默认图标。该路径**无需鉴权**即可访问
   * （已加入 JWT 白名单），因此可以直接放进 `<img src>`：浏览器给图片请求
   * 不会带 `Authorization` 头，若要求鉴权就会表现为"头像永远不显示"。
   * 末尾的 `v` 是版本号，换头像后会变，用来破浏览器缓存。
   */
  avatarUrl: string | null
  /** 伤病记录（后端以 JSON 数组字符串存储，接口层已转成数组） */
  injuryRecord: string[]
  /** 脱敏手机号，如 138****8000 */
  phone: string
  /** yyyy-MM-dd HH:mm:ss */
  createdAt: string
}

/** 注册请求 */
export interface RegisterRequest {
  /** 2-20 字符 */
  nickname: string
  /** 11 位手机号 */
  phone: string
  /** 8-32 位，须含大小写字母与数字 */
  password: string
  gender?: Gender
  /** yyyy-MM-dd */
  birthDate?: string
  /** 50-250 */
  height?: number
  /** 30-300 */
  weight?: number
  trainingGoal?: TrainingGoal
  trainingLevel?: TrainingLevel
}

/** 注册响应（只回 id/昵称/脱敏手机号，不回凭证） */
export interface RegisterResponse {
  id: number
  nickname: string
  phone: string
}

/** 登录请求 */
export interface LoginRequest {
  phone: string
  password: string
}

/** 登录响应里的精简用户信息 */
export interface UserBrief {
  id: number
  nickname: string
  gender: number | null
  trainingGoal: string | null
  /** 头像访问路径（可空）；顶栏直接用它渲染，省掉一次档案请求 */
  avatarUrl?: string | null
}

/** 头像上传响应 */
export interface AvatarUploadResponse {
  /** 形如 `/api/v1/user/avatar/12?v=1789999999999`（已带破缓存的版本号） */
  avatarUrl: string
}

/** 登录响应 */
export interface LoginResponse {
  token: string
  /** yyyy-MM-dd HH:mm:ss */
  expiresAt: string
  user: UserBrief
}

/** 修改档案请求 —— 所有字段可选，传了就更新 */
export interface UpdateProfileRequest {
  nickname?: string
  gender?: Gender
  birthDate?: string
  height?: number
  weight?: number
  trainingGoal?: TrainingGoal
  trainingLevel?: TrainingLevel
  injuryRecord?: string[]
}

/** 修改密码请求 */
export interface ChangePasswordRequest {
  oldPassword: string
  newPassword: string
}

/** 刷新 Token 响应 */
export interface RefreshTokenResponse {
  token: string
  /** yyyy-MM-dd HH:mm:ss */
  expiresAt: string
}

import { http } from './client'
import type {
  ChangePasswordRequest,
  LoginRequest,
  LoginResponse,
  RefreshTokenResponse,
  RegisterRequest,
  RegisterResponse,
  UpdateProfileRequest,
  UserProfile,
} from '@/types'

/**
 * 用户模块 API（7 个接口）
 * <p>
 * 全部路径以 `/v1/user/*` 开头，axios 实例的 baseURL 已设为 `/api`，
 * 因此实际请求是 `/api/v1/user/*`（由 Vite proxy 转发到 Java 8080）。
 */
export const userApi = {
  /** 1.1 注册 */
  register: (data: RegisterRequest): Promise<RegisterResponse> =>
    http.post<RegisterResponse>('/v1/user/register', data),

  /** 1.2 登录 */
  login: (data: LoginRequest): Promise<LoginResponse> =>
    http.post<LoginResponse>('/v1/user/login', data),

  /** 1.3 查询个人档案 */
  getProfile: (): Promise<UserProfile> => http.get<UserProfile>('/v1/user/profile'),

  /** 1.4 修改个人档案（传了就更新） */
  updateProfile: (data: UpdateProfileRequest): Promise<void> =>
    http.put<void>('/v1/user/profile', data),

  /** 1.5 修改密码 —— 成功后该用户全部旧 Token 失效，必须重新登录 */
  changePassword: (data: ChangePasswordRequest): Promise<void> =>
    http.put<void>('/v1/user/password', data),

  /** 1.6 登出（当前 Token 进黑名单） */
  logout: (): Promise<void> => http.post<void>('/v1/user/logout'),

  /** 1.7 刷新 Token（仅当剩余有效期 < 24h 才允许，每个 Token 只能刷一次） */
  refresh: (): Promise<RefreshTokenResponse> =>
    http.post<RefreshTokenResponse>('/v1/user/refresh'),
}

export default userApi

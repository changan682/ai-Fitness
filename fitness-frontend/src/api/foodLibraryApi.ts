import { http } from './client'
import type { FoodLibraryQuery, FoodLibraryResponse, ReloadCacheResponse } from '@/types'

/**
 * 食物热量库 API
 * <p>
 * `keyword` 与 `category` **可以同时生效**（后端已修掉早期的 if/else 短路问题）。
 */
export const foodLibraryApi = {
  /** 5.1 查询食物列表（返回 {list, categories}） */
  query: (params: FoodLibraryQuery = {}): Promise<FoodLibraryResponse> =>
    http.get<FoodLibraryResponse>('/v1/food-library', { params }),

  /** 查询所有分类（已按固定顺序排好） */
  getCategories: (): Promise<string[]> => http.get<string[]>('/v1/food-library/categories'),

  /**
   * 重建食物库缓存（管理用）
   * <p>
   * 返回实际加载条数；查询失败时接口会返回错误码而不是假的 success。
   */
  reloadCache: (): Promise<ReloadCacheResponse> =>
    http.post<ReloadCacheResponse>('/v1/food-library/reload-cache'),
}

export default foodLibraryApi

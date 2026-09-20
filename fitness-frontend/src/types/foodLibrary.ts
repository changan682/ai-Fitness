import type { FoodCategory } from './enums'

/**
 * 食物热量库
 * <p>
 * 库由 `sql/init.sql` 播种 30 条；饮食记录只能引用库中已存在的食物名（精确匹配）。
 */
export interface FoodItem {
  id: number
  foodName: string
  category: FoodCategory | string
  /** 每 100g 热量(kcal) */
  caloriesPer100g: number
  proteinPer100g: number | null
  fatPer100g: number | null
  carbsPer100g: number | null
  /** yyyy-MM-dd HH:mm:ss */
  createdAt: string
  updatedAt: string
}

/** 食物库查询参数（keyword 与 category 可同时生效） */
export interface FoodLibraryQuery {
  /** 食物名模糊匹配 */
  keyword?: string
  category?: string
}

/** 食物库列表响应 */
export interface FoodLibraryResponse {
  list: FoodItem[]
  /** 全库分类（后端已按固定顺序 主食/肉类/蔬菜/水果/乳制品/零食/饮品 排好） */
  categories: string[]
}

/** 重建缓存响应（返回实际加载条数，失败时接口会返回错误码而不是假成功） */
export interface ReloadCacheResponse {
  loaded: number
}

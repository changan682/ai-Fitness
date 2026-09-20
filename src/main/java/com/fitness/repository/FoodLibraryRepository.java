package com.fitness.repository;

import com.fitness.entity.FoodLibrary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FoodLibraryRepository extends JpaRepository<FoodLibrary, Long> {

    /**
     * 按食物名称精确查询
     * <p>
     * 饮食记录录入时必须用精确匹配判断「食物是否在热量库中」：
     * 模糊匹配会让「鸡」命中「鸡蛋(煮)」并取第一条，既误判存在性又把热量算错（规范 4.1 要求 4001）。
     */
    Optional<FoodLibrary> findByFoodName(String foodName);

    /** 按食物名称模糊查询 */
    @Query("SELECT f FROM FoodLibrary f WHERE f.foodName LIKE %:keyword% " +
            "ORDER BY f.category, f.foodName")
    List<FoodLibrary> findByFoodNameContaining(@Param("keyword") String keyword);

    /** 按分类查询 */
    List<FoodLibrary> findByCategoryOrderByFoodNameAsc(String category);

    /** 关键词 + 分类组合查询（规范 5.1 示例即 ?keyword=鸡&category=肉类） */
    @Query("SELECT f FROM FoodLibrary f WHERE f.foodName LIKE %:keyword% AND f.category = :category " +
            "ORDER BY f.foodName")
    List<FoodLibrary> findByFoodNameContainingAndCategory(@Param("keyword") String keyword,
                                                          @Param("category") String category);

    /** 查询所有分类 */
    @Query("SELECT DISTINCT f.category FROM FoodLibrary f ORDER BY f.category")
    List<String> findAllCategories();
}

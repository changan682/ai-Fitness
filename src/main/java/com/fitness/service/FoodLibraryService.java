package com.fitness.service;

import com.fitness.cache.CacheKeys;
import com.fitness.cache.RedisCacheService;
import com.fitness.entity.FoodLibrary;
import com.fitness.exception.BusinessException;
import com.fitness.exception.ErrorCode;
import com.fitness.repository.FoodLibraryRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 食物热量库服务 — 查询 + 启动预热到 Redis（规范 2.6）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FoodLibraryService {

    /**
     * 食物库分类的固定展示顺序
     * <p>
     * 规范 5.1 的响应示例给定了顺序（主食/肉类/蔬菜/水果/乳制品/零食/饮品），
     * 而 DB 里按字典序取会是「乳制品/主食/…」，前端筛选器顺序会与文档不一致。
     */
    private static final List<String> CATEGORY_ORDER = List.of(
            "主食", "肉类", "蔬菜", "水果", "乳制品", "零食", "饮品");

    private final FoodLibraryRepository foodLibraryRepository;
    private final RedisCacheService redisCacheService;

    /**
     * 应用启动时预加载食物库到 Redis（Hash：field=食物名, value=每100g热量）
     * <p>
     * 预热失败不阻断启动：数据库/Redis 暂时不可用时，接口仍可在首次访问时回源，
     * 避免「中间件抖动导致整个应用起不来」。
     * <p>
     * ⚠️ 这份「宽容」<b>只适用于启动路径</b>。管理员显式调用重建缓存时必须如实报错，
     * 因此 {@link #reloadCache()} 走的是会抛异常的 {@link #loadFoodsIntoCache()}，
     * 而不是复用本方法 —— 否则接口会返回 success 而实际一条都没加载：
     * 食物库表列名与 DDL 不一致时（曾发生：实体的 {@code calories_per100g}
     * vs DDL 的 {@code calories_per_100g}），启动日志只有一条 WARN，
     * 而 {@code POST /reload-cache} 仍然报成功，故障被彻底掩盖。
     */
    @PostConstruct
    public void warmUpCache() {
        try {
            loadFoodsIntoCache();
        } catch (Exception e) {
            log.warn("食物库缓存预热失败（首次查询将回源MySQL）: {}", e.getMessage());
        }
    }

    /**
     * 把食物库真正读进 Redis
     *
     * @return 成功加载的食物条数
     * @throws RuntimeException 查询数据库失败（表结构/连接问题）时原样抛出，由调用方决定怎么处理
     */
    private int loadFoodsIntoCache() {
        List<FoodLibrary> allFoods = foodLibraryRepository.findAll();
        if (allFoods.isEmpty()) {
            log.warn("食物库为空，跳过缓存预热（请先执行 sql/init.sql 初始化数据）");
            return 0;
        }
        Map<String, String> foodMap = new LinkedHashMap<>();
        for (FoodLibrary f : allFoods) {
            foodMap.put(f.getFoodName(), f.getCaloriesPer100g().toPlainString());
        }
        redisCacheService.hSetAll(CacheKeys.FOOD_LIBRARY_ALL, foodMap);
        redisCacheService.expireWithJitter(CacheKeys.FOOD_LIBRARY_ALL, CacheKeys.FOOD_LIBRARY_TTL_SECONDS);
        log.info("食物库缓存预热完成: {} 种食物已加载到Redis", allFoods.size());
        return allFoods.size();
    }

    /**
     * 精确查询食物（存在性校验 + 热量取值）
     * <p>
     * 优先读 Redis Hash；未命中回源 MySQL 并回写；
     * 确实不存在时写入空值标记（防穿透），返回 null 由调用方抛 4001。
     */
    public FoodLibrary getFoodExactly(String foodName) {
        if (foodName == null || foodName.isBlank()) {
            return null;
        }

        // 1. 先查 Redis：hash field 存在即命中（含空值标记）
        Object cached = redisCacheService.hGet(CacheKeys.FOOD_LIBRARY_ALL, foodName);
        if (cached != null) {
            if (redisCacheService.isNullMarker(cached)) {
                return null;
            }
            // 缓存里只有热量，其余营养字段仍需回表补齐
            return foodLibraryRepository.findByFoodName(foodName).orElse(null);
        }

        // 2. 回源 MySQL
        FoodLibrary food = foodLibraryRepository.findByFoodName(foodName).orElse(null);

        // 3. 回写缓存或空值标记
        if (food == null) {
            redisCacheService.hSetNullField(CacheKeys.FOOD_LIBRARY_ALL, foodName,
                    RedisCacheService.NULL_MARKER_TTL_SECONDS);
            return null;
        }
        redisCacheService.hSet(CacheKeys.FOOD_LIBRARY_ALL, food.getFoodName(),
                food.getCaloriesPer100g().toPlainString());
        return food;
    }

    /** 查询食物每100g热量（精确匹配），不存在返回 null */
    public BigDecimal getCaloriesPer100g(String foodName) {
        FoodLibrary food = getFoodExactly(foodName);
        return food == null ? null : food.getCaloriesPer100g();
    }

    /**
     * 查询食物列表 — 规范 5.1
     * keyword 与 category 可同时生效（此前实现按 if/else 短路，传两个参数时 keyword 被丢弃）。
     */
    public List<FoodLibrary> searchFoods(String keyword, String category) {
        boolean hasKeyword = keyword != null && !keyword.isBlank();
        boolean hasCategory = category != null && !category.isBlank();

        if (hasKeyword && hasCategory) {
            return foodLibraryRepository.findByFoodNameContainingAndCategory(keyword, category);
        }
        if (hasCategory) {
            return foodLibraryRepository.findByCategoryOrderByFoodNameAsc(category);
        }
        if (hasKeyword) {
            return foodLibraryRepository.findByFoodNameContaining(keyword);
        }
        return foodLibraryRepository.findAll();
    }

    /** 查询所有分类（按规范示例的固定顺序输出） */
    public List<String> getAllCategories() {
        List<String> actual = foodLibraryRepository.findAllCategories();
        return CATEGORY_ORDER.stream()
                .filter(actual::contains)
                .toList();
    }

    /**
     * 重新加载缓存（管理员显式触发的接口）
     * <p>
     * 与启动预热<b>刻意不同</b>：这里必须如实抛出异常。调用方是主动要求重建缓存的人，
     * 若查询食物库失败（例如列名与 {@code sql/init.sql} 不一致）却返回 success，
     * 会把一个「食物库/饮食模块全挂」的故障伪装成「缓存已刷新」。
     *
     * @return 成功加载的食物条数（前端可直接看到实际加载了多少）
     * @throws BusinessException 查询失败时抛 9999，并在 msg 里点明要检查什么
     */
    public int reloadCache() {
        redisCacheService.delete(CacheKeys.FOOD_LIBRARY_ALL);
        try {
            return loadFoodsIntoCache();
        } catch (Exception e) {
            log.error("食物库缓存重建失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "食物库缓存重建失败：查询食物库出错，请检查数据库连接以及表结构是否与 sql/init.sql 一致");
        }
    }
}

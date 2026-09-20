package com.fitness.cache;

import com.fitness.service.WorkoutPlanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 缓存预热 — 应用启动时预加载食物库和训练模板到Redis
 * <p>
 * 规范要求（提示词 2.6/2.7）：食物库与训练模板均「启动预加载 + 长 TTL」，
 * 其中食物库由 FoodLibraryService#warmUpCache 在 @PostConstruct 阶段完成，
 * 训练模板在此处 ApplicationRunner 阶段预热（此时全部 Bean 已就绪）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheWarmUpRunner implements ApplicationRunner {

    private final RedisCacheService redisCacheService;
    private final WorkoutPlanService workoutPlanService;

    @Override
    public void run(ApplicationArguments args) {
        log.info("=== 缓存预热开始 ===");

        // 1. 食物库：已通过 FoodLibraryService @PostConstruct 预热。
        //    这里只读 Redis Hash 的 field 数量，不再执行 MySQL 全表查询（启动期不做无谓的 DB 开销）。
        try {
            long foodCount = redisCacheService.hSize(CacheKeys.FOOD_LIBRARY_ALL);
            log.info("食物库缓存状态: {} 种食物", foodCount);
        } catch (Exception e) {
            log.warn("食物库缓存检查失败: {}", e.getMessage());
        }

        // 2. 训练计划模板：调用 Service 查询即完成 Cache-Aside 回写（workout:template:all）
        try {
            int templateCount = workoutPlanService.listTemplates().size();
            log.info("训练模板缓存预热完成: {} 个模板已加载到Redis", templateCount);
        } catch (Exception e) {
            // 预热失败不影响应用启动，首次访问接口时会自动回源MySQL
            log.warn("训练模板缓存预热失败（首次访问将回源MySQL）: {}", e.getMessage());
        }

        log.info("=== 缓存预热完成 ===");
    }
}

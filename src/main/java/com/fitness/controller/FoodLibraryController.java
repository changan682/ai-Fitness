package com.fitness.controller;

import com.fitness.common.Result;
import com.fitness.entity.FoodLibrary;
import com.fitness.service.FoodLibraryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 食物热量库模块 Controller — /api/v1/food-library/*
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/food-library")
@RequiredArgsConstructor
public class FoodLibraryController {

    private final FoodLibraryService foodLibraryService;

    /** 查询食物列表（支持关键词搜索 + 分类筛选），返回 {list, categories} 与提示词 5.1 对齐 */
    @GetMapping
    public Result<Map<String, Object>> searchFoods(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category) {
        List<FoodLibrary> foods = foodLibraryService.searchFoods(keyword, category);
        List<String> categories = foodLibraryService.getAllCategories();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", foods);
        data.put("categories", categories);
        return Result.ok(data);
    }

    /** 查询所有分类 */
    @GetMapping("/categories")
    public Result<List<String>> getCategories() {
        return Result.ok(foodLibraryService.getAllCategories());
    }

    /**
     * 重新加载食物库缓存
     * <p>
     * 返回实际加载的条数：重建失败时 Service 会抛 9999，不会返回一个「成功」的假象。
     * 前端可据此确认缓存确实装进去了（例如 30 种食物）。
     */
    @PostMapping("/reload-cache")
    public Result<Map<String, Object>> reloadCache() {
        int loaded = foodLibraryService.reloadCache();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("loaded", loaded);
        return Result.ok("食物库缓存已重建", data);
    }
}

package com.fitness.repository;

import com.fitness.entity.FoodLibrary;
import com.fitness.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Repository 层切片测试（提示词测试策略：@DataJpaTest + H2 内存库，每个 Repository 至少 1 个用例）
 * <p>
 * 覆盖 UserRepository + FoodLibraryRepository 的<b>方法名派生查询</b>与 {@code @Query} 查询。
 * <p>
 * 为什么用 {@code Replace.NONE} 而不是 @DataJpaTest 默认的嵌入式库替换：
 * 默认替换会忽略 {@code application-test.yml} 里的
 * {@code MODE=MySQL;DATABASE_TO_LOWER=TRUE}，导致 H2 与 MySQL 语法/大小写行为不一致；
 * 这里复用既有测试配置（url/dialect/ddl-auto），让测试库尽可能贴近生产 MySQL 语义。
 * 数据全部落在内存库，不污染开发库。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("Repository 测试：UserRepository + FoodLibraryRepository")
class UserAndFoodRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FoodLibraryRepository foodLibraryRepository;

    @BeforeEach
    void cleanUp() {
        userRepository.deleteAll();
        foodLibraryRepository.deleteAll();
    }

    // ==================== UserRepository ====================

    @Test
    @DisplayName("findByPhone：按手机号精确取出用户，未注册手机号返回空")
    void findByPhoneShouldReturnUserForRegisteredPhoneOnly() {
        userRepository.save(user("13800138000", "老张"));

        Optional<User> found = userRepository.findByPhone("13800138000");
        assertTrue(found.isPresent(), "已注册手机号必须能查到用户");
        assertEquals("老张", found.get().getNickname());
        assertEquals("13800138000", found.get().getPhone());

        assertTrue(userRepository.findByPhone("13900139000").isEmpty(),
                "未注册手机号必须返回空，否则登录会走到密码校验而不是 1003");
    }

    @Test
    @DisplayName("existsByPhone：注册去重判断（命中已存在 → true，用于返回 1001）")
    void existsByPhoneShouldReportExistingPhone() {
        userRepository.save(user("13800138000", "老张"));

        assertTrue(userRepository.existsByPhone("13800138000"), "已注册手机号应返回 true（注册接口据此返回 1001）");
        assertFalse(userRepository.existsByPhone("13900139000"), "未注册手机号应返回 false");
        assertFalse(userRepository.existsByPhone(null), "null 手机号不应命中任何用户");
    }

    // ==================== FoodLibraryRepository ====================

    @Test
    @DisplayName("findByFoodName：精确匹配，禁止模糊命中（'鸡' 不得命中 '鸡蛋(煮)'）")
    void findByFoodNameShouldBeExactMatchNotFuzzy() {
        seedFoods();

        Optional<FoodLibrary> chicken = foodLibraryRepository.findByFoodName("鸡胸肉");
        assertTrue(chicken.isPresent(), "精确名称必须能查到");
        assertEquals(0, chicken.get().getCaloriesPer100g().compareTo(new BigDecimal("133.0")));

        assertTrue(foodLibraryRepository.findByFoodName("鸡").isEmpty(),
                "「鸡」不是库中任一食物的精确名称，必须返回空 —— 若这里模糊命中「鸡蛋(煮)」，"
                        + "饮食录入会把热量算成鸡蛋的热量（规范 4.1 要求返回 4001）");
        assertTrue(foodLibraryRepository.findByFoodName("米饭(蒸)").isEmpty(), "库中没有的精确名称应返回空");
    }

    @Test
    @DisplayName("findByFoodNameContainingAndCategory：keyword 与 category 必须同时生效")
    void findByFoodNameContainingAndCategoryShouldApplyBothFilters() {
        seedFoods();

        List<FoodLibrary> meatWithChicken = foodLibraryRepository
                .findByFoodNameContainingAndCategory("鸡", "肉类");
        assertEquals(Set.of("鸡胸肉", "鸡腿", "鸡蛋(煮)"), names(meatWithChicken),
                "应只返回「肉类」中含「鸡」的三条（主食/蔬菜里的记录不得混入）");
        assertTrue(meatWithChicken.stream().allMatch(f -> "肉类".equals(f.getCategory())),
                "分类过滤必须生效");
        assertTrue(meatWithChicken.stream().allMatch(f -> f.getFoodName().contains("鸡")),
                "关键词过滤必须生效");

        // 回归点：旧实现用 if/else 短路，传了 category 就丢弃 keyword，此处会错误返回全部主食
        assertTrue(foodLibraryRepository.findByFoodNameContainingAndCategory("鸡", "主食").isEmpty(),
                "「主食」中没有含「鸡」的食物，keyword 被丢弃时这里会错误地返回非空");
    }

    @Test
    @DisplayName("findByFoodNameContaining：跨分类模糊匹配")
    void findByFoodNameContainingShouldFuzzyMatchAcrossCategories() {
        seedFoods();

        List<FoodLibrary> chickenFoods = foodLibraryRepository.findByFoodNameContaining("鸡");
        assertEquals(3, chickenFoods.size(), "应命中 鸡胸肉/鸡腿/鸡蛋(煮) 三条");
        assertTrue(chickenFoods.stream().allMatch(f -> f.getFoodName().contains("鸡")));
        assertTrue(chickenFoods.stream().anyMatch(f -> "鸡蛋(煮)".equals(f.getFoodName())),
                "模糊查询场景下「鸡蛋(煮)」应当能被检索到（与精确查询的语义差异）");
    }

    @Test
    @DisplayName("findByCategoryOrderByFoodNameAsc：按分类过滤，未知分类返回空")
    void findByCategoryShouldReturnEmptyForUnknownCategory() {
        seedFoods();

        List<FoodLibrary> meats = foodLibraryRepository.findByCategoryOrderByFoodNameAsc("肉类");
        assertEquals(3, meats.size(), "肉类共 3 条");
        assertEquals(meats.stream().map(FoodLibrary::getFoodName).sorted().collect(Collectors.toList()),
                meats.stream().map(FoodLibrary::getFoodName).collect(Collectors.toList()),
                "结果应按 food_name 升序");

        assertTrue(foodLibraryRepository.findByCategoryOrderByFoodNameAsc("不存在的分类").isEmpty());
    }

    @Test
    @DisplayName("findAllCategories：去重且升序，供前端筛选器使用")
    void findAllCategoriesShouldReturnDistinctSortedCategories() {
        seedFoods();
        foodLibraryRepository.save(food("牛肉", "肉类", "250.0"));

        List<String> categories = foodLibraryRepository.findAllCategories();
        assertEquals(Set.of("主食", "肉类", "蔬菜"), Set.copyOf(categories), "分类必须去重");
        assertEquals(categories.stream().sorted().collect(Collectors.toList()), categories, "分类应按字典序升序");
    }

    // ==================== 测试数据 ====================

    /** 固定测试数据：含两个「鸡X肉类」与一个易被模糊误命中的「鸡蛋(煮)」 */
    private void seedFoods() {
        foodLibraryRepository.save(food("鸡胸肉", "肉类", "133.0"));
        foodLibraryRepository.save(food("鸡腿", "肉类", "181.0"));
        foodLibraryRepository.save(food("鸡蛋(煮)", "肉类", "155.0"));
        foodLibraryRepository.save(food("米饭", "主食", "116.0"));
        foodLibraryRepository.save(food("菠菜", "蔬菜", "24.0"));
        foodLibraryRepository.flush();
    }

    private FoodLibrary food(String name, String category, String calories) {
        return FoodLibrary.builder()
                .foodName(name)
                .category(category)
                .caloriesPer100g(new BigDecimal(calories))
                .build();
    }

    private User user(String phone, String nickname) {
        return User.builder()
                .nickname(nickname)
                .phone(phone)
                .password("$2a$12$fake-bcrypt-hash")
                .build();
    }

    private Set<String> names(List<FoodLibrary> foods) {
        return foods.stream().map(FoodLibrary::getFoodName).collect(Collectors.toSet());
    }
}

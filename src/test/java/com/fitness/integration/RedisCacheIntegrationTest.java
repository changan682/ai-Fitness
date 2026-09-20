package com.fitness.integration;

import com.fitness.cache.CacheKeys;
import com.fitness.cache.DistributedLockUtil;
import com.fitness.cache.RedisCacheService;
import com.fitness.config.TraceFilter;
import com.fitness.dto.HealthResponse;
import com.fitness.repository.BodyMetricRepository;
import com.fitness.repository.DietRecordRepository;
import com.fitness.repository.FoodLibraryRepository;
import com.fitness.repository.TemplateExerciseRepository;
import com.fitness.repository.TrainingRecordRepository;
import com.fitness.repository.UserRepository;
import com.fitness.repository.UserWorkoutScheduleRepository;
import com.fitness.repository.WeeklyPlanRepository;
import com.fitness.repository.WorkoutPlanTemplateRepository;
import com.fitness.service.BodyMetricService;
import com.fitness.service.DietRecordService;
import com.fitness.service.FoodLibraryService;
import com.fitness.service.HealthCheckService;
import com.fitness.service.StatsService;
import com.fitness.service.TrainingRecordService;
import com.fitness.service.UserService;
import com.fitness.service.WeeklyPlanService;
import com.fitness.service.WorkoutPlanService;
import com.fitness.task.ScheduledTasks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redis 缓存链路集成测试（提示词测试策略：@SpringBootTest 覆盖「Redis 缓存读写」核心链路）
 * <p>
 * <b>中间件替换策略</b>：dev 环境的 Redis（192.168.199.128:6379）与 RabbitMQ 在测试期不可达，
 * 因此把 {@code RedisTemplate}、{@code RedisConnectionFactory}、RabbitMQ 的
 * {@code ConnectionFactory} 全部换成 mock：
 * <ul>
 *   <li>{@code RedisTemplate} → 用「内存 Map + Mockito Answer」实现一个结构化替身，
 *       能真实回放 {@code SET/GET} 与 TTL，从而验证 {@link RedisCacheService} 的
 *       写入/读取/空值标记/回源逻辑，而不是「调用即通过」；</li>
 *   <li>两个 ConnectionFactory → 避免上下文启动或健康检查时发起真实网络连接
 *       （也用于把 HealthCheckService 的探测结果做成确定性断言）。</li>
 * </ul>
 * 数据库仍使用 H2 内存库（独立库名 fitness_it，与 @DataJpaTest 的库隔离）。
 */
@SpringBootTest(properties = {
        // 与 Repository 测试用不同的内存库名，避免两个上下文互相重建成表
        "spring.datasource.url=jdbc:h2:mem:fitness_it;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        // RedisTemplate 被 mock 替换后 getConnectionFactory() 为 null，而 Redis Repository 支持
        // （@RedisHash）本项目并未使用，故在测试中排除该自动配置，避免无关的适配器创建失败
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
})
@ActiveProfiles("test")
@DisplayName("集成测试：@SpringBootTest 上下文装配 + Redis 缓存读写链路")
class RedisCacheIntegrationTest {

    private static final Long USER_ID = 1001L;

    /** 用内存 Map 承载「Redis」数据，让缓存读写断言有真实状态可查 */
    private final Map<String, Object> fakeRedisStore = new ConcurrentHashMap<>();
    /** 记录每次写入实际使用的 TTL（秒），用于断言 ±300s 扰动 */
    private final Map<String, Long> writtenTtlSeconds = new ConcurrentHashMap<>();

    @MockBean
    private RedisTemplate<String, Object> redisTemplate;

    @MockBean
    private RedisConnectionFactory redisConnectionFactory;

    /**
     * 生产环境的 LettuceConnectionFactory 同时实现了同步/响应式两套接口；
     * 把它替换成 mock 后，RedisReactiveAutoConfiguration 的 reactiveRedisTemplate
     * 就没有 ReactiveRedisConnectionFactory 可注入了，这里补一个 mock 保持上下文 Bean 完整。
     */
    @MockBean
    private org.springframework.data.redis.connection.ReactiveRedisConnectionFactory reactiveRedisConnectionFactory;

    @MockBean
    private org.springframework.amqp.rabbit.connection.ConnectionFactory rabbitConnectionFactory;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private RedisCacheService redisCacheService;

    @Autowired
    private DistributedLockUtil distributedLockUtil;

    @Autowired
    private HealthCheckService healthCheckService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void stubFakeRedis() {
        fakeRedisStore.clear();
        writtenTtlSeconds.clear();

        ValueOperations<String, Object> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        when(valueOps.get(any())).thenAnswer(inv -> fakeRedisStore.get(inv.getArgument(0)));

        doAnswer(inv -> {
            String key = inv.getArgument(0);
            Object value = inv.getArgument(1);
            long timeout = inv.getArgument(2);
            TimeUnit unit = inv.getArgument(3);
            fakeRedisStore.put(key, value);
            writtenTtlSeconds.put(key, unit.toSeconds(timeout));
            return null;
        }).when(valueOps).set(anyString(), any(), anyLong(), any(TimeUnit.class));

        // 模拟「抢到互斥锁」，使 getOrLoad 走正常回源 + 回写分支
        when(valueOps.setIfAbsent(anyString(), any(), any(Duration.class))).thenReturn(true);
    }

    // ==================== TTL 随机扰动（防雪崩） ====================

    @Test
    @DisplayName("ttlWithJitter：结果落在 [base-300, base+300] 且不小于 1 秒")
    void ttlWithJitterShouldStayWithinRangeAndBeAtLeastOne() {
        long base = 1800;
        boolean sawDifferentValue = false;
        long first = RedisCacheService.ttlWithJitter(base);
        for (int i = 0; i < 1000; i++) {
            long ttl = RedisCacheService.ttlWithJitter(base);
            assertTrue(ttl >= base - 300 && ttl <= base + 300,
                    "TTL 必须落在 [1500, 2100]（±300s 扰动），实际=" + ttl);
            if (ttl != first) {
                sawDifferentValue = true;
            }
        }
        assertTrue(sawDifferentValue, "扰动必须真的随机（1000 次取样不应完全相同），否则防雪崩失效");

        // 小基准 TTL（空值标记 60s）：扰动后可能 ≤0，必须被钳制到 1s，否则 Redis SET 会报错
        for (int i = 0; i < 1000; i++) {
            long ttl = RedisCacheService.ttlWithJitter(60);
            assertTrue(ttl >= 1 && ttl <= 360, "空值标记 TTL 必须 >=1 且 <=360，实际=" + ttl);
        }
        assertTrue(RedisCacheService.ttlWithJitter(1) >= 1, "极小基准 TTL 也必须 >= 1s");
    }

    // ==================== 字符串缓存读写 ====================

    @Test
    @DisplayName("setWithJitter 写入后 get 能读回，且落库 TTL 带扰动")
    void setWithJitterThenGetShouldRoundTrip() {
        String key = CacheKeys.userProfile(USER_ID);

        redisCacheService.setWithJitter(key, "cached-profile", 1800);

        assertEquals("cached-profile", redisCacheService.get(key), "写入的值必须能原样读回");
        assertEquals("cached-profile", fakeRedisStore.get(key));
        long ttl = writtenTtlSeconds.get(key);
        assertTrue(ttl >= 1500 && ttl <= 2100, "写入 TTL 应为 1800±300，实际=" + ttl);
    }

    @Test
    @DisplayName("setNullMarker / isNullMarker：空值标记可被识别（防穿透）")
    void setNullMarkerShouldBeDetectedByIsNullMarker() {
        String key = CacheKeys.metricLatest(USER_ID);

        redisCacheService.setNullMarker(key);

        Object cached = redisCacheService.get(key);
        assertNotNull(cached, "空值标记必须真的写进缓存，否则防穿透失效");
        assertTrue(redisCacheService.isNullMarker(cached), "读回的值必须被识别为空值标记");
        assertFalse(redisCacheService.isNullMarker("real-value"), "普通值不能被误判为空值标记");
        assertFalse(redisCacheService.isNullMarker(null));

        long ttl = writtenTtlSeconds.get(key);
        assertTrue(ttl >= 1 && ttl <= 360, "空值标记 TTL 基准 60s + 扰动，实际=" + ttl);
    }

    // ==================== 缓存击穿防护：getOrLoad ====================

    @Test
    @DisplayName("getOrLoad：缓存命中时直接返回，不调用 loader（不查库）")
    void getOrLoadShouldReturnCachedValueWithoutCallingLoader() {
        String key = CacheKeys.statsWeekly(USER_ID, java.time.LocalDate.parse("2026-07-27"));
        fakeRedisStore.put(key, "cached-stats");
        AtomicInteger loaderCalls = new AtomicInteger();

        Supplier<String> loader = () -> {
            loaderCalls.incrementAndGet();
            return "db-stats";
        };

        String result = redisCacheService.getOrLoad(key, CacheKeys.lockStatsWeekly(USER_ID,
                java.time.LocalDate.parse("2026-07-27")), 3600, 10, loader);

        assertEquals("cached-stats", result);
        assertEquals(0, loaderCalls.get(), "缓存命中绝不能再回源 MySQL");
    }

    @Test
    @DisplayName("getOrLoad：未命中时调用 loader 并把结果回写缓存（带扰动 TTL）")
    void getOrLoadShouldInvokeLoaderAndWriteBack() {
        String key = CacheKeys.statsWeekly(USER_ID, java.time.LocalDate.parse("2026-07-27"));
        String lockKey = CacheKeys.lockStatsWeekly(USER_ID, java.time.LocalDate.parse("2026-07-27"));
        AtomicInteger loaderCalls = new AtomicInteger();

        String result = redisCacheService.getOrLoad(key, lockKey, 1800, 10, () -> {
            loaderCalls.incrementAndGet();
            return "loaded-from-db";
        });

        assertEquals("loaded-from-db", result);
        assertEquals(1, loaderCalls.get(), "未命中时必须且只回源一次");
        assertEquals("loaded-from-db", redisCacheService.get(key), "回源结果必须回写缓存");
        long ttl = writtenTtlSeconds.get(key);
        assertTrue(ttl >= 1500 && ttl <= 2100, "回写 TTL 应为 1800±300，实际=" + ttl);
        // 回源前必须尝试抢互斥锁（解锁走 Lua 脚本，已在 DistributedLockUtilTest 覆盖）
        verify(redisTemplate.opsForValue()).setIfAbsent(eq(lockKey), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("getOrLoad：loader 返回 null 时写空值标记，第二次请求不再回源")
    void getOrLoadShouldWriteNullMarkerWhenLoaderReturnsNull() {
        String key = CacheKeys.userProfile(USER_ID);
        String lockKey = "lock:test:profile:" + USER_ID;   // 锁 Key 按规范不加缓存前缀，此处用测试专用名
        AtomicInteger loaderCalls = new AtomicInteger();
        Supplier<String> nullLoader = () -> {
            loaderCalls.incrementAndGet();
            return null;
        };

        assertNull(redisCacheService.getOrLoad(key, lockKey, 60, 10, nullLoader));
        assertEquals(1, loaderCalls.get());
        assertTrue(redisCacheService.isNullMarker(fakeRedisStore.get(key)),
                "回源为空时必须写入空值标记，否则同一个无效 key 会反复穿透到 MySQL");

        assertNull(redisCacheService.getOrLoad(key, lockKey, 60, 10, nullLoader));
        assertEquals(1, loaderCalls.get(), "第二次请求应命中空值标记直接返回 null，不再回源");
    }

    @Test
    @DisplayName("分布式锁：tryLock 拿到唯一标识，unlock 释放自己不持有的锁时不抛异常")
    void distributedLockShouldAcquireAndRelease() {
        String lockValue = distributedLockUtil.tryLock(CacheKeys.lockScheduled("weeklyStats"), 120);

        assertNotNull(lockValue, "SET NX 成功时应返回唯一锁标识");
        verify(redisTemplate.opsForValue())
                .setIfAbsent(eq(CacheKeys.lockScheduled("weeklyStats")), eq(lockValue), any(Duration.class));
        distributedLockUtil.unlock(CacheKeys.lockScheduled("weeklyStats"), lockValue);
    }

    // ==================== 上下文装配 ====================

    @Test
    @DisplayName("Spring 上下文完整启动：TraceFilter / HealthCheckService / WorkoutPlanService 等全部 Bean 装配成功")
    void contextShouldExposeAllCoreBeans() {
        // 本轮新加/修复的 Bean
        assertNotNull(applicationContext.getBean(TraceFilter.class), "TraceFilter（链路追踪）必须装配成功");
        assertNotNull(applicationContext.getBean(HealthCheckService.class), "HealthCheckService 必须装配成功");
        assertNotNull(applicationContext.getBean(WorkoutPlanService.class), "WorkoutPlanService 必须装配成功");
        assertNotNull(applicationContext.getBean(WeeklyPlanService.class));
        assertNotNull(applicationContext.getBean(ScheduledTasks.class), "定时任务 Bean 必须装配成功");

        // 缓存三件套
        assertNotNull(applicationContext.getBean(RedisCacheService.class));
        assertNotNull(applicationContext.getBean(DistributedLockUtil.class));

        // Service 层
        for (Class<?> type : List.of(UserService.class, TrainingRecordService.class, BodyMetricService.class,
                DietRecordService.class, FoodLibraryService.class, StatsService.class)) {
            assertNotNull(applicationContext.getBean(type), type.getSimpleName() + " 必须装配成功");
        }

        // 9 个 Repository 全部装配成功
        for (Class<?> type : List.of(UserRepository.class, TrainingRecordRepository.class,
                BodyMetricRepository.class, DietRecordRepository.class, FoodLibraryRepository.class,
                WeeklyPlanRepository.class, WorkoutPlanTemplateRepository.class,
                TemplateExerciseRepository.class, UserWorkoutScheduleRepository.class)) {
            assertNotNull(applicationContext.getBean(type), type.getSimpleName() + " 必须装配成功");
        }
    }

    @Test
    @DisplayName("数据源必须是 H2 内存库（测试不得连接开发/生产库）")
    void dataSourceShouldBeInMemoryH2() throws SQLException {
        DataSource dataSource = applicationContext.getBean(DataSource.class);

        try (java.sql.Connection connection = dataSource.getConnection()) {
            String url = connection.getMetaData().getURL();
            assertTrue(url.startsWith("jdbc:h2:mem:"), "测试库必须是 H2 内存库，实际=" + url);
            assertTrue(url.contains("fitness_it"), "应使用集成测试专用库名，与开发库隔离");
            assertTrue(connection.isValid(2), "H2 连接必须可用（Hibernate 已按实体建表）");
        }
    }

    @Test
    @DisplayName("健康检查（提示词 10.1）：MySQL/Redis/RabbitMQ 三项探测与总体状态判定")
    void healthCheckShouldReportDependencyStatus() {
        // Redis：mock 出 PONG；RabbitMQ：mock 出「连接已打开」；中间件连接全部被替换，不产生真实网络访问
        RedisConnection redisConnection = mock(RedisConnection.class);
        given(redisConnectionFactory.getConnection()).willReturn(redisConnection);
        given(redisConnection.ping()).willReturn("PONG");

        Connection rabbitConnection = mock(Connection.class);
        given(rabbitConnectionFactory.createConnection()).willReturn(rabbitConnection);
        given(rabbitConnection.isOpen()).willReturn(true);

        HealthResponse response = healthCheckService.check();

        assertEquals(List.of("mysql", "redis", "rabbitmq", "pythonAgent"),
                List.copyOf(response.getServices().keySet()),
                "规范 10.1 要求逐项返回这 4 个依赖的状态");
        assertEquals("UP", response.getServices().get("mysql"), "H2 数据源可连通 → mysql UP");
        assertEquals("UP", response.getServices().get("redis"));
        assertEquals("UP", response.getServices().get("rabbitmq"));
        assertEquals("UP", response.getStatus(), "硬依赖 MySQL/Redis 均 UP → 总体 UP");
        assertTrue(Set.of("UP", "DOWN").contains(response.getServices().get("pythonAgent")),
                "Python Agent 属可降级依赖，值只可能是 UP/DOWN（本机未启动 Python 服务时为 DOWN）");
        assertNotNull(response.getTimestamp());
    }
}

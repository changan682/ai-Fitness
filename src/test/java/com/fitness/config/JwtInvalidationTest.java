package com.fitness.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitness.cache.CacheKeys;
import com.fitness.cache.RedisCacheService;
import com.fitness.cache.TokenBlacklistService;
import com.fitness.util.JwtUtil;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * JWT 失效机制测试 —— 规范第 6 条（黑名单必须按 jti 而不是 userId）
 *
 * <h3>背景：为什么必须按 jti</h3>
 * 原实现用 {@code user:token:blacklist:{userId}} 存 Token 值。同一用户多设备登录时，
 * 两个 Token 会落到同一个 key 上：后登出的会覆盖先登出的记录，
 * 于是「先登出的那个 Token」又恢复可用 —— 这是真实的鉴权漏洞。
 * 本测试覆盖的正是这个场景（两个 Token 分别登出，互不覆盖）。
 *
 * <h3>为什么用真实的 JwtUtil + 内存版 Redis</h3>
 * 只把 {@link RedisCacheService} 换成「内存 Map」的实现，
 * 这样 {@link TokenBlacklistService} 与 {@link JwtInterceptor} 都跑真实逻辑，
 * 断言的是真实的失效语义，而不是「某个 mock 被调用过」。
 */
@DisplayName("JWT 失效机制：jti 黑名单 + iat 水位线")
class JwtInvalidationTest {

    private static final String SECRET = "test-only-secret-key-at-least-32-bytes-long!!";
    private static final Long USER_ID = 1001L;

    /** 模拟的 Redis：key → value */
    private final Map<String, Object> redis = new HashMap<>();
    /** 模拟的 Redis：key → TTL（毫秒），用于断言 TTL 语义 */
    private final Map<String, Long> ttlMs = new HashMap<>();

    private JwtUtil jwtUtil;
    private TokenBlacklistService tokenBlacklistService;
    private JwtInterceptor jwtInterceptor;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(SECRET);
        properties.setExpirationDays(7);
        jwtUtil = new JwtUtil(properties);

        RedisCacheService redisCacheService = mock(RedisCacheService.class);
        when(redisCacheService.exists(anyString()))
                .thenAnswer(inv -> redis.containsKey(inv.getArgument(0)));
        when(redisCacheService.get(anyString()))
                .thenAnswer(inv -> redis.get(inv.getArgument(0)));
        when(redisCacheService.getExpireMillis(anyString()))
                .thenAnswer(inv -> ttlMs.getOrDefault(inv.getArgument(0), -2L));
        doAnswer(inv -> {
            String key = inv.getArgument(0);
            long timeout = inv.getArgument(2);
            TimeUnit unit = inv.getArgument(3);
            redis.put(key, inv.getArgument(1));
            ttlMs.put(key, unit.toMillis(timeout));
            return null;
        }).when(redisCacheService).set(anyString(), any(), anyLong(), any(TimeUnit.class));
        doAnswer(inv -> {
            // setWithJitter(key, value, baseTtlSeconds)
            // 注意：必须先把 value 取成 Object 再 String.valueOf，否则编译器会把
            // getArgument 的泛型推断成 char[]（String.valueOf 有 char[] 重载），运行时 ClassCastException
            String key = inv.getArgument(0);
            Object value = inv.getArgument(1);
            redis.put(key, String.valueOf(value));
            return null;
        }).when(redisCacheService).setWithJitter(anyString(), any(), anyLong());

        tokenBlacklistService = new TokenBlacklistService(redisCacheService);
        jwtInterceptor = new JwtInterceptor(jwtUtil, tokenBlacklistService, new ObjectMapper());
    }

    // ==================== Token 本身 ====================

    @Test
    @DisplayName("签发的 Token 必须带 jti（唯一）与 iat")
    void generatedTokenShouldCarryJtiAndIssuedAt() {
        String token = jwtUtil.generateToken(USER_ID, "13800138000");

        assertNotNull(jwtUtil.getJti(token), "Token 必须带 jti，否则无法进入黑名单");
        assertNotNull(jwtUtil.getIssuedAt(token), "Token 必须带 iat，水位线校验依赖它");
        assertNotNull(jwtUtil.getUserIdFromToken(token));
    }

    @Test
    @DisplayName("同一用户连续登录两次 → 两个 Token 的 jti 不同")
    void eachLoginShouldGetDistinctJti() {
        String first = jwtUtil.generateToken(USER_ID, "13800138000");
        String second = jwtUtil.generateToken(USER_ID, "13800138000");

        assertNotEquals(jwtUtil.getJti(first), jwtUtil.getJti(second),
                "jti 必须每次登录都不同，否则多设备会互相顶掉");
    }

    // ==================== 登出：多设备互不覆盖（核心回归用例） ====================

    @Test
    @DisplayName("多设备场景：设备A登出后设备B仍可用，设备B登出也不会让A恢复可用")
    void logoutOnOneDeviceShouldNotAffectAnotherDevice() throws Exception {
        String deviceA = jwtUtil.generateToken(USER_ID, "13800138000");
        String deviceB = jwtUtil.generateToken(USER_ID, "13800138000");

        // 设备 A 登出
        tokenBlacklistService.blacklist(jwtUtil.getJti(deviceA), jwtUtil.getRemainingTime(deviceA));

        assertEquals(9001, preHandle(deviceA), "A 已登出 → 必须被拦下");
        assertEquals(0, preHandle(deviceB), "B 未登出 → 不应受影响");

        // 设备 B 也登出。以 userId 为 key 的旧实现会在这里覆盖掉 A 的记录，让 A 重新可用
        tokenBlacklistService.blacklist(jwtUtil.getJti(deviceB), jwtUtil.getRemainingTime(deviceB));

        assertEquals(9001, preHandle(deviceA), "A 必须仍然失效（不能被 B 的登出覆盖）");
        assertEquals(9001, preHandle(deviceB));
    }

    @Test
    @DisplayName("黑名单 key 形态为 user:token:blacklist:{jti}，TTL = Token 剩余有效期")
    void blacklistShouldUseJtiKeyWithRemainingTtl() {
        String token = jwtUtil.generateToken(USER_ID, "13800138000");
        String jti = jwtUtil.getJti(token);

        tokenBlacklistService.blacklist(jti, jwtUtil.getRemainingTime(token));

        String expectedKey = CacheKeys.userTokenBlacklist(jti);
        assertTrue(redis.containsKey(expectedKey), "key 必须是 " + expectedKey);
        assertTrue(ttlMs.get(expectedKey) > 0, "TTL 应为 Token 剩余有效期（毫秒）");
        assertTrue(ttlMs.get(expectedKey) <= 7L * 24 * 3600 * 1000,
                "TTL 不应超过 JWT 有效期（Token 过期后自然失效，无需长期占用 Redis）");
    }

    @Test
    @DisplayName("空 jti 不写入黑名单（避免产生无意义的 key）")
    void blacklistShouldIgnoreBlankJti() {
        tokenBlacklistService.blacklist(null, 1000L);
        tokenBlacklistService.blacklist("  ", 1000L);

        assertTrue(redis.isEmpty(), "空 jti 不应产生任何 Redis 写入");
    }

    // ==================== 改密码：iat 水位线 ====================

    @Test
    @DisplayName("改密码后：此前签发的 Token 全部失效（逐个 jti 无法枚举，必须靠水位线）")
    void passwordChangeShouldInvalidateAllPreviouslyIssuedTokens() throws Exception {
        String oldToken = jwtUtil.generateToken(USER_ID, "13800138000");
        long issuedAt = jwtUtil.getIssuedAt(oldToken).getTime();

        // 模拟改密码：水位线设在「旧 Token 签发之后」
        long watermark = issuedAt + 1;
        tokenBlacklistService.markAllTokensInvalidBefore(USER_ID, watermark);

        assertEquals(9001, preHandle(oldToken), "改密前签发的 Token 必须被拦下");
        assertTrue(tokenBlacklistService.isIssuedBeforeWatermark(USER_ID, issuedAt),
                "iat 早于水位线 → 失效");
        assertFalse(tokenBlacklistService.isIssuedBeforeWatermark(USER_ID, watermark),
                "iat 等于水位线（改密同一毫秒后签发）不算失效，"
                        + "这条边界由「改密时顺手按 jti 拉黑当前 Token」补齐");
    }

    @Test
    @DisplayName("水位线只前进不回退，且写入 user:token:invalid-after:{userId}")
    void watermarkShouldOnlyMoveForward() {
        tokenBlacklistService.markAllTokensInvalidBefore(USER_ID, 1_000_000L);
        tokenBlacklistService.markAllTokensInvalidBefore(USER_ID, 500_000L);   // 更早的时间戳

        assertTrue(redis.containsKey(CacheKeys.userTokenInvalidAfter(USER_ID)),
                "水位线 key 必须为 user:token:invalid-after:{userId}");
        assertTrue(tokenBlacklistService.isIssuedBeforeWatermark(USER_ID, 900_000L),
                "回退的时间戳不得覆盖已设置的水位线（否则会把已失效的 Token 又放行）");
    }

    // ==================== 缺少 jti 的 Token ====================

    @Test
    @DisplayName("鉴权：无 jti 的 Token 直接拒绝（无法进入黑名单的 Token 不能放行）")
    void tokenWithoutJtiShouldBeRejected() throws Exception {
        String legacy = Jwts.builder()
                .subject(String.valueOf(USER_ID))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3600_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes()))
                .compact();

        assertEquals(9001, preHandle(legacy),
                "老版本/伪造的无 jti Token 无法被拉黑，必须直接拒绝");
    }

    // ==================== 辅助方法 ====================

    /**
     * 用真实 JwtInterceptor 处理一次请求
     *
     * @return 0 表示放行；否则返回响应体里的业务错误码（9001）
     */
    private int preHandle(String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/user/profile");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = jwtInterceptor.preHandle(request, response, new Object());
        if (allowed) {
            return 0;
        }
        // 拦截器把业务码写在 JSON body 里（HTTP 状态保持 200，见 JwtInterceptor 的注释）
        return new ObjectMapper().readTree(response.getContentAsString()).get("code").asInt();
    }
}

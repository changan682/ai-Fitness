package com.fitness.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * JWT Token 黑名单 — 登出 / 刷新旧 Token / 改密后作废已签发的 Token
 *
 * <h3>为什么按 jti 而不是 userId 存</h3>
 * 规范最初的示例是 {@code user:token:blacklist:{userId}} 存 Token 值，但同一用户多设备登录时
 * 两个 Token 会落在同一个 key 上：后登出的会覆盖先登出的记录，
 * 于是「先登出的那个 Token」又变回可用 —— 这是实打实的鉴权漏洞。
 * 因此改为以 JWT 自带的 <b>jti</b>（唯一 ID）为 key，每个 Token 一条独立记录，多设备互不影响：
 * <pre>
 *   登出     → SET fitness:cache:user:token:blacklist:{jti} 1 EX {Token剩余有效期}
 *   鉴权     → 解析出 jti，exists(key) 为真则拒绝
 * </pre>
 *
 * <h3>改密码 / 强制下线：为什么还需要水位线</h3>
 * 这两类场景要求一次作废该用户的<b>全部</b>Token，但服务端拿不到其它设备的 jti（无法枚举），
 * 因此另用一条「失效水位线」{@code user:token:invalid-after:{userId}}：
 * 鉴权时只要 Token 的 {@code iat} 早于水位线就拒绝。两者叠加即可覆盖所有作废场景。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenBlacklistService {

    /** 黑名单 key 的存在性即代表「已失效」，值本身没有语义 */
    private static final String PRESENT = "1";

    private final RedisCacheService redisCacheService;

    // ==================== 单 Token 拉黑（按 jti） ====================

    /**
     * 拉黑一个 Token
     *
     * @param jti       Token 的唯一 ID（{@code JwtUtil#getJti}）
     * @param ttlMillis Token 剩余有效期；取「Token 剩余有效期」与「key 现有 TTL」的较大值，
     *                  避免重复写入时缩短已存在记录的寿命（TTL 到点即 Token 自然过期，无需再记录）
     */
    public void blacklist(String jti, long ttlMillis) {
        if (jti == null || jti.isBlank() || ttlMillis <= 0) {
            return;
        }
        String key = CacheKeys.userTokenBlacklist(jti);
        try {
            long existingTtlMs = redisCacheService.getExpireMillis(key);
            long effectiveTtlMs = Math.max(ttlMillis, existingTtlMs);
            redisCacheService.set(key, PRESENT, effectiveTtlMs, TimeUnit.MILLISECONDS);
            log.debug("Token 已加入黑名单: jti={}, 剩余有效期={}ms", jti, ttlMillis);
        } catch (Exception e) {
            // 写失败不能静默：否则会留下一个仍然可用的旧 Token
            log.error("Token 黑名单写入失败: jti={}", jti, e);
        }
    }

    /**
     * 判断某个 Token（按 jti）是否已被拉黑（{@code JwtInterceptor} 鉴权时调用）
     * <p>
     * Redis 异常时返回 {@code false}（放行）是刻意的可用性取舍：黑名单是「提前失效」的优化，
     * Token 本身仍有签名与过期时间兜底；若这里 fail-closed，
     * Redis 抖动会导致全部用户被强制登出。异常会以 ERROR 级别记录，不会被忽略。
     */
    public boolean contains(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        try {
            return redisCacheService.exists(CacheKeys.userTokenBlacklist(jti));
        } catch (Exception e) {
            log.error("Token 黑名单查询失败，本次按未拉黑处理: jti={}", jti, e);
            return false;
        }
    }

    // ==================== 全量失效水位线（改密码 / 强制下线） ====================

    /**
     * 让该用户在此刻之前签发的所有 Token 立即失效
     *
     * @param userId      用户 ID
     * @param epochMillis 失效水位线（当前时间毫秒戳）
     */
    public void markAllTokensInvalidBefore(Long userId, long epochMillis) {
        if (userId == null || epochMillis <= 0) {
            return;
        }
        String key = CacheKeys.userTokenInvalidAfter(userId);
        try {
            // 水位线只可能被「改密时间」推进，取较大值可避免并发下被旧时间戳回退
            long current = readWatermark(key);
            if (current >= epochMillis) {
                return;
            }
            redisCacheService.setWithJitter(key, String.valueOf(epochMillis),
                    CacheKeys.TOKEN_INVALID_AFTER_TTL_SECONDS);
            log.info("已设置 Token 失效水位线: userId={}, invalidAfter={}", userId, epochMillis);
        } catch (Exception e) {
            log.error("Token 失效水位线写入失败: userId={}", userId, e);
        }
    }

    /**
     * 判断某个 Token 是否早于失效水位线（即已被「改密码 / 强制下线」作废）
     *
     * @param issuedAtMillis Token 的 iat（毫秒）；为 null/0 表示 Token 不带 iat，
     *                       此时只要存在水位线就判为失效（保守策略，宁可要求重新登录）
     */
    public boolean isIssuedBeforeWatermark(Long userId, Long issuedAtMillis) {
        if (userId == null) {
            return false;
        }
        try {
            long watermark = readWatermark(CacheKeys.userTokenInvalidAfter(userId));
            if (watermark <= 0) {
                return false;
            }
            return issuedAtMillis == null || issuedAtMillis < watermark;
        } catch (Exception e) {
            log.error("Token 失效水位线查询失败，本次按有效处理: userId={}", userId, e);
            return false;
        }
    }

    // ==================== 内部工具 ====================

    /** 读取水位线（毫秒戳），不存在或解析失败返回 0 */
    private long readWatermark(String key) {
        Object cached = redisCacheService.get(key);
        if (cached == null) {
            return 0L;
        }
        try {
            return Long.parseLong(cached.toString().trim());
        } catch (NumberFormatException e) {
            log.warn("Token 失效水位线解析失败，按未设置处理: key={}, value={}", key, cached);
            return 0L;
        }
    }
}

package com.fitness.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Redis 缓存服务 — 统一封装 String/Hash/List 读写 + Key 前缀管理
 * <p>
 * 规范第 908 行明确要求「统一封装 Redis 读写操作」，因此业务代码不得直接注入
 * {@code RedisTemplate}，必须经过本类，以获得：
 * <ul>
 *   <li>统一的 key 前缀（{@link CacheKeys#PREFIX}）</li>
 *   <li>统一的 TTL 随机扰动（防雪崩，见 {@link #setWithJitter}）</li>
 *   <li>统一的空值标记（防穿透，见 {@link #setNullMarker}）</li>
 *   <li>统一的互斥锁回源（防击穿，见 {@link #getOrLoad}）</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisCacheService {

    /** TTL 随机扰动幅度（秒）— 规范强约束：防大量 key 同时过期引发缓存雪崩 */
    private static final int TTL_JITTER_SECONDS = 300;

    /** 空值标记 TTL（秒）— 规范：查询结果为 null 时缓存空值标记，60s 即可 */
    public static final long NULL_MARKER_TTL_SECONDS = 60L;

    /** 空值哨兵值 — 用于区分「缓存了 null」与「缓存未命中」 */
    private static final String NULL_MARKER = "__NULL__";

    /** Hash 形式空值标记所用的 field 名 */
    private static final String NULL_MARKER_FIELD = "__NULL__";

    /** 未抢到互斥锁时的等待时长（毫秒），等待后重试读缓存 */
    private static final long LOCK_RETRY_WAIT_MS = 100L;

    private final RedisTemplate<String, Object> redisTemplate;
    private final DistributedLockUtil distributedLockUtil;

    /** 计算到次日凌晨的 TTL（秒）— 用于「今日」类缓存（今日训练、AI每日总结） */
    public static long secondsUntilMidnight() {
        return Duration.between(LocalTime.now(), LocalTime.MAX).getSeconds() + 1;
    }

    /**
     * 为基准 TTL 叠加 ±300s 随机扰动
     * <p>
     * 下限钳制为 1s，避免基准 TTL 很小（如 60s 空值标记）时算出非正数导致 SET 报错。
     */
    public static long ttlWithJitter(long baseSeconds) {
        long delta = ThreadLocalRandom.current().nextLong(-TTL_JITTER_SECONDS, TTL_JITTER_SECONDS + 1L);
        return Math.max(baseSeconds + delta, 1L);
    }

    // ==================== 穿透 / 击穿 / 雪崩防护 ====================

    /**
     * 写入空值标记（防缓存穿透）
     * <p>
     * 查询结果为空时写入哨兵值，后续相同请求在 TTL 内直接命中该标记并返回 null，
     * 不再穿透到 MySQL。TTL 取 60s：既能挡住短时间内的重复无效查询，
     * 又能在数据真实产生后快速恢复（不会长时间返回错误的「不存在」）。
     */
    public void setNullMarker(String key) {
        set(key, NULL_MARKER, ttlWithJitter(NULL_MARKER_TTL_SECONDS), TimeUnit.SECONDS);
    }

    /** 判断缓存值是否为空值标记 */
    public boolean isNullMarker(Object value) {
        return NULL_MARKER.equals(value);
    }

    /**
     * 缓存击穿防护：热点 key 失效瞬间只允许一个线程回源
     * <p>
     * 流程：读缓存 → 命中直接返回 → 未命中则抢互斥锁 →
     * 抢到锁的调用 loader 回源并写回缓存（null 则写空值标记）→ 释放锁；
     * 未抢到锁的短暂等待后重试读缓存，仍未命中则直接回源兜底，
     * 保证任何情况下都不出现「前端白屏」。
     *
     * @param cacheKey        缓存 key
     * @param lockKey         互斥锁 key（规范示例：lock:ai:summary:{userId}:{date}）
     * @param baseTtlSeconds  缓存基准 TTL
     * @param lockTtlSeconds  锁持有 TTL（应大于回源耗时，避免锁提前释放）
     * @param loader          回源逻辑
     */
    @SuppressWarnings("unchecked")
    public <T> T getOrLoad(String cacheKey, String lockKey, long baseTtlSeconds,
                           long lockTtlSeconds, Supplier<T> loader) {
        Object cached = get(cacheKey);
        if (cached != null) {
            log.debug("缓存命中: key={}", cacheKey);
            return isNullMarker(cached) ? null : (T) cached;
        }

        String lockValue = distributedLockUtil.tryLock(lockKey, lockTtlSeconds);
        if (lockValue == null) {
            // 其他线程正在回源，短暂等待后重试读缓存
            log.debug("未获得回源互斥锁，等待重试: key={}", cacheKey);
            sleepQuietly(LOCK_RETRY_WAIT_MS);
            Object retry = get(cacheKey);
            if (retry != null) {
                return isNullMarker(retry) ? null : (T) retry;
            }
            // 兜底：对方回源较慢时直接自己查，保证接口可用性优先于「只回源一次」
            return loader.get();
        }

        try {
            T value = loader.get();
            if (value == null) {
                setNullMarker(cacheKey);
            } else {
                setWithJitter(cacheKey, value, baseTtlSeconds);
            }
            return value;
        } finally {
            distributedLockUtil.unlock(lockKey, lockValue);
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ==================== String 操作 ====================

    public void set(String key, Object value, long timeout, TimeUnit unit) {
        redisTemplate.opsForValue().set(key, value, timeout, unit);
    }

    /** 带随机扰动的写入 — 所有业务缓存统一走此入口，避免硬编码未经扰动的 TTL */
    public void setWithJitter(String key, Object value, long baseTtlSeconds) {
        set(key, value, ttlWithJitter(baseTtlSeconds), TimeUnit.SECONDS);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) redisTemplate.opsForValue().get(key);
    }

    public boolean exists(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    public void delete(String key) {
        redisTemplate.delete(key);
    }

    public void deleteAll(Collection<String> keys) {
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    // ==================== Hash 操作 ====================

    public void hSet(String key, String field, Object value) {
        redisTemplate.opsForHash().put(key, field, value);
    }

    /**
     * 以 Hash 形式写入空值标记（防穿透）
     * <p>
     * 为什么不复用 {@link #setNullMarker}：同一个 key 不能既是 String 又是 Hash，
     * 否则 Redis 会抛 WRONGTYPE；Hash 类缓存必须用 Hash 形式的哨兵 field。
     */
    public void hSetNullMarker(String key, long baseTtlSeconds) {
        hSet(key, NULL_MARKER_FIELD, NULL_MARKER);
        expireWithJitter(key, baseTtlSeconds);
    }

    /** 判断 Hash 缓存是否为空值标记 */
    public boolean hIsNullMarker(String key) {
        return isNullMarker(hGet(key, NULL_MARKER_FIELD));
    }

    /**
     * 向 Hash 的指定 field 写入空值标记（防穿透）
     * <p>
     * 用于「按名称精确查询」这类场景（如食物库 hash field=食物名）：
     * 不存在的名称也写一个哨兵 field，避免同一个无效名称反复穿透到 MySQL。
     */
    public void hSetNullField(String key, String field, long baseTtlSeconds) {
        hSet(key, field, NULL_MARKER);
        expireWithJitter(key, baseTtlSeconds);
    }

    public Object hGet(String key, String field) {
        return redisTemplate.opsForHash().get(key, field);
    }

    public void hSetAll(String key, Map<String, ?> map) {
        if (map != null && !map.isEmpty()) {
            redisTemplate.opsForHash().putAll(key, map);
        }
    }

    /** 读取整个 Hash（field → value） */
    public Map<Object, Object> hGetAll(String key) {
        return redisTemplate.opsForHash().entries(key);
    }

    public long hSize(String key) {
        Long size = redisTemplate.opsForHash().size(key);
        return size == null ? 0L : size;
    }

    public void hDelete(String key, String... fields) {
        redisTemplate.opsForHash().delete(key, (Object[]) fields);
    }

    // ==================== List 操作 ====================

    /** 追加到列表尾部（今日训练记录按时间顺序追加，读取时无需反转） */
    public void rPush(String key, Object value) {
        redisTemplate.opsForList().rightPush(key, value);
    }

    public void rPushAll(String key, Collection<?> values) {
        if (values != null && !values.isEmpty()) {
            redisTemplate.opsForList().rightPushAll(key, values.toArray());
        }
    }

    @SuppressWarnings("unchecked")
    public <T> List<T> lRange(String key, long start, long end) {
        return (List<T>) redisTemplate.opsForList().range(key, start, end);
    }

    // ==================== 过期时间 ====================

    public void expire(String key, long timeout, TimeUnit unit) {
        redisTemplate.expire(key, timeout, unit);
    }

    /** 设置带随机扰动的过期时间（用于已存在的 key，如 List 追加后补 TTL） */
    public void expireWithJitter(String key, long baseTtlSeconds) {
        redisTemplate.expire(key, ttlWithJitter(baseTtlSeconds), TimeUnit.SECONDS);
    }

    /** 读取 key 剩余有效期（毫秒），key 不存在或未设过期时返回 -1/-2 */
    public long getExpireMillis(String key) {
        Long ttl = redisTemplate.getExpire(key, TimeUnit.MILLISECONDS);
        return ttl == null ? -2L : ttl;
    }
}

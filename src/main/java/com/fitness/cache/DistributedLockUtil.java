package com.fitness.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Redis 分布式锁 — 基于 SET NX EX + Lua 脚本安全释放
 * <p>
 * 用于定时任务防重（多实例部署场景）和回调幂等
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DistributedLockUtil {

    private final RedisTemplate<String, Object> redisTemplate;

    /** Lua 脚本：值匹配才删除，防止误删其他实例的锁 */
    private static final String UNLOCK_SCRIPT = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            else
                return 0
            end
            """;

    /**
     * 尝试获取分布式锁
     * @param lockKey 锁的key
     * @param timeoutSec 锁的过期时间（秒）
     * @return 锁的唯一标识（null表示获取失败），用于释放锁
     */
    public String tryLock(String lockKey, long timeoutSec) {
        String lockValue = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, lockValue, Duration.ofSeconds(timeoutSec));
        if (Boolean.TRUE.equals(acquired)) {
            log.debug("获取分布式锁成功: key={}, value={}", lockKey, lockValue);
            return lockValue;
        }
        log.debug("获取分布式锁失败: key={}", lockKey);
        return null;
    }

    /**
     * 释放分布式锁（仅释放自己持有的锁）
     * @param lockKey 锁的key
     * @param lockValue 锁的唯一标识
     */
    public void unlock(String lockKey, String lockValue) {
        if (lockValue == null) return;
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(UNLOCK_SCRIPT, Long.class);
        Long result = redisTemplate.execute(script, List.of(lockKey), lockValue);
        if (result != null && result > 0) {
            log.debug("释放分布式锁成功: key={}", lockKey);
        }
    }
}

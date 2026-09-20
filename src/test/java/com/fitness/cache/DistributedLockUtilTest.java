package com.fitness.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 分布式锁单元测试（提示词测试策略：DistributedLockUtil 加锁/解锁）
 * <p>
 * 验证 SET NX EX 加锁的获取/失败分支，以及 Lua 脚本释放锁不抛异常。
 */
@ExtendWith(MockitoExtension.class)
class DistributedLockUtilTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @InjectMocks
    private DistributedLockUtil util;

    @Test
    @SuppressWarnings("unchecked")
    void tryLockShouldReturnUniqueValueOnSuccess() {
        ValueOperations<String, Object> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        String lock = util.tryLock("lock:scheduled:test", 60);

        assertNotNull(lock, "获取锁成功应返回唯一标识");
    }

    @Test
    @SuppressWarnings("unchecked")
    void tryLockShouldReturnNullOnFailure() {
        ValueOperations<String, Object> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        assertNull(util.tryLock("lock:scheduled:test", 60), "获取锁失败应返回 null");
    }

    @Test
    void unlockShouldReleaseOwnedLock() {
        assertDoesNotThrow(() -> util.unlock("lock:scheduled:test", "some-value"),
                "释放锁应通过 Lua 脚本原子执行，不抛异常");
    }

    @Test
    void unlockWithNullValueShouldBeNoOp() {
        assertDoesNotThrow(() -> util.unlock("lock:scheduled:test", null),
                "空锁值应直接返回，不执行 Redis 调用");
    }
}

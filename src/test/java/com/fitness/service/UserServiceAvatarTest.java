package com.fitness.service;

import com.fitness.cache.RedisCacheService;
import com.fitness.config.JwtProperties;
import com.fitness.dto.AvatarUploadResponse;
import com.fitness.entity.User;
import com.fitness.repository.UserRepository;
import com.fitness.util.JwtUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 头像上传的服务层编排测试（Mockito，不启动 Spring）。
 *
 * <h3>为什么单独测"编排"而不是只测文件读写</h3>
 * 头像上传真正的风险点是**多步操作的顺序与失败回滚**：
 * 写了新文件却改库失败会留下孤儿文件、换了头像却不失效缓存会让前端继续显示旧图。
 * 这些都不在 {@link AvatarStorageServiceTest} 的覆盖范围内。
 */
@ExtendWith(MockitoExtension.class)
class UserServiceAvatarTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private JwtProperties jwtProperties;

    @Mock
    private RedisCacheService redisCacheService;

    @Mock
    private com.fitness.cache.TokenBlacklistService tokenBlacklistService;

    @Mock
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Mock
    private AvatarStorageService avatarStorageService;

    @InjectMocks
    private UserService userService;

    private static final String NEW_URL = "/api/v1/user/avatar/5?v=1789999999999";
    private static final String OLD_URL = "/api/v1/user/avatar/5?v=1700000000000";

    private static MultipartFile someFile() {
        return new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[]{1, 2, 3});
    }

    @Test
    @DisplayName("上传成功：写库 → 事务提交后失效档案缓存 → 删旧文件")
    void uploadReplacesAvatarAndCleansOldFile() {
        User user = User.builder().id(5L).nickname("张三").avatarUrl(OLD_URL).build();
        when(userRepository.findById(5L)).thenReturn(Optional.of(user));
        when(avatarStorageService.store(eq(5L), any())).thenReturn(NEW_URL);

        AvatarUploadResponse response = runInTransaction(() -> userService.uploadAvatar(5L, someFile()));

        assertEquals(NEW_URL, response.getAvatarUrl());
        assertEquals(NEW_URL, user.getAvatarUrl(), "库里必须换成新路径");
        verify(userRepository).saveAndFlush(user);
        verify(avatarStorageService).deleteQuietly(5L, OLD_URL);
    }

    @Test
    @DisplayName("档案缓存在事务提交后才失效（延迟双删第 2 步），提交前不删")
    void profileCacheEvictedOnlyAfterCommit() {
        User user = User.builder().id(5L).nickname("张三").avatarUrl(OLD_URL).build();
        when(userRepository.findById(5L)).thenReturn(Optional.of(user));
        when(avatarStorageService.store(eq(5L), any())).thenReturn(NEW_URL);

        // 只开事务同步、不触发 afterCommit：此时缓存必须**还没**被删（事务未提交，
        // 提前删会让并发请求把旧值重新读回缓存 —— 这正是"延迟双删"要解决的问题）
        TransactionSynchronizationManager.initSynchronization();
        try {
            userService.uploadAvatar(5L, someFile());
            verify(redisCacheService, never()).delete(any());

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);
            verify(redisCacheService).delete(com.fitness.cache.CacheKeys.userProfile(5L));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("首次上传（原本没有头像）：不调用删除，也不报错")
    void firstUploadHasNoOldFile() {
        User user = User.builder().id(5L).nickname("张三").build();
        when(userRepository.findById(5L)).thenReturn(Optional.of(user));
        when(avatarStorageService.store(eq(5L), any())).thenReturn(NEW_URL);

        userService.uploadAvatar(5L, someFile());

        verify(avatarStorageService, never()).deleteQuietly(anyLong(), any());
    }

    @Test
    @DisplayName("写库失败：删掉刚写的新文件再抛出（不留孤儿文件）")
    void dbFailureRemovesTheNewlyWrittenFile() {
        User user = User.builder().id(5L).nickname("张三").avatarUrl(OLD_URL).build();
        when(userRepository.findById(5L)).thenReturn(Optional.of(user));
        when(avatarStorageService.store(eq(5L), any())).thenReturn(NEW_URL);
        when(userRepository.saveAndFlush(user)).thenThrow(new RuntimeException("DB 挂了"));

        assertThrows(RuntimeException.class, () -> userService.uploadAvatar(5L, someFile()));

        verify(avatarStorageService).deleteQuietly(5L, NEW_URL);
        verify(avatarStorageService, never()).deleteQuietly(5L, OLD_URL);
        verify(redisCacheService, never()).delete(any());
    }

    /**
     * 在「事务同步已激活」的上下文里跑一段逻辑，并在结束时触发 afterCommit。
     * <p>
     * 生产代码用的是 {@code TransactionSynchronizationManager} 注册延迟回调，
     * 纯单测没有 Spring 事务，不同步激活的话那个回调根本不会注册，
     * 于是"缓存到底删没删"就测不到 —— 而那恰恰是换头像后最容易出问题的一步。
     */
    private static <T> T runInTransaction(java.util.function.Supplier<T> action) {
        TransactionSynchronizationManager.initSynchronization();
        try {
            T result = action.get();
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);
            return result;
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("读取头像走库里记录的路径；用户不存在时返回 null（Controller 转 404）")
    void loadAvatarUsesStoredPathOnly() {
        User user = User.builder().id(5L).avatarUrl(NEW_URL).build();
        when(userRepository.findById(5L)).thenReturn(Optional.of(user));
        when(avatarStorageService.load(5L, NEW_URL)).thenReturn(new byte[]{9});

        assertEquals(1, userService.loadAvatar(5L).length);

        when(userRepository.findById(6L)).thenReturn(Optional.empty());
        org.junit.jupiter.api.Assertions.assertNull(userService.loadAvatar(6L));
        verify(avatarStorageService, times(1)).load(anyLong(), any());
    }
}

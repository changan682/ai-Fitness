package com.fitness.service;

import at.favre.lib.crypto.bcrypt.BCrypt;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitness.cache.CacheKeys;
import com.fitness.cache.RedisCacheService;
import com.fitness.cache.TokenBlacklistService;
import com.fitness.config.JwtProperties;
import com.fitness.dto.*;
import com.fitness.entity.User;
import com.fitness.exception.BusinessException;
import com.fitness.exception.ErrorCode;
import com.fitness.repository.UserRepository;
import com.fitness.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 用户服务 — 注册、登录、档案管理、密码修改、登出、Token 刷新
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    /** BCrypt 哈希强度 — cost=12 兼顾安全与性能 */
    private static final int BCRYPT_COST = 12;

    /** 允许刷新 Token 的时间窗：过期前 24 小时内（规范 1.7） */
    private static final long REFRESH_WINDOW_MS = 24 * 3600_000L;

    private static final DateTimeFormatter DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final JwtProperties jwtProperties;
    private final RedisCacheService redisCacheService;
    private final TokenBlacklistService tokenBlacklistService;
    private final ObjectMapper objectMapper;

    // ==================== 注册 ====================

    /**
     * 用户注册
     * 密码使用 BCrypt 加密存储，明文密码不落盘、不打印日志（日志仅打印脱敏手机号）
     */
    @Transactional
    public RegisterResponse register(RegisterRequest req) {
        // 检查手机号是否已注册
        if (userRepository.existsByPhone(req.getPhone())) {
            throw new BusinessException(ErrorCode.PHONE_REGISTERED);
        }

        User user = User.builder()
                .nickname(req.getNickname())
                .phone(req.getPhone())
                .password(BCrypt.withDefaults().hashToString(BCRYPT_COST, req.getPassword().toCharArray()))
                .gender(req.getGender())
                .height(toDecimal(req.getHeight()))
                .weight(toDecimal(req.getWeight()))
                .trainingGoal(req.getTrainingGoal())
                .trainingLevel(req.getTrainingLevel())
                .build();

        if (req.getBirthDate() != null) {
            user.setBirthDate(LocalDate.parse(req.getBirthDate()));
        }

        user = userRepository.save(user);
        log.info("用户注册成功: userId={}, phone={}", user.getId(), maskPhone(req.getPhone()));

        // 规范 1.1：注册成功返回 { id, nickname, phone(脱敏) }
        return RegisterResponse.builder()
                .id(user.getId())
                .nickname(user.getNickname())
                .phone(maskPhone(user.getPhone()))
                .build();
    }

    // ==================== 登录 ====================

    /**
     * 用户登录
     * 验证手机号+密码，返回 JWT Token 和用户基本信息
     */
    public LoginResponse login(LoginRequest req) {
        User user = userRepository.findByPhone(req.getPhone())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        // BCrypt 密码校验
        if (!BCrypt.verifyer().verify(req.getPassword().toCharArray(), user.getPassword()).verified) {
            throw new BusinessException(ErrorCode.PASSWORD_ERROR);
        }

        String token = jwtUtil.generateToken(user.getId(), user.getPhone());

        log.info("用户登录成功: userId={}", user.getId());
        return LoginResponse.builder()
                .token(token)
                .expiresAt(LocalDateTime.now()
                        .plusDays(jwtProperties.getExpirationDays()).format(DATE_TIME_FORMAT))
                .user(toUserBrief(user))
                .build();
    }

    // ==================== 档案查询 ====================

    /**
     * 查询个人档案 — Cache-Aside（旁路缓存，TTL 30分钟，叠加 ±300s 扰动）
     * <p>
     * 读取：先查 Redis Hash → 命中直接返回 → 未命中查 MySQL → 写入 Redis。
     * 更新（改档案/改密码）后由 {@link #evictProfileCache} 删除缓存。
     */
    public UserProfileResponse getProfile(Long userId) {
        String cacheKey = CacheKeys.userProfile(userId);

        // 1. 先查缓存
        Object cached = redisCacheService.hGet(cacheKey, CacheKeys.USER_PROFILE_FIELD);
        if (cached instanceof UserProfileResponse cachedProfile) {
            log.debug("用户档案缓存命中: userId={}", userId);
            return cachedProfile;
        }

        // 2. 未命中查 DB
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        UserProfileResponse response = toProfileResponse(user);

        // 3. 写入缓存（Hash + 带扰动的 TTL）
        redisCacheService.hSet(cacheKey, CacheKeys.USER_PROFILE_FIELD, response);
        redisCacheService.expireWithJitter(cacheKey, CacheKeys.USER_PROFILE_TTL_SECONDS);
        log.debug("用户档案缓存写入: userId={}", userId);
        return response;
    }

    // ==================== 档案修改 ====================

    /** 修改个人档案 — 传了才更新，不传保持原值 */
    @Transactional
    public void updateProfile(Long userId, UpdateProfileRequest req) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        if (req.getNickname() != null) user.setNickname(req.getNickname());
        if (req.getGender() != null) user.setGender(req.getGender());
        if (req.getBirthDate() != null && !req.getBirthDate().isBlank()) {
            user.setBirthDate(LocalDate.parse(req.getBirthDate()));
        }
        if (req.getHeight() != null) user.setHeight(toDecimal(req.getHeight()));
        if (req.getWeight() != null) user.setWeight(toDecimal(req.getWeight()));
        if (req.getTrainingGoal() != null) user.setTrainingGoal(req.getTrainingGoal());
        if (req.getTrainingLevel() != null) user.setTrainingLevel(req.getTrainingLevel());
        if (req.getInjuryRecord() != null) {
            // 规范 1.4 入参为字符串数组，实体按 DDL 注释存 JSON 数组字符串
            user.setInjuryRecord(writeInjuryRecord(req.getInjuryRecord()));
        }

        userRepository.save(user);

        // 延迟双删 第1步：写库后立即删（快速收敛）
        evictProfileCache(userId);
        // 延迟双删 第2步：事务提交后再删一次，清掉「写库期间被并发读回填」的旧值
        evictProfileCacheAfterCommit(userId);
        log.info("用户档案更新: userId={}", userId);
    }

    // ==================== 密码修改 ====================

    /** 修改密码 — 需校验旧密码，修改后将当前 Token 加入黑名单 */
    @Transactional
    public void changePassword(Long userId, String token, ChangePasswordRequest req) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        // 校验旧密码 — 规范 1.5 要求 msg 为「旧密码错误」（码仍为 1002）
        if (!BCrypt.verifyer().verify(req.getOldPassword().toCharArray(), user.getPassword()).verified) {
            throw new BusinessException(ErrorCode.PASSWORD_ERROR, "旧密码错误");
        }

        user.setPassword(BCrypt.withDefaults().hashToString(BCRYPT_COST, req.getNewPassword().toCharArray()));
        userRepository.save(user);

        // 改密后：档案缓存失效 + 该用户「全部已签发 Token」立即失效。
        // 用 iat 水位线而不是只拉黑当前这一个 Token：改密码的安全语义是「所有设备重新登录」，
        // 只拉黑当前 Token 会让其它设备此前拿到的 Token 继续可用，等于改密没生效。
        evictProfileCache(userId);
        evictProfileCacheAfterCommit(userId);
        tokenBlacklistService.markAllTokensInvalidBefore(userId, System.currentTimeMillis());
        if (token != null) {
            // 顺手把当前 Token 也按 jti 拉黑：水位线判据是 iat < watermark，
            // 同一毫秒内签发的 Token 会落在等号边界上，这一步把边界补齐
            blacklistToken(token);
        }

        log.info("密码修改成功: userId={}", userId);
    }

    // ==================== Token 刷新 ====================

    /**
     * 刷新 JWT Token — 规范 1.7
     * <p>
     * 业务规则：仅当 Token 剩余有效期 &lt; 24 小时才允许刷新；
     * 刷新后旧 Token 立即加入黑名单（等价于「每个 Token 仅允许刷新 1 次」，
     * 因为旧 Token 再次请求会被黑名单拦下）。
     */
    public RefreshTokenResponse refreshToken(Long userId, String oldToken) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        long remainingMs = jwtUtil.getRemainingTime(oldToken);
        if (remainingMs > REFRESH_WINDOW_MS) {
            throw new BusinessException(ErrorCode.TOKEN_REFRESH_NOT_ALLOWED);
        }

        String newToken = jwtUtil.generateToken(user.getId(), user.getPhone());

        // 旧 Token 作废，防止同一 Token 被反复刷新
        blacklistToken(oldToken);

        log.info("Token 刷新成功: userId={}", userId);
        return RefreshTokenResponse.builder()
                .token(newToken)
                .expiresAt(LocalDateTime.now()
                        .plusDays(jwtProperties.getExpirationDays()).format(DATE_TIME_FORMAT))
                .build();
    }

    // ==================== Token 黑名单 ====================

    /**
     * 将 Token 加入 Redis 黑名单（按 jti 粒度），TTL 为 Token 剩余有效期
     * <p>
     * Key 为 {@code user:token:blacklist:{jti}}（见 {@link TokenBlacklistService}）。
     * 以 jti 而非 userId 为 key，多设备登录时各 Token 互不覆盖。
     */
    public void blacklistToken(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        String jti;
        try {
            jti = jwtUtil.getJti(token);
        } catch (Exception e) {
            // Token 解析失败（非法/已过期）时无需拉黑：它本来就通不过鉴权
            log.warn("Token 无法解析 jti，跳过黑名单写入: {}", e.getMessage());
            return;
        }
        if (jti == null || jti.isBlank()) {
            log.warn("Token 缺少 jti claim，无法加入黑名单（JwtInterceptor 会直接拒绝无 jti 的 Token）");
            return;
        }
        tokenBlacklistService.blacklist(jti, jwtUtil.getRemainingTime(token));
    }

    // ==================== 工具方法 ====================

    /** 删除用户档案缓存（档案修改/密码修改成功后调用） */
    private void evictProfileCache(Long userId) {
        redisCacheService.delete(CacheKeys.userProfile(userId));
        log.debug("用户档案缓存已删除: userId={}", userId);
    }

    /**
     * 事务提交后再删一次缓存（延迟双删的第 2 步）
     * <p>
     * 原因：事务未提交时并发请求仍可能读到旧值并回填缓存，
     * 只在写库后删一次会留下脏缓存；提交后再删可覆盖这个窗口。
     */
    private void evictProfileCacheAfterCommit(Long userId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evictProfileCache(userId);
            }
        });
    }

    /** 将 User 实体转为 Profile 响应（手机号脱敏、伤病记录转数组） */
    private UserProfileResponse toProfileResponse(User user) {
        return UserProfileResponse.builder()
                .id(user.getId())
                .nickname(user.getNickname())
                .gender(user.getGender())
                .birthDate(user.getBirthDate() != null ? user.getBirthDate().toString() : null)
                .height(user.getHeight())
                .weight(user.getWeight())
                .trainingGoal(user.getTrainingGoal())
                .trainingLevel(user.getTrainingLevel())
                .injuryRecord(parseInjuryRecord(user.getInjuryRecord()))
                .phone(maskPhone(user.getPhone()))
                .createdAt(user.getCreatedAt())
                .build();
    }

    private LoginResponse.UserBrief toUserBrief(User user) {
        return LoginResponse.UserBrief.builder()
                .id(user.getId())
                .nickname(user.getNickname())
                .gender(user.getGender())
                .trainingGoal(user.getTrainingGoal())
                .build();
    }

    /** 字符串数组 → JSON 数组字符串（实体 injury_record 为 TEXT） */
    private String writeInjuryRecord(List<String> injuryRecord) {
        try {
            return objectMapper.writeValueAsString(injuryRecord);
        } catch (Exception e) {
            log.warn("伤病记录序列化失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * JSON 数组字符串 → 字符串数组；兼容非 JSON 的历史数据
     * <p>
     * 注意返回 {@code new ArrayList<>()} 而不是 {@code List.of()}：
     * 该结果会随 UserProfileResponse 一起写入 Redis，而 GenericJackson2JsonRedisSerializer
     * 会写入 {@code @class}；JDK 不可变集合（ImmutableCollections$ListN）无法被反序列化，
     * 会导致下一次读档案缓存直接失败。
     */
    private List<String> parseInjuryRecord(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            List<String> fallback = new ArrayList<>();
            fallback.add(json);
            return fallback;
        }
    }

    /**
     * Double → BigDecimal 转换
     * <p>
     * 请求 DTO 用 Double（前端 JSON 数值天然是浮点），实体用 BigDecimal（DDL 为 DECIMAL(5,1)）。
     * 用 {@code BigDecimal.valueOf(double)} 而不是 {@code new BigDecimal(double)}：
     * 后者会把二进制浮点误差原样带进来（70.1 会变成 70.09999999999999…）。
     */
    private BigDecimal toDecimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    /** 手机号脱敏：138****8000 */
    private String maskPhone(String phone) {
        if (phone == null || phone.length() != 11) return phone;
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }
}

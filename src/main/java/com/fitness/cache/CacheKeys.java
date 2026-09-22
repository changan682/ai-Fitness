package com.fitness.cache;

import java.time.LocalDate;

/**
 * Redis 缓存 Key 统一管理
 * <p>
 * 强制要求（提示词第五章 1 + 第十一章第 8 条）：
 * <ul>
 *   <li>所有<b>缓存</b> Key 必须使用统一前缀 {@code fitness:cache:}，严禁在业务代码中硬编码裸 Key</li>
 *   <li>Key 模式必须与规范「缓存 Key 命名规范」表逐条一致</li>
 * </ul>
 * 注意：分布式锁（{@code lock:*}）不加 {@code fitness:cache:} 前缀 ——
 * 规范 Key 表把锁单独列为 {@code lock:scheduled:{taskName}} 形式，
 * 且锁是协调原语而非缓存数据，混入缓存命名空间会干扰缓存清理与统计。
 */
public final class CacheKeys {

    /** 全局统一前缀 */
    public static final String PREFIX = "fitness:cache:";

    // ==================== 缓存 Key 前缀 ====================

    /** 用户档案缓存 — Hash（field=profile，value=UserProfileResponse JSON） */
    public static final String USER_PROFILE = PREFIX + "user:profile:";

    /**
     * JWT Token 黑名单 — String（value="1"，key 存在即代表该 Token 已登出）
     * <p>
     * <b>key 必须按 jti（Token 唯一 ID）而不是 userId</b>：同一用户多设备登录时，
     * 以 userId 为 key 会让后登出的 Token 覆盖先登出的记录，导致先登出的 Token 又恢复可用（鉴权漏洞）。
     */
    public static final String USER_TOKEN_BLACKLIST = PREFIX + "user:token:blacklist:";

    /**
     * 用户级 Token 失效水位线 — String（value=epoch 毫秒时间戳）
     * <p>
     * 用于「改密码 / 强制下线」这类需要一次作废该用户<b>所有</b>已签发 Token 的场景：
     * 服务端无法枚举该用户全部设备的 jti，因此改为记录一个时间水位线，
     * 鉴权时只要 Token 的 iat 早于该水位线就拒绝。
     */
    public static final String USER_TOKEN_INVALID_AFTER = PREFIX + "user:token:invalid-after:";

    /** 今日训练记录缓存 — List（JSON数组，Dashboard 高频读取） */
    public static final String TRAINING_TODAY = PREFIX + "training:today:";

    /** 最新体测数据缓存 — Hash（weight_kg/waist_cm/... 六字段 + id/created_at） */
    public static final String METRIC_LATEST = PREFIX + "metric:latest:";

    /** 7日滑动均值缓存 — String（JSON） */
    public static final String METRIC_AVG7D = PREFIX + "metric:avg7d:";

    /** 食物热量库缓存 — Hash（field=food_name，value=每100g热量） */
    public static final String FOOD_LIBRARY_ALL = PREFIX + "food:library:all";

    /** 训练计划模板列表缓存 — String（JSON数组，含模板下全部动作明细） */
    public static final String WORKOUT_TEMPLATE_ALL = PREFIX + "workout:template:all";

    /** 本周训练统计缓存 — String（JSON） */
    public static final String STATS_WEEKLY = PREFIX + "stats:weekly:";

    /** AI每日总结缓存 — String（Markdown 文本），第4周启用 */
    public static final String AI_SUMMARY = PREFIX + "ai:summary:";

    /** AI动作推荐缓存 — String（JSON），第4周启用 */
    public static final String AI_RECOMMEND = PREFIX + "ai:recommend:";

    /**
     * AI 问答会话热层 — String（JSON 数组，元素为 {@code {role, content}}）
     * <p>
     * 只存"送模型的上下文窗口"（最近 6 轮），长期历史落在 {@code t_ai_chat_history} 表里。
     * key 必须带 userId：会话归属靠它把关 —— 只按 sessionId 存取的话，
     * 猜到/拿到别人 sessionId 的人就能续上别人的对话。
     */
    public static final String AI_CHAT_SESSION = PREFIX + "ai:chat:session:";

    /**
     * 身体状态主动问询缓存 — String（JSON：响应 + 快照指纹）
     * <p>
     * key 只按 userId 维度（同一时刻只有"最新一份问询"有意义）；
     * 值里带指纹，指纹变了就重新生成 —— 否则用户新记了一条体测却仍看到旧结论。
     */
    public static final String AI_BODY_CONSULT = PREFIX + "ai:body:consult:";

    // ==================== 分布式锁 Key 前缀（规范 3.1 / 3.2） ====================

    /** 定时任务防重锁 */
    public static final String LOCK_SCHEDULED = "lock:scheduled:";

    /** 周计划回调幂等锁 */
    public static final String LOCK_WEEKLY_PLAN = "lock:weekly:plan:";

    /** AI总结缓存击穿互斥锁 */
    public static final String LOCK_AI_SUMMARY = "lock:ai:summary:";

    /** 本周统计回源互斥锁（防热点 key 击穿；规范未单列，沿用 lock:* 命名风格） */
    public static final String LOCK_STATS_WEEKLY = "lock:stats:weekly:";

    // ==================== Hash field 常量 ====================

    /** 用户档案 Hash 的固定 field */
    public static final String USER_PROFILE_FIELD = "profile";

    // ==================== TTL 常量（秒） ====================

    /** 用户档案缓存 TTL = 30 分钟（规范 2.1） */
    public static final long USER_PROFILE_TTL_SECONDS = 1800;

    /** 7日滑动均值缓存 TTL = 1 小时（规范 2.5） */
    public static final long METRIC_AVG7D_TTL_SECONDS = 3600;

    /** 本周训练统计缓存 TTL = 1 小时（规范 2.10） */
    public static final long STATS_WEEKLY_TTL_SECONDS = 3600;

    /**
     * Token 失效水位线 TTL = 8 天（秒）
     * <p>
     * 比 JWT 最长有效期（jwt.expiration-days，默认 7 天）多 1 天即可：
     * 水位线只需要「活到所有比它早签发的 Token 都自然过期」为止，之后自动清理，无需永久驻留。
     */
    public static final long TOKEN_INVALID_AFTER_TTL_SECONDS = 8L * 24 * 3600;

    /**
     * 最新体测数据 TTL = 30 天（规范 2.4 写「永久，手动更新」）
     * <p>
     * 与强约束第 8 条「TTL 不得设置为永不过期（除食物库和模板数据外）」冲突，
     * 取折中：写穿透时刷新 + 30 天长 TTL，既避免 key 永久驻留，也不会自然过期丢失热度。
     */
    public static final long METRIC_LATEST_TTL_SECONDS = 30L * 24 * 3600;

    /** 食物热量库 TTL = 24 小时（规范 2.6） */
    public static final long FOOD_LIBRARY_TTL_SECONDS = 86400;

    /** 训练计划模板 TTL = 24 小时（规范 2.7） */
    public static final long WORKOUT_TEMPLATE_TTL_SECONDS = 86400;

    /**
     * AI 问答会话热层 TTL = 2 小时
     * <p>
     * 取值理由：一场连续提问不会超过两小时，而 key 留在 Redis 里越久越占内存；
     * 过期并不等于"对话丢了"—— 热层未命中会从 {@code t_ai_chat_history} 回填最近 6 轮
     * （见 {@code AiChatSessionService#load}）。
     */
    public static final long AI_CHAT_SESSION_TTL_SECONDS = 7200;

    /**
     * 身体状态问询缓存 TTL = 12 小时
     * <p>
     * 比 AI 总结（24h）短、比会话（2h）长：体测数据一天最多变几次，
     * 12 小时足够覆盖"用户反复打开页面看同一份结论"的场景，又不至于让结论明显过期。
     * 真正的时效性由指纹保证（数据一变就重新生成），TTL 只是兜底清理。
     */
    public static final long AI_BODY_CONSULT_TTL_SECONDS = 12 * 3600L;

    // ==================== Key 构建方法（避免业务代码拼接裸 Key） ====================

    public static String userProfile(Long userId) {
        return USER_PROFILE + userId;
    }

    /** JWT 黑名单：user:token:blacklist:{jti} */
    public static String userTokenBlacklist(String jti) {
        return USER_TOKEN_BLACKLIST + jti;
    }

    /** Token 失效水位线：user:token:invalid-after:{userId} */
    public static String userTokenInvalidAfter(Long userId) {
        return USER_TOKEN_INVALID_AFTER + userId;
    }

    /** 今日训练记录：training:today:{userId}:{date} */
    public static String trainingToday(Long userId, LocalDate date) {
        return TRAINING_TODAY + userId + ":" + date;
    }

    public static String metricLatest(Long userId) {
        return METRIC_LATEST + userId;
    }

    public static String metricAvg7d(Long userId) {
        return METRIC_AVG7D + userId;
    }

    /** 本周训练统计：stats:weekly:{userId}:{weekStart} */
    public static String statsWeekly(Long userId, LocalDate weekStart) {
        return STATS_WEEKLY + userId + ":" + weekStart;
    }

    /** 定时任务锁：lock:scheduled:{taskName} */
    public static String lockScheduled(String taskName) {
        return LOCK_SCHEDULED + taskName;
    }

    /** AI总结击穿互斥锁：lock:ai:summary:{userId}:{date} */
    public static String lockAiSummary(Long userId, LocalDate date) {
        return LOCK_AI_SUMMARY + userId + ":" + date;
    }

    /**
     * 周计划回调幂等锁：{@code lock:weekly:plan:callback:{taskId}}
     * <p>
     * 以 taskId（而不是 userId+weekStart）为粒度：同一周可能因为重投/重试被回调多次，
     * 幂等键必须与 MQ 消息一一对应，才能准确识别「同一条消息的重复投递」。
     */
    public static String lockWeeklyPlanCallback(String taskId) {
        return LOCK_WEEKLY_PLAN + "callback:" + taskId;
    }

    /** 本周统计回源锁：lock:stats:weekly:{userId}:{weekStart} */
    public static String lockStatsWeekly(Long userId, LocalDate weekStart) {
        return LOCK_STATS_WEEKLY + userId + ":" + weekStart;
    }

    /** AI 问答会话热层：ai:chat:session:{userId}:{sessionId} */
    public static String aiChatSession(Long userId, String sessionId) {
        return AI_CHAT_SESSION + userId + ":" + sessionId;
    }

    /** 身体状态问询缓存：ai:body:consult:{userId} */
    public static String aiBodyConsult(Long userId) {
        return AI_BODY_CONSULT + userId;
    }

    private CacheKeys() {
        // 工具类，禁止实例化
    }
}

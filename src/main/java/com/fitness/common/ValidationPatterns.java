package com.fitness.common;

/**
 * 全局参数校验正则 — 集中管理，避免各 DTO 重复硬编码导致规则漂移
 */
public final class ValidationPatterns {

    /**
     * 密码复杂度（规范 1.1 / 1.5）：8-32 位，必须同时包含小写字母、大写字母和数字。
     * <p>
     * 用 {@code \S} 限定不含空白字符；三条 lookahead 分别断言三类字符各至少出现一次。
     */
    public static final String PASSWORD = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)\\S{8,32}$";

    /** 密码复杂度提示文案 */
    public static final String PASSWORD_MESSAGE = "密码需8-32位且同时包含大小写字母和数字";

    /** 日期 yyyy-MM-dd */
    public static final String DATE = "^\\d{4}-\\d{2}-\\d{2}$";

    /** 日期 yyyy-MM-dd 或空串 */
    public static final String DATE_OR_EMPTY = "^(\\d{4}-\\d{2}-\\d{2})?$";

    /** 中国大陆手机号 */
    public static final String PHONE = "^1[3-9]\\d{9}$";

    private ValidationPatterns() {
        // 工具类，禁止实例化
    }
}

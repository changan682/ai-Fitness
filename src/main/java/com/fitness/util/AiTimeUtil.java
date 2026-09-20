package com.fitness.util;

import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * AI 时间格式转换工具
 * <p>
 * 为什么需要它：Python 的 {@code datetime} 经 Pydantic 序列化后是 ISO8601
 * （如 {@code 2026-07-30T15:35:00} 或带微秒/时区的 {@code 2026-07-30T15:35:00.123456+08:00}），
 * 而本项目 Java 侧全局把 LocalDateTime 约定为 {@code yyyy-MM-dd HH:mm:ss}。
 * 若直接把这些字段声明成 LocalDateTime，反序列化就会失败，
 * 因此协议层统一用 String 承接，再由这里转换成本地时间返回给前端。
 */
@Slf4j
public final class AiTimeUtil {

    private AiTimeUtil() {
        // 工具类，禁止实例化
    }

    /**
     * 宽松解析 ISO8601 时间串
     * <p>
     * 依次尝试「带时区偏移」→「不带时区」，都失败则退化为当前时间：
     * {@code generatedAt} 语义就是「这份结果刚生成」，回退成 now() 与事实相符，
     * 且能让前端始终拿到非空时间，不必为格式异常分支加空值判断。
     */
    public static LocalDateTime parseIsoOrNow(String iso) {
        if (iso == null || iso.isBlank()) {
            return LocalDateTime.now();
        }
        try {
            return OffsetDateTime.parse(iso).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // 继续尝试无时区格式
        }
        try {
            return LocalDateTime.parse(iso);
        } catch (DateTimeParseException e) {
            log.warn("Python 返回的时间格式无法解析，回退为当前时间: value={}", iso);
            return LocalDateTime.now();
        }
    }
}

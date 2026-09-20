package com.fitness.config;

import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Jackson 全局配置 — 统一日期/日期时间格式
 * <p>
 * 规范「通用约定」要求：
 * <ul>
 *   <li>日期统一 {@code yyyy-MM-dd}（如 2026-07-30）</li>
 *   <li>日期时间统一 {@code yyyy-MM-dd HH:mm:ss}（如 2026-07-30 14:30:00）</li>
 * </ul>
 * 注意：{@code spring.jackson.date-format} 只对 java.util.Date 生效，
 * 对 JSR-310 的 LocalDate/LocalDateTime 无效，因此必须显式注册序列化器，
 * 否则默认会输出 ISO 格式 "2026-07-30T14:30:00"，与前端约定的格式不一致。
 */
@Configuration
public class JacksonConfig {

    /** 日期格式：yyyy-MM-dd */
    public static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 日期时间格式：yyyy-MM-dd HH:mm:ss */
    public static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer javaTimeFormatCustomizer() {
        return builder -> {
            SimpleModule module = new SimpleModule("fitness-java-time");
            module.addSerializer(LocalDate.class, new LocalDateSerializer(DATE_FORMATTER));
            module.addDeserializer(LocalDate.class, new LocalDateDeserializer(DATE_FORMATTER));
            module.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(DATE_TIME_FORMATTER));
            module.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(DATE_TIME_FORMATTER));
            // 用 modulesToInstall 而非 modules：前者是「追加」，后者会覆盖 Spring Boot
            // 自动发现的 JavaTimeModule 等模块，导致 JSR-310 支持整体失效。
            builder.modulesToInstall(module);
        };
    }
}

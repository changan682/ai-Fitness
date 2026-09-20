package com.fitness.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitness.dto.UserProfileResponse;
import com.fitness.entity.TrainingRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Redis 缓存序列化往返测试
 * <p>
 * <b>为什么必须单独测这一层</b>：项目原先 123 个测试全绿，但真实启动后
 * {@code GET /api/v1/user/profile} 直接返回 9999 —— 原因是
 * {@code new GenericJackson2JsonRedisSerializer()} 的内置 ObjectMapper
 * 没有注册 JavaTimeModule，序列化带 {@code LocalDateTime} 的 DTO 时抛
 * {@code SerializationException}。
 * <p>
 * 为什么既有测试抓不到：{@code RedisCacheIntegrationTest} 把 {@code RedisTemplate}
 * mock 成了内存 Map，<b>mock 不会真的执行 Jackson 序列化</b>，
 * 于是整条序列化链路从未被验证过。这个测试直接拿 {@link RedisConfig} 产出的
 * 真实序列化器做字节级往返，把这一环锁死。
 * <p>
 * 它是纯单元测试：只需要一个假的 RedisConnectionFactory（不会真的连 Redis），
 * 因此可以纳入常规构建。
 */
@DisplayName("Redis 缓存序列化：带 java.time 字段的 DTO 必须能往返")
class RedisSerializerRoundTripTest {

    /** 取得 RedisConfig 为 Value 配置的真实序列化器 */
    private RedisSerializer<Object> valueSerializer() {
        // 复刻 Spring Boot 的行为：Jackson2ObjectMapperBuilder 会自动注册 JavaTimeModule，
        // 再叠加项目自己的 JacksonConfig（yyyy-MM-dd HH:mm:ss）
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new JacksonConfig().javaTimeFormatCustomizer().customize(builder);
        ObjectMapper springObjectMapper = builder.build();

        RedisConfig redisConfig = new RedisConfig();
        RedisTemplate<String, Object> template =
                redisConfig.redisTemplate(mock(RedisConnectionFactory.class), springObjectMapper);

        RedisSerializer<?> serializer = template.getValueSerializer();
        assertNotNull(serializer, "RedisTemplate 必须配置 Value 序列化器");
        @SuppressWarnings("unchecked")
        RedisSerializer<Object> cast = (RedisSerializer<Object>) serializer;
        return cast;
    }

    @SuppressWarnings("unchecked")
    private <T> T roundTrip(T value) {
        RedisSerializer<Object> serializer = valueSerializer();
        byte[] bytes = serializer.serialize(value);
        assertNotNull(bytes, "序列化结果不应为 null");
        return (T) serializer.deserialize(bytes);
    }

    @Test
    @DisplayName("UserProfileResponse（含 LocalDateTime + BigDecimal + List）— 就是线上炸掉的那个")
    void userProfileResponseShouldRoundTrip() {
        List<String> injuries = new ArrayList<>();
        injuries.add("左肩旧伤");

        UserProfileResponse original = UserProfileResponse.builder()
                .id(1L)
                .nickname("环境验证")
                .gender(1)
                .birthDate("1995-06-15")
                .height(new BigDecimal("175.5"))
                .weight(new BigDecimal("70.2"))
                .trainingGoal("增肌")
                .trainingLevel("进阶")
                .injuryRecord(injuries)
                .phone("138****8000")
                .createdAt(LocalDateTime.of(2026, 9, 14, 18, 39, 37))
                .build();

        UserProfileResponse restored = roundTrip(original);

        assertNotNull(restored);
        assertEquals(original.getId(), restored.getId());
        assertEquals(original.getNickname(), restored.getNickname());
        assertEquals(original.getCreatedAt(), restored.getCreatedAt(), "LocalDateTime 必须无损还原");
        assertEquals(0, original.getHeight().compareTo(restored.getHeight()));
        assertEquals(original.getInjuryRecord(), restored.getInjuryRecord());
    }

    @Test
    @DisplayName("TrainingRecord（含 LocalDate）— training:today 缓存的同类隐患")
    void trainingRecordShouldRoundTrip() {
        TrainingRecord original = TrainingRecord.builder()
                .id(2001L)
                .userId(1L)
                .trainingDate(LocalDate.of(2026, 7, 30))
                .actionName("杠铃卧推")
                .sets(4).reps(10)
                .weightKg(new BigDecimal("60.0"))
                .volume(new BigDecimal("2400.0"))
                .durationMin(45).rpe(8)
                .remark("最后一组力竭")
                .createdAt(LocalDateTime.of(2026, 7, 30, 15, 30, 0))
                .build();

        TrainingRecord restored = roundTrip(original);

        assertNotNull(restored);
        assertEquals(original.getTrainingDate(), restored.getTrainingDate(), "LocalDate 必须无损还原");
        assertEquals(original.getCreatedAt(), restored.getCreatedAt());
        assertEquals(original.getActionName(), restored.getActionName());
        assertEquals(0, original.getVolume().compareTo(restored.getVolume()));
    }

    @Test
    @DisplayName("周统计那样的嵌套 Map 结构应能往返（StatsService 缓存的就是它）")
    void nestedMapShouldRoundTrip() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("weekStart", "2026-08-03");
        stats.put("trainingDays", 5);
        stats.put("totalVolume", new BigDecimal("18500.5"));
        stats.put("avgRpe", null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("weightChange", new BigDecimal("-0.3"));
        stats.put("bodyMetrics", body);
        List<Map<String, Object>> top = new ArrayList<>();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("actionName", "杠铃卧推");
        item.put("totalVolume", new BigDecimal("5200.0"));
        item.put("count", 2);
        top.add(item);
        stats.put("topActions", top);

        Object restored = roundTrip(stats);

        assertInstanceOf(Map.class, restored);
        Map<?, ?> map = (Map<?, ?>) restored;
        assertEquals("2026-08-03", map.get("weekStart"));
        assertEquals(5, map.get("trainingDays"));
        assertEquals(0, new BigDecimal("18500.5").compareTo((BigDecimal) map.get("totalVolume")));
        assertInstanceOf(List.class, map.get("topActions"));
        assertEquals(1, ((List<?>) map.get("topActions")).size());
    }

    @Test
    @DisplayName("缓存里的时间格式应与接口输出一致（yyyy-MM-dd HH:mm:ss，不是 ISO）")
    void cachedTimeFormatShouldMatchApiContract() {
        UserProfileResponse value = UserProfileResponse.builder()
                .id(1L)
                .createdAt(LocalDateTime.of(2026, 9, 14, 18, 39, 37))
                .build();

        String json = new String(valueSerializer().serialize(value), StandardCharsets.UTF_8);

        assertTrue(json.contains("2026-09-14 18:39:37"),
                "缓存里的时间应用项目统一格式，实际 JSON: " + json);
        assertTrue(json.contains("@class"),
                "必须写入 @class，否则读缓存时无法还原具体类型");
    }
}

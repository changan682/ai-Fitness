package com.fitness.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Redis 配置 — 序列化策略 + 缓存管理器
 * <p>
 * 所有缓存 Key 通过 spring.cache.redis.key-prefix 统一添加前缀 fitness:cache:
 */
@Configuration
public class RedisConfig {

    /**
     * RedisTemplate — Key 用字符串、Value 用 JSON 序列化
     * <p>
     * ⚠️ 这里必须注入 Spring 容器里的 {@link ObjectMapper} 而不是直接
     * {@code new GenericJackson2JsonRedisSerializer()}：
     * 后者的内置 ObjectMapper <b>没有注册 JavaTimeModule</b>，导致任何带
     * {@code LocalDate}/{@code LocalDateTime} 字段的对象写缓存时直接抛
     * <pre>
     * SerializationException: Java 8 date/time type `java.time.LocalDateTime` not supported by default
     * </pre>
     * 受影响的不止档案缓存，还有 {@code training:today} 里的 TrainingRecord（含训练日期与创建时间）。
     * 用容器里的 ObjectMapper 还有额外好处：缓存里的时间格式与接口返回的时间格式
     * （JacksonConfig 配的 yyyy-MM-dd HH:mm:ss）自动保持一致。
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory,
                                                       ObjectMapper springObjectMapper) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        // Key 使用字符串序列化
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());

        GenericJackson2JsonRedisSerializer jsonSerializer = cacheJsonSerializer(springObjectMapper);
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }

    /**
     * 构造缓存专用 JSON 序列化器
     * <p>
     * 用 {@code copy()} 而不是直接改容器里的 ObjectMapper：
     * 下面的 default typing 会往 JSON 里写 {@code @class} 字段，
     * 绝不能污染 MVC 用来输出接口响应的那个实例。
     * <p>
     * 关于 {@code @class}（default typing）：缓存里存的是 {@code Object}，
     * 没有类型信息就还原不回具体类型，所以它是必需的；
     * 但这也是历史上的反序列化攻击面，因此用 {@link BasicPolymorphicTypeValidator}
     * 把可还原的类型限制在本项目包与几个安全的基础包内，
     * 而不是用放开一切的 LaissezFaireSubTypeValidator。
     */
    private GenericJackson2JsonRedisSerializer cacheJsonSerializer(ObjectMapper springObjectMapper) {
        ObjectMapper cacheMapper = springObjectMapper.copy();
        cacheMapper.activateDefaultTyping(
                cachePolymorphicTypeValidator(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY);
        return new GenericJackson2JsonRedisSerializer(cacheMapper);
    }

    /** 允许被 {@code @class} 还原的类型白名单 */
    private PolymorphicTypeValidator cachePolymorphicTypeValidator() {
        return BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.fitness.")       // 本项目 DTO / 实体
                .allowIfSubType("java.util.")         // ArrayList / LinkedHashMap
                .allowIfSubType("java.time.")         // LocalDate / LocalDateTime
                .allowIfSubType("java.math.")         // BigDecimal
                .build();
    }

    /**
     * Spring Cache 管理器 — 默认 TTL 30 分钟，不缓存 null
     * <p>
     * 注意：一旦自定义了 CacheManager，Spring Boot 的缓存自动配置就会退避，
     * application.yml 里 spring.cache.redis.* 将不再自动生效 ——
     * 因此这里显式把 key-prefix 接过来，保证「所有缓存 Key 统一带 fitness:cache: 前缀」
     * 这条强约束在 @Cacheable 场景下同样成立。
     * <p>
     * 关于 TTL 随机扰动（强约束第 8 条）：本项目的业务缓存全部走
     * {@link com.fitness.cache.RedisCacheService#setWithJitter} 手动管理，扰动在那条路径上已落实。
     * 这里的 {@code entryTtl} 是固定值，因为 Spring Cache 的 TTL 配置粒度是「每个 cache 一个固定值」，
     * 要做逐条扰动需要自定义 RedisCacheWriter 去改写每次 put 的 Duration。
     * 当前项目未使用任何 {@code @Cacheable}（该 Bean 是为满足规范的 RedisConfig 要求而保留），
     * 因此不引入这份额外复杂度；若将来启用 @Cacheable，请为各 cache 单独设定 TTL
     * 并注意补上扰动策略。
     */
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory factory,
                                     ObjectMapper springObjectMapper,
                                     @Value("${spring.cache.redis.key-prefix:fitness:cache:}") String keyPrefix) {
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(30))
                .disableCachingNullValues()
                .computePrefixWith(cacheName -> keyPrefix + cacheName + "::")
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                cacheJsonSerializer(springObjectMapper)));

        return RedisCacheManager.builder(factory)
                .cacheDefaults(config)
                .build();
    }
}

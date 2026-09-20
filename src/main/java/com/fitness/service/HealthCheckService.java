package com.fitness.service;

import com.fitness.client.AiPythonClient;
import com.fitness.dto.HealthResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 健康检查服务 — 提示词 10.1
 * <p>
 * 逐项探测 MySQL / Redis / RabbitMQ / Python Agent。
 * 每个探测都设有超时上限：某个中间件不可达时接口仍能快速返回，
 * 避免 healthcheck 因单个依赖阻塞而被 Docker/K8s 误判为「进程挂死」。
 * <p>
 * 总体状态判定：MySQL 与 Redis 是 BFF 的硬依赖，任一 DOWN 则整体 DOWN；
 * RabbitMQ（第7周启用）与 Python Agent（第4周启用）属于可降级依赖，
 * 单项 DOWN 不影响整体 UP，这样在没有 AI/MQ 的环境下也能正常联调。
 */
@Slf4j
@Service
public class HealthCheckService {

    /** 单项探测超时（毫秒）——健康检查必须比被检查的服务更快失败 */
    private static final long PROBE_TIMEOUT_MS = 1500L;

    private static final String UP = "UP";
    private static final String DOWN = "DOWN";

    private final DataSource dataSource;
    private final RedisConnectionFactory redisConnectionFactory;
    private final ConnectionFactory rabbitConnectionFactory;
    private final AiPythonClient aiPythonClient;

    public HealthCheckService(DataSource dataSource,
                              RedisConnectionFactory redisConnectionFactory,
                              ConnectionFactory rabbitConnectionFactory,
                              AiPythonClient aiPythonClient) {
        this.dataSource = dataSource;
        this.redisConnectionFactory = redisConnectionFactory;
        this.rabbitConnectionFactory = rabbitConnectionFactory;
        this.aiPythonClient = aiPythonClient;
    }

    /** 执行全部依赖探测并汇总 */
    public HealthResponse check() {
        Map<String, String> services = new LinkedHashMap<>();
        String mysql = probe("mysql", this::probeMysql);
        String redis = probe("redis", this::probeRedis);
        String rabbitmq = probe("rabbitmq", this::probeRabbitmq);
        String pythonAgent = probe("pythonAgent", this::probePythonAgent);

        services.put("mysql", mysql);
        services.put("redis", redis);
        services.put("rabbitmq", rabbitmq);
        services.put("pythonAgent", pythonAgent);

        String overall = (UP.equals(mysql) && UP.equals(redis)) ? UP : DOWN;

        return HealthResponse.builder()
                .status(overall)
                .timestamp(LocalDateTime.now())
                .services(services)
                .build();
    }

    /** 统一探测包装：限时执行 + 异常吞掉，任何失败都只体现为该服务 DOWN */
    private String probe(String name, Supplier<Boolean> check) {
        try {
            Boolean ok = CompletableFuture.supplyAsync(check)
                    .get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return Boolean.TRUE.equals(ok) ? UP : DOWN;
        } catch (Exception e) {
            log.debug("健康检查探测失败: service={}, reason={}", name, e.toString());
            return DOWN;
        }
    }

    private boolean probeMysql() {
        try (Connection conn = dataSource.getConnection()) {
            return conn.isValid(2);
        } catch (Exception e) {
            log.warn("MySQL 健康检查失败: {}", e.getMessage());
            return false;
        }
    }

    private boolean probeRedis() {
        try {
            String pong = redisConnectionFactory.getConnection().ping();
            return pong != null;
        } catch (Exception e) {
            log.warn("Redis 健康检查失败: {}", e.getMessage());
            return false;
        }
    }

    private boolean probeRabbitmq() {
        try (org.springframework.amqp.rabbit.connection.Connection conn =
                     rabbitConnectionFactory.createConnection()) {
            return conn.isOpen();
        } catch (Exception e) {
            log.warn("RabbitMQ 健康检查失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Python Agent 健康探测 — 复用业务用的 {@link AiPythonClient}
     * <p>
     * 不另起一套 HttpClient：统一走 RestClientConfig 的 baseUrl 与超时配置，
     * 否则「健康检查说 UP、业务调用却超时」这种配置漂移会很难排查。
     * Python 未启动时返回 DOWN，属第 4 周联调期的预期状态。
     */
    private boolean probePythonAgent() {
        return aiPythonClient.health() != null;
    }
}

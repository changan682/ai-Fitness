package com.fitness.config;

import com.fitness.util.TraceIdUtil;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * RestClient 配置 — Java → Python 的跨语言 HTTP 客户端（规范第 922 行「RestClientConfig」）
 * <p>
 * 三个关键点：
 * <ol>
 *   <li><b>超时</b>：连接 3s / 读取 30s。没有超时的 HTTP 客户端是线上事故的常见来源 ——
 *       Python 或大模型卡住会把 Tomcat 工作线程一起拖死。</li>
 *   <li><b>连接复用</b>：底层用 JDK {@link HttpClient}，自带连接池，避免每请求三次握手；
 *       显式开启 HTTP/1.1（默认即支持 HTTP/2，但服务端 Uvicorn 通常只跑 1.1）。</li>
 *   <li><b>traceId 透传</b>：请求拦截器从 MDC 取 traceId 注入 {@code X-Trace-Id}，
 *       与规范第十章的跨语言链路要求一致（React → Java → Python 同一条 traceId）。</li>
 * </ol>
 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class RestClientConfig {

    /** Python 服务调用专用的 RestClient（带 baseUrl，业务层只写相对路径） */
    @Bean
    public RestClient aiRestClient(AiProperties aiProperties) {
        return buildRestClient(aiProperties, aiProperties.getPython().getReadTimeout());
    }

    /**
     * Agent 循环专用 RestClient —— 读取超时更长（默认 90s）。
     * <p>
     * 为什么不复用 {@link #aiRestClient}：一次 Agent 回答含多轮模型 + 工具往返，
     * 用普通的 30s 会先超时，用户看到「AI 服务超时」而其实只是"还在跑"。
     * 拆成两个 Bean 比放宽全局超时更安全 —— 普通接口仍保持快速失败。
     */
    @Bean
    public RestClient aiAgentRestClient(AiProperties aiProperties) {
        return buildRestClient(aiProperties, aiProperties.getPython().getAgentReadTimeout());
    }

    private RestClient buildRestClient(AiProperties aiProperties, Duration readTimeout) {
        AiProperties.Python python = aiProperties.getPython();

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(python.getConnectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);

        return RestClient.builder()
                .baseUrl(python.getBaseUrl())
                .requestFactory(requestFactory)
                .requestInterceptor((request, body, execution) -> {
                    // 无 traceId 时生成一个并写入 MDC，保证 Java/Python 两段日志能串起来
                    request.getHeaders().set(TraceIdUtil.TRACE_ID_HEADER, TraceIdUtil.currentOrCreate());
                    return execution.execute(request, body);
                })
                .build();
    }
}

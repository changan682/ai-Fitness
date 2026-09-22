package com.fitness.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * AI 服务配置属性 — 对应 application.yml 的 {@code ai.*}
 * <p>
 * 包含 Python FastAPI 服务的地址/超时，以及调用失败时的兜底文案（规范第十一章第 7 条
 * 「AI 降级方案」：Python 超时或报错时必须返回预设文案，不能让前端白屏）。
 */
@Data
@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    /** Python FastAPI 服务 */
    private Python python = new Python();

    /** 降级兜底文案 */
    private Fallback fallback = new Fallback();

    @Data
    public static class Python {

        /** Python 服务基址，如 http://localhost:8000 */
        private String baseUrl = "http://localhost:8000";

        /** 建立连接超时（规范建议 3s：连不上要快速失败，避免请求线程被占满） */
        private Duration connectTimeout = Duration.ofSeconds(3);

        /** 读取超时（规范建议 30s：大模型生成较慢，但必须有上限） */
        private Duration readTimeout = Duration.ofSeconds(30);
    }

    @Data
    public static class Fallback {

        /** 训练总结兜底文案（规范 7.1 错误响应示例） */
        private String summary = "AI 教练暂时走神了，请稍后再试 😅";

        /** 知识库问答兜底文案（规范 7.4 错误响应示例） */
        private String chat = "知识库暂时不可用，正在紧急恢复中 🛠️";

        /** 姿态评估兜底文案（规范 7.3 错误响应示例） */
        private String pose = "姿态评估服务暂时不可用，请稍后再试";

        /** 动作推荐兜底文案 */
        private String recommend = "AI 教练暂时走神了，请稍后再试 😅";

        /**
         * 身体状态问询兜底文案（体验优化批次 D）
         * <p>
         * 刻意写成"陈述事实"而不是"稍后再试"：这个功能是 AI 主动关心用户，
         * 挂掉时最好的表现是把页面上本来就有的数据如实复述一遍，而不是让用户白等。
         */
        private String consult = "AI 教练暂时不在线，稍后再点一次即可（下面的数据摘要来自你自己的记录）";
    }
}

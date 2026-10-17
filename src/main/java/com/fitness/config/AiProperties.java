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

        /**
         * Agent 循环的读取超时 —— **必须比单次调用更长**
         * <p>
         * 一次 Agent 回答 = 最多 5 次模型往返 + 最多 5 次工具往返，实测 5–15s，
         * 抖动时很容易超过 30s。若沿用 {@link #readTimeout}，Java 会先抛 6001「AI 服务超时」，
         * 而 Python 其实只是"还没跑完"—— 用户看到的错误信息是错的。
         * <p>
         * 因此给 Agent 单独的客户端与更高的上限，**不改全局 30s**
         * （普通接口不需要这么长的等待，放宽会掩盖真正的超时问题）。
         * <p>
         * ⚠️ 必须大于 Python 侧的循环死线（默认 25s），让 Python 先自己收口并返回降级答案，
         * 而不是让 Java 超时把整个响应丢掉。
         */
        private Duration agentReadTimeout = Duration.ofSeconds(90);
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

package com.fitness.service;

import com.fitness.client.AiPythonClient;
import com.fitness.config.AiProperties;
import com.fitness.config.RestClientConfig;
import com.fitness.dto.AiChatRequest;
import com.fitness.dto.AiRecommendRequest;
import com.fitness.dto.ai.PySummaryRequest;
import com.fitness.exception.BusinessException;
import com.fitness.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 降级链路测试 — 规范第十一章第 7 条「AI 降级方案」
 * <p>
 * 规范原文：<i>「若 Python 调用大模型超时或报错，Java 必须返回预设好的兜底文案
 * （如"AI 教练暂时走神了，请稍后再试"），不能让前端白屏。」</i>
 * <p>
 * <b>为什么这个测试不需要真的关掉 Python</b>：把 baseUrl 指向本机一个必然无人监听的端口
 * （127.0.0.1:1，特权端口，正常环境不会有服务），连接会立刻被拒绝，
 * 从而稳定复现「Python 不可用」这一分支 —— 比「先杀进程再跑测试」可靠得多，
 * 而且可以纳入常规构建，不会因为环境差异变成偶发失败。
 * <p>
 * 断言的重点不是「抛异常了」，而是<b>抛出的 msg 必须是兜底文案而不是技术错误信息</b>：
 * 前者能直接展示给用户，后者会暴露 "Connection refused" 这类无意义内容。
 */
@SpringBootTest(
        classes = {RestClientConfig.class, AiPythonClient.class, AiProxyService.class},
        properties = {
                // 特权端口，必然连不上 → 稳定复现 Python 不可用
                "ai.python.base-url=http://127.0.0.1:1",
                // 缩短超时，避免测试因环境差异长时间等待
                "ai.python.connect-timeout=500ms",
                "ai.python.read-timeout=500ms"
        })
@DisplayName("AI 降级：Python 不可用时返回兜底文案而非技术错误")
class AiDegradationTest {

    @Autowired
    private AiPythonClient aiPythonClient;

    @Autowired
    private AiProxyService aiProxyService;

    @Autowired
    private AiProperties aiProperties;

    /**
     * 问答记忆用桩替掉 —— 本测试关注的是「Python 不可用时的降级文案」，
     * 不该把 Redis/MySQL 一起拉进 Spring 上下文（记忆本身由 AiChatSessionServiceTest 覆盖）。
     */
    @org.springframework.boot.test.mock.mockito.MockBean
    private AiChatSessionService chatSessionService;

    @Test
    @DisplayName("客户端层：连接失败应翻译为 AI_TIMEOUT(6001) 业务异常")
    void clientShouldTranslateConnectionFailureToBusinessException() {
        PySummaryRequest request = PySummaryRequest.builder()
                .userId(1001L)
                .date("2026-07-30")
                .records(List.of())
                .build();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> aiPythonClient.generateSummary(request));
        assertEquals(ErrorCode.AI_TIMEOUT.getCode(), ex.getCode(),
                "连接失败/超时统一映射为 6001");
    }

    @Test
    @DisplayName("降级：动作推荐失败时 msg 应为兜底文案")
    void recommendShouldFallBackToPresetText() {
        AiRecommendRequest request = new AiRecommendRequest();
        request.setTargetMuscle("胸");
        request.setEquipment(List.of("哑铃"));
        request.setCount(5);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> aiProxyService.recommend(request));

        String fallback = aiProperties.getFallback().getRecommend();
        assertEquals(ErrorCode.AI_TIMEOUT.getCode(), ex.getCode());
        assertEquals(fallback, ex.getMessage(),
                "必须是预设兜底文案，而不是底层技术错误信息");
        assertTrue(ex.getMessage().contains("AI 教练"), "兜底文案应面向用户可读");
    }

    @Test
    @DisplayName("降级：知识库问答失败时 msg 应为兜底文案")
    void chatShouldFallBackToPresetText() {
        AiChatRequest request = new AiChatRequest();
        request.setQuestion("深蹲时膝盖可以超过脚尖吗？");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> aiProxyService.chat(1001L, request));

        assertEquals(aiProperties.getFallback().getChat(), ex.getMessage());
        // 关键反例：不能把客户端的默认错误文案直接透给前端
        assertTrue(!ErrorCode.AI_TIMEOUT.getMsg().equals(ex.getMessage()),
                "不应把「AI服务超时」这类技术文案直接返回前端");
    }

    @Test
    @DisplayName("降级：Python 不可用时，姿态评估的 msg 应为兜底文案")
    void poseEvaluateShouldFallBackToPresetText() throws Exception {
        // 必须传「合法图片」：第 6 周起 Java 会先压缩再转发，
        // 非法字节会被本地拦成 9003「不支持的图片格式」（那是入参问题，不是服务不可用），
        // 就测不到「Python 不可用 → 兜底文案」这条分支了。
        MockMultipartFile image = new MockMultipartFile(
                "image", "squat.jpg", "image/jpeg", sampleJpeg());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> aiProxyService.evaluatePose(image, "深蹲"));
        assertEquals(aiProperties.getFallback().getPose(), ex.getMessage());
    }

    /** 生成一张 64x64 的合法 JPEG，用于需要"图片本身没问题"的用例 */
    private static byte[] sampleJpeg() throws IOException {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(new java.awt.Color(200, 60, 60));
            g.fillRect(0, 0, 64, 64);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", buffer);
        return buffer.toByteArray();
    }

    @Test
    @DisplayName("姿态评估：本地参数校验必须在调用 Python 之前拦下非法动作名")
    void poseEvaluateShouldRejectUnsupportedActionBeforeCallingPython() {
        MockMultipartFile image = new MockMultipartFile(
                "image", "x.jpg", "image/jpeg", new byte[]{1});

        BusinessException ex = assertThrows(BusinessException.class,
                () -> aiProxyService.evaluatePose(image, "游泳"));
        // 9003 而不是 6001：明显的非法入参不该消耗一次跨语言往返
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("动作名称需为"));
    }

    @Test
    @DisplayName("健康探测：Python 不可用时返回 null，绝不抛异常")
    void healthProbeMustNotThrow() {
        // /api/v1/health 会调用它，健康检查接口本身不能因为依赖挂了而失败
        assertNull(aiPythonClient.health());
    }

    @Test
    @DisplayName("知识库健康：Python 不可用时按 6003 返回可展示状态")
    void knowledgeHealthShouldReportMilvusUnavailable() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> aiProxyService.knowledgeHealth());
        assertEquals(ErrorCode.MILVUS_UNAVAILABLE.getCode(), ex.getCode(),
                "规范 7.5：Milvus 不可用应为 6003 而非 6001");
        assertNotNull(ex.getMessage());
    }
}

package com.fitness.service;

import com.fitness.client.AiPythonClient;
import com.fitness.config.AiProperties;
import com.fitness.dto.AiPoseResponse;
import com.fitness.dto.ai.PyPoseData;
import com.fitness.dto.ai.PyPoseRequest;
import com.fitness.exception.BusinessException;
import com.fitness.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 姿态评估代理测试 —— 规范 7.3「Java 收到图片后先压缩到 1MB 以内再 Base64 转发 Python」
 * <p>
 * 断言的是关键契约：<b>送给 Python 的 Base64 必须来自压缩后的图片</b>。
 * 若某次改动把压缩步骤去掉，这里会直接失败 —— 因为 13MB 级的 Base64 会让同步接口不可用。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AiProxyService：姿态评估图片先压缩再 Base64")
class AiProxyServicePoseTest {

    /** 转发目标：≤1MB（Base64 后约 1.37MB） */
    private static final long ONE_MB = 1024 * 1024L;

    /** Python 侧 PoseEvaluateRequest.image_base64 的 max_length */
    private static final int PY_MAX_BASE64_CHARS = 2_000_000;

    @Mock
    private AiPythonClient aiPythonClient;

    private AiProxyService aiProxyService;

    @BeforeEach
    void setUp() {
        // AiProperties 是普通配置类，直接 new 即可（默认兜底文案与生产一致）
        aiProxyService = new AiProxyService(aiPythonClient, new AiProperties());
    }

    @Test
    @DisplayName("大图上传：转发给 Python 的 Base64 解码后 ≤1MB，且仍是合法 JPEG")
    void shouldCompressImageBeforeBase64Forwarding() throws IOException {
        byte[] raw = jpeg(noisyImage(2400, 1800));
        assertTrue(raw.length > ONE_MB, "测试图片应大于 1MB，实际=" + raw.length);

        when(aiPythonClient.evaluatePose(any(PyPoseRequest.class))).thenReturn(poseData());

        MockMultipartFile file = new MockMultipartFile(
                "image", "squat.jpg", "image/jpeg", raw);

        AiPoseResponse response = aiProxyService.evaluatePose(file, "深蹲");

        ArgumentCaptor<PyPoseRequest> captor = ArgumentCaptor.forClass(PyPoseRequest.class);
        verify(aiPythonClient).evaluatePose(captor.capture());
        PyPoseRequest sent = captor.getValue();

        assertEquals("深蹲", sent.getActionName(), "动作名称必须原样转发");

        byte[] decoded = Base64.getDecoder().decode(sent.getImageBase64());
        assertTrue(decoded.length <= ONE_MB,
                "转发给 Python 的图片必须是压缩后的（≤1MB），实际=" + decoded.length);
        assertTrue(sent.getImageBase64().length() <= PY_MAX_BASE64_CHARS,
                "Base64 长度必须落在 Python 侧 max_length 之内，实际=" + sent.getImageBase64().length());
        assertTrue(decoded[0] == (byte) 0xFF && decoded[1] == (byte) 0xD8,
                "压缩结果应为 JPEG（FFD8 魔数）");

        // 响应字段正常映射（确保压缩改动没有影响返回结构）
        assertEquals(78, response.getScore());
        assertEquals("良好", response.getScoreLevel());
    }

    @Test
    @DisplayName("非图片内容 → 9003 参数错误，且根本不调用 Python")
    void shouldRejectNonImageWithoutCallingPython() {
        MockMultipartFile file = new MockMultipartFile(
                "image", "fake.jpg", "image/jpeg", "definitely-not-an-image".getBytes());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> aiProxyService.evaluatePose(file, "深蹲"));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode(),
                "格式不支持的图片应返回 9003，而不是降级为转发原图");
        verify(aiPythonClient, never()).evaluatePose(any());
    }

    @Test
    @DisplayName("空文件 → 9003「图片文件不能为空」")
    void shouldRejectEmptyFile() {
        MockMultipartFile file = new MockMultipartFile("image", "empty.jpg", "image/jpeg", new byte[0]);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> aiProxyService.evaluatePose(file, "深蹲"));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        assertEquals("图片文件不能为空", ex.getMessage());
    }

    // ==================== 测试数据 ====================

    private PyPoseData poseData() {
        PyPoseData data = new PyPoseData();
        data.setScore(78);
        data.setScoreLevel("良好");
        data.setIssues(List.of("膝盖轻微内扣"));
        data.setSuggestions(List.of("下蹲时向外打开膝盖"));
        data.setGoodPoints(List.of("下蹲深度达标"));
        data.setEvaluatedAt("2026-07-30T15:38:00");
        return data;
    }

    private BufferedImage noisyImage(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(20260730L);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt(0xFFFFFF));
            }
        }
        return image;
    }

    private byte[] jpeg(BufferedImage image) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", buffer);
        return buffer.toByteArray();
    }
}

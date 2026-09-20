package com.fitness.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图片压缩工具测试 —— 规范 7.3「姿态评估图片必须压缩到 1MB 以内再 Base64 转发」
 * <p>
 * 断言三件事：
 * <ol>
 *   <li>大图能被压到目标体积以内，且最长边被缩到上限之内；</li>
 *   <li>PNG（含透明通道）也能压成合法 JPEG，不会因 alpha 通道编码失败；</li>
 *   <li>非图片内容 / 空内容被明确拒绝（→ 上游转成 9003），而不是把一个损坏文件转发给 Python。</li>
 * </ol>
 */
@DisplayName("ImageCompressor：姿态评估图片压缩")
class ImageCompressorTest {

    /** 1MB —— 与 AiProxyService 转发 Python 时的目标一致 */
    private static final long ONE_MB = 1024 * 1024L;

    @Test
    @DisplayName("大尺寸高噪点 JPEG 必须被压到 1MB 以内，且最长边 ≤1024px")
    void shouldCompressLargeJpegUnderTargetSize() throws IOException {
        // 随机噪点最难压缩，用它来构造「确实需要多轮降质」的输入
        byte[] raw = jpeg(noisyImage(2400, 1800), 1.0f);
        assertTrue(raw.length > ONE_MB, "构造的测试图片应大于 1MB，实际=" + raw.length);

        byte[] compressed = ImageCompressor.compress(raw, ONE_MB, 1024);

        assertTrue(compressed.length <= ONE_MB,
                "压缩后必须 ≤1MB（Base64 后约 1.37MB），实际=" + compressed.length);
        assertEquals("jpeg", ImageCompressor.detectFormat(compressed), "输出应为 JPEG");

        BufferedImage result = ImageIO.read(new ByteArrayInputStream(compressed));
        assertNotNull(result, "压缩结果必须是可解码的图片");
        assertTrue(Math.max(result.getWidth(), result.getHeight()) <= 1024,
                "最长边必须缩到 1024px 以内，实际=" + result.getWidth() + "x" + result.getHeight());
    }

    @Test
    @DisplayName("PNG（带透明通道）也能压成合法 JPEG，透明区域铺白底不会编码失败")
    void shouldCompressPngWithAlphaChannel() throws IOException {
        BufferedImage image = new BufferedImage(1600, 1200, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(new Color(0, 0, 0, 0));          // 全透明背景
            g.fillRect(0, 0, 1600, 1200);
            g.setColor(new Color(200, 30, 30, 200));
            g.fillOval(200, 200, 800, 600);
        } finally {
            g.dispose();
        }

        byte[] png = encode(image, "png");
        assertEquals("png", ImageCompressor.detectFormat(png));

        byte[] compressed = ImageCompressor.compress(png, ONE_MB, 1024);

        assertEquals("jpeg", ImageCompressor.detectFormat(compressed),
                "带 alpha 的 PNG 必须转成 JPEG（否则体积下不来）");
        BufferedImage result = ImageIO.read(new ByteArrayInputStream(compressed));
        assertNotNull(result);
        assertTrue(Math.max(result.getWidth(), result.getHeight()) <= 1024);
    }

    @Test
    @DisplayName("本来就达标的小 JPEG 原样返回：不重编码、不改变字节")
    void shouldReturnSmallJpegUntouched() throws IOException {
        byte[] raw = jpeg(noisyImage(400, 300), 0.8f);
        assertTrue(raw.length <= ONE_MB && raw.length > 0);

        byte[] result = ImageCompressor.compress(raw, ONE_MB, 1024);

        assertSame(raw, result, "已达标的小图不应被重新编码（避免「压一次反而变大」）");
    }

    @Test
    @DisplayName("识别真实格式：JPEG / PNG 魔数，非图片内容返回 null")
    void shouldDetectFormatByMagicBytes() throws IOException {
        assertEquals("jpeg", ImageCompressor.detectFormat(jpeg(noisyImage(80, 80), 0.9f)));
        assertEquals("png", ImageCompressor.detectFormat(encode(noisyImage(80, 80), "png")));
        assertEquals(null, ImageCompressor.detectFormat("这不是图片".getBytes()));
        assertEquals(null, ImageCompressor.detectFormat(new byte[]{1, 2, 3}));
        assertEquals(null, ImageCompressor.detectFormat(null));
    }

    @Test
    @DisplayName("空内容 / 非图片内容 → IllegalArgumentException（上游转成 9003 参数错误）")
    void shouldRejectEmptyAndNonImageContent() {
        assertThrows(IllegalArgumentException.class,
                () -> ImageCompressor.compress(new byte[0]),
                "空文件必须被拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> ImageCompressor.compress("this is not an image at all".getBytes()),
                "非图片内容必须被拒绝，不能转发给 Python");
        // 有正确魔数但内容损坏 → 也应报错而不是抛底层 IO 异常
        assertThrows(IllegalArgumentException.class,
                () -> ImageCompressor.compress(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3, 4, 5}));
    }

    // ==================== 测试图片构造 ====================

    /** 生成随机噪点图：随机像素几乎不可压缩，便于稳定地构造出「超目标体积」的输入 */
    private BufferedImage noisyImage(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(20260730L);   // 固定种子：测试结果可复现
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt(0xFFFFFF));
            }
        }
        return image;
    }

    private byte[] jpeg(BufferedImage image, float quality) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            javax.imageio.ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            try (javax.imageio.stream.ImageOutputStream out = ImageIO.createImageOutputStream(buffer)) {
                writer.setOutput(out);
                writer.write(null, new javax.imageio.IIOImage(image, null, null), param);
            } finally {
                writer.dispose();
            }
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("构造测试图片失败", e);
        }
    }

    private byte[] encode(BufferedImage image, String format) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            ImageIO.write(image, format, buffer);
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("构造测试图片失败", e);
        }
    }
}

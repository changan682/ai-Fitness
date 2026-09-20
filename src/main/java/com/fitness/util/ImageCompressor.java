package com.fitness.util;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * 姿态评估图片压缩工具 —— 把用户上传的原图压到「可安全 Base64 转发」的体量
 *
 * <h3>为什么必须压缩（规范 7.3）</h3>
 * 前端允许上传 10MB 的原图，但 Base64 会让体积膨胀约 33%（10MB → 约 13.3MB）：
 * <ul>
 *   <li>JSON + HTTP 传输 13MB 字符串非常慢，姿态评估这种「同步等待结果」的接口体验会很差；</li>
 *   <li>Base64 字符串在 Java 里是常驻堆内存的 char/byte 数组，并发几个请求就会显著推高 GC 压力；</li>
 *   <li>多模态模型接收图片时本身也会做缩放，传原图并<b>不会</b>提升判断精度。</li>
 * </ul>
 * 因此统一压到 ≤1MB（Base64 后约 1.37MB）再转发，链路稳定且不损失有效信息。
 *
 * <h3>实现要点</h3>
 * <ul>
 *   <li>只用 JDK 自带的 {@code javax.imageio}，不引入额外图像库依赖；</li>
 *   <li>先按最长边缩放到 ≤1024px，再在「质量阶梯」里逐档降质直到达标；</li>
 *   <li>仍不达标时继续按比例缩小尺寸，多轮尝试后返回当前最优结果（宁可略微超标也不让接口失败）；</li>
 *   <li>PNG 带 alpha 通道时先铺白底再编码为 JPEG，避免透明区域变成黑块。</li>
 * </ul>
 */
@Slf4j
public final class ImageCompressor {

    /** 默认压缩目标：1MB（规范 7.3「压缩到 1MB 以内」） */
    public static final long DEFAULT_MAX_BYTES = 1024 * 1024L;

    /** 默认最长边上限：1024px（姿态评估只需要看清人体姿态，更大分辨率对模型无增益） */
    public static final int DEFAULT_MAX_EDGE = 1024;

    /** 质量阶梯：先试较高画质，不达标再逐档下降（0.75 是肉眼几乎无损的起点） */
    private static final float[] QUALITY_LADDER = {0.75f, 0.6f, 0.5f, 0.4f, 0.3f};

    /** 尺寸收缩轮次：质量压到最低仍超标时，每次再缩小 20% 重来 */
    private static final int MAX_RESIZE_ROUNDS = 4;

    static {
        // 关掉 ImageIO 的磁盘缓存：默认实现会把流落到临时目录（服务器上表现为 /tmp 被图片写满）。
        // 这里处理的都是单张 ≤1MB 的图片，全部放内存更安全。
        ImageIO.setUseCache(false);
    }

    private ImageCompressor() {
        // 工具类，禁止实例化
    }

    /** 按默认目标压缩（≤1MB、最长边 ≤1024px） */
    public static byte[] compress(byte[] raw) {
        return compress(raw, DEFAULT_MAX_BYTES, DEFAULT_MAX_EDGE);
    }

    /**
     * 压缩图片为 JPEG 字节数组
     *
     * @param raw      原始图片字节（JPG / PNG）
     * @param maxBytes 目标最大字节数
     * @param maxEdge  最长边上限（像素）
     * @return 压缩后的 JPEG 字节数组
     * @throws IllegalArgumentException 图片为空、格式不支持或已损坏
     */
    public static byte[] compress(byte[] raw, long maxBytes, int maxEdge) {
        if (raw == null || raw.length == 0) {
            throw new IllegalArgumentException("图片内容为空");
        }

        String format = detectFormat(raw);
        if (format == null) {
            // 按魔数判断真实格式，不信任文件名后缀（后缀可以随便改）
            throw new IllegalArgumentException("不支持的图片格式，仅支持 JPG / PNG");
        }

        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(raw));
        } catch (IOException e) {
            throw new IllegalArgumentException("图片读取失败，请重新上传");
        }
        if (image == null) {
            throw new IllegalArgumentException("图片已损坏或格式不受支持");
        }

        // 已经达标的小图直接原样返回：不重编码，避免「压一次反而变大」的无谓损耗
        if (raw.length <= maxBytes && maxEdgeOf(image) <= maxEdge && "jpeg".equals(format)) {
            return raw;
        }

        byte[] best = null;
        BufferedImage current = resizeToMaxEdge(image, maxEdge);

        for (int round = 0; round <= MAX_RESIZE_ROUNDS; round++) {
            BufferedImage rgb = toRgb(current);
            for (float quality : QUALITY_LADDER) {
                byte[] encoded = encodeJpeg(rgb, quality);
                if (encoded == null) {
                    continue;
                }
                if (best == null || encoded.length < best.length) {
                    best = encoded;
                }
                if (encoded.length <= maxBytes) {
                    return encoded;
                }
            }
            // 降质到底仍超标 → 尺寸再缩 20% 重试
            int nextEdge = Math.max(1, (int) (maxEdgeOf(current) * 0.8));
            if (nextEdge < 64) {
                break;      // 再小就没有分析价值了，直接用当前最优结果
            }
            current = resizeToMaxEdge(current, nextEdge);
        }

        if (best == null) {
            throw new IllegalArgumentException("图片压缩失败，请换一张图片重试");
        }
        log.warn("图片压缩后仍超过目标大小，返回当前最优结果: {}KB > {}KB",
                best.length / 1024, maxBytes / 1024);
        return best;
    }

    /**
     * 按魔数识别真实格式
     *
     * @return {@code "jpeg"} / {@code "png"}；无法识别返回 null
     */
    public static String detectFormat(byte[] raw) {
        if (raw == null || raw.length < 8) {
            return null;
        }
        // JPEG: FF D8 FF
        if ((raw[0] & 0xFF) == 0xFF && (raw[1] & 0xFF) == 0xD8 && (raw[2] & 0xFF) == 0xFF) {
            return "jpeg";
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if ((raw[0] & 0xFF) == 0x89 && raw[1] == 0x50 && raw[2] == 0x4E && raw[3] == 0x47
                && raw[4] == 0x0D && raw[5] == 0x0A && raw[6] == 0x1A && raw[7] == 0x0A) {
            return "png";
        }
        return null;
    }

    // ==================== 内部实现 ====================

    private static int maxEdgeOf(BufferedImage image) {
        return Math.max(image.getWidth(), image.getHeight());
    }

    /** 等比缩放到最长边不超过 maxEdge；本来就够小则原样返回（不放大） */
    private static BufferedImage resizeToMaxEdge(BufferedImage source, int maxEdge) {
        int width = source.getWidth();
        int height = source.getHeight();
        int longest = Math.max(width, height);
        if (longest <= maxEdge) {
            return source;
        }

        double scale = (double) maxEdge / longest;
        int targetWidth = Math.max(1, (int) Math.round(width * scale));
        int targetHeight = Math.max(1, (int) Math.round(height * scale));

        // 统一用 RGB 目标图：JPEG 不支持 alpha，PNG 的透明区域需要先铺白底
        BufferedImage target = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(java.awt.Color.WHITE);           // 透明区域铺白，避免编成 JPEG 后变黑
            g.fillRect(0, 0, targetWidth, targetHeight);
            g.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    /** 确保图像是 RGB 类型（JPEG 编码器不接受带 alpha 的 ARGB） */
    private static BufferedImage toRgb(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_RGB) {
            return source;
        }
        BufferedImage rgb = new BufferedImage(
                source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, source.getWidth(), source.getHeight());
            g.drawImage(source, 0, 0, null);
        } finally {
            g.dispose();
        }
        return rgb;
    }

    /** 按指定质量编码为 JPEG；编码器不可用或写入失败时返回 null */
    private static byte[] encodeJpeg(BufferedImage image, float quality) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            return null;
        }
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(buffer)) {
            writer.setOutput(output);
            writer.write(null, new IIOImage(image, null, null), param);
            return buffer.toByteArray();
        } catch (IOException e) {
            log.warn("JPEG 编码失败: quality={}, reason={}", quality, e.getMessage());
            return null;
        } finally {
            writer.dispose();
        }
    }
}

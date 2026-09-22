package com.fitness.service;

import com.fitness.config.AvatarProperties;
import com.fitness.exception.BusinessException;
import com.fitness.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 头像存储单元测试 —— 纯文件系统，不启动 Spring，不依赖 MySQL/Redis。
 *
 * <h3>这里守的是什么</h3>
 * 头像上传是**唯一一个把用户提供的字节写进服务器磁盘**的入口，因此测试重点不在"能不能传上去"，
 * 而在几条边界：路径穿越、伪装的假图、超限文件、以及"路径与用户不匹配"的脏数据。
 */
class AvatarStorageServiceTest {

    @TempDir
    Path tempDir;

    private AvatarStorageService service;

    @BeforeEach
    void setUp() {
        AvatarProperties properties = new AvatarProperties();
        properties.setDir(tempDir.toString());
        properties.setMaxUploadBytes(2 * 1024 * 1024L);
        properties.setMaxStoredBytes(300 * 1024L);
        properties.setMaxEdge(256);
        service = new AvatarStorageService(properties);
    }

    // ==================== 素材 ====================

    /** 生成真实 JPEG 字节（必须是真图：校验会做解码） */
    private static byte[] jpegBytes(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(30, 120, 200));
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    private static MockMultipartFile file(byte[] bytes, String name, String contentType) {
        return new MockMultipartFile("file", name, contentType, bytes);
    }

    // ==================== 正常路径 ====================

    @Test
    @DisplayName("上传成功：返回带版本号的访问路径，且能按该路径读回同样的字节")
    void storeThenLoad() throws Exception {
        byte[] raw = jpegBytes(600, 600);

        String url = service.store(7L, file(raw, "avatar.jpg", "image/jpeg"));

        assertTrue(url.startsWith("/api/v1/user/avatar/7?v="), "路径必须含用户与版本号，实际: " + url);
        byte[] loaded = service.load(7L, url);
        assertNotNull(loaded, "刚存进去的头像必须能读出来");
        // 600px 大图会被压到 ≤256px，因此只断言"是 JPEG"，不断言字节相等
        assertEquals((byte) 0xFF, loaded[0]);
        assertEquals((byte) 0xD8, loaded[1]);
        assertTrue(service.exists(7L, url));
    }

    @Test
    @DisplayName("小图不重编码：字节原样落盘（避免压一次反而变大）")
    void smallJpegIsStoredAsIs() throws Exception {
        byte[] raw = jpegBytes(120, 120);

        String url = service.store(8L, file(raw, "small.jpg", "image/jpeg"));

        assertArrayEquals(raw, service.load(8L, url), "已达标的小图应原样保存");
    }

    @Test
    @DisplayName("连续两次上传拿到不同版本号（同毫秒也不撞名）—— 否则浏览器缓存破不掉、新旧文件互相覆盖")
    void consecutiveUploadsGetDistinctVersions() throws Exception {
        String first = service.store(9L, file(jpegBytes(200, 200), "a.jpg", "image/jpeg"));
        String second = service.store(9L, file(jpegBytes(300, 300), "b.jpg", "image/jpeg"));

        assertNotEquals(first, second, "同一毫秒内的两次上传也必须拿到不同的 ?v= 版本号");
        assertTrue(service.exists(9L, first), "存储层不负责删旧文件（那是 UserService 的编排职责）");
        assertTrue(service.exists(9L, second));

        // 由调用方显式清理旧文件，且必须幂等（再删一次不能抛）
        service.deleteQuietly(9L, first);
        assertFalse(service.exists(9L, first));
        service.deleteQuietly(9L, first);
        assertTrue(service.exists(9L, second), "删旧文件不能误伤当前头像");
    }

    // ==================== 边界与安全 ====================

    @Test
    @DisplayName("空文件被拒（1004），且不产生任何文件")
    void emptyFileRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.store(1L, file(new byte[0], "empty.jpg", "image/jpeg")));
        assertEquals(ErrorCode.AVATAR_INVALID.getCode(), ex.getCode());
        assertEquals(0, filesInTempDir());
    }

    @Test
    @DisplayName("超过 2MB 被拒（1004）")
    void oversizeRejected() {
        byte[] huge = new byte[2 * 1024 * 1024 + 1];
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.store(1L, file(huge, "huge.jpg", "image/jpeg")));
        assertEquals(ErrorCode.AVATAR_INVALID.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("把文本改名成 .jpg 也上传不了 —— 按魔数判断，不信后缀")
    void fakeImageRejected() {
        byte[] notAnImage = "这不是图片，只是改了扩展名".getBytes(StandardCharsets.UTF_8);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.store(1L, file(notAnImage, "evil.jpg", "image/jpeg")));

        assertEquals(ErrorCode.AVATAR_INVALID.getCode(), ex.getCode());
        assertEquals(0, filesInTempDir(), "校验失败的图片不能落盘");
    }

    @Test
    @DisplayName("路径穿越：库里的脏 avatarUrl 读不出目录外的文件")
    void pathTraversalIsRefused() throws Exception {
        // 在头像目录外放一个"敏感文件"，然后构造各种穿越路径尝试读取它
        Path outside = tempDir.getParent().resolve("secret.txt");
        Files.writeString(outside, "should-never-be-served");
        try {
            String[] attacks = {
                    "/api/v1/user/avatar/1?v=../../secret",
                    "/api/v1/user/avatar/1?v=1/../../secret",
                    "/api/v1/user/avatar/../1?v=1",
                    "/etc/passwd",
                    "1-1789999999999.jpg",                       // 直接给文件名也不行
                    "/api/v1/user/avatar/1?v=1789999999999\n.jpg",
            };
            for (String attack : attacks) {
                assertNull(service.load(1L, attack), "不该为非法路径返回内容: " + attack);
                assertFalse(service.exists(1L, attack), "非法路径不该被认作存在: " + attack);
            }
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    @DisplayName("路径里的用户 id 与请求用户不一致时按「无头像」处理（防越权读别人的头像）")
    void mismatchedUserIdIsRefused() throws Exception {
        String urlOfUser1 = service.store(1L, file(jpegBytes(120, 120), "a.jpg", "image/jpeg"));

        assertNotNull(service.load(1L, urlOfUser1));
        assertNull(service.load(2L, urlOfUser1), "换成别人来读同一个路径必须拿不到");
    }

    private long filesInTempDir() {
        try (var stream = Files.list(tempDir)) {
            return stream.count();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

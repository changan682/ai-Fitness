package com.fitness.service;

import com.fitness.config.AvatarProperties;
import com.fitness.exception.BusinessException;
import com.fitness.exception.ErrorCode;
import com.fitness.util.ImageCompressor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 头像文件存储 — 校验、压缩、落盘、读取、清理。
 *
 * <h3>为什么文件名由服务端生成，而不是用上传的文件名</h3>
 * 上传的文件名完全由客户端控制，直接拼进路径就是**路径穿越**漏洞
 * （{@code ../../application.yml} 这类）。这里文件名固定为 {@code {userId}-{epochMillis}.jpg}，
 * 只含两个服务端产生的数字，客户端连一个字符都影响不到路径。
 *
 * <h3>为什么只能存成 JPEG</h3>
 * 复用 {@link ImageCompressor}：它按**魔数**判断真实格式（不信任后缀），只认 JPG/PNG，
 * 输出统一是 JPEG。副作用是**透明 PNG 会变白底** —— 已在文档中写明。
 * 不引入 WebP：现有工具链不支持，为头像单独引一个图像库不划算。
 *
 * <h3>数据库里存什么</h3>
 * 存访问路径 {@code /api/v1/user/avatar/{userId}?v={epochMillis}}，文件由路径里的两个数字
 * 反推得到 —— 这样"库里那条记录"就是唯一事实来源，不需要额外的文件名列，
 * 也不存在"记录与实际文件对不上"的可能性。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AvatarStorageService {

    /** 头像访问路径前缀（读取接口与 JWT 白名单必须与此一致） */
    public static final String URL_PREFIX = "/api/v1/user/avatar/";

    /**
     * 合法访问路径的形状 —— 只有完全符合它的字符串才会被用来拼文件路径。
     * <p>
     * 这是路径穿越的第二道防线：即便有人把脏数据写进了 {@code avatar_url}
     * （例如手工改库），拼路径前也会先被这个正则挡下。
     */
    private static final Pattern URL_PATTERN =
            Pattern.compile("^/api/v1/user/avatar/(\\d{1,19})\\?v=(\\d{1,19})$");

    private static final String EXTENSION = ".jpg";

    private final AvatarProperties properties;

    // ==================== 写入 ====================

    /**
     * 保存头像并返回访问路径
     *
     * @throws BusinessException 头像为空/超限/格式不支持/已损坏（错误码 1004）
     */
    public String store(Long userId, MultipartFile file) {
        if (userId == null) {
            // 正常不可能走到这里：userId 由 Controller 从 Token 里取。
            // 真出现说明鉴权白名单把上传接口也放行了（见 WebConfig.JWT_EXCLUDE_PATTERNS 的注释），
            // 与其在 findById(null) 上抛一个看不懂的 9999，不如在这里说清楚。
            log.error("上传头像时未取到登录用户：请检查 JWT 白名单是否误把 POST /api/v1/user/avatar 也排除了");
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "未取到登录用户，无法上传头像");
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.AVATAR_INVALID, "请选择要上传的图片");
        }
        if (file.getSize() > properties.getMaxUploadBytes()) {
            throw new BusinessException(ErrorCode.AVATAR_INVALID,
                    "头像文件过大（最大 " + properties.getMaxUploadBytes() / 1024 / 1024 + "MB）");
        }

        byte[] raw;
        try {
            raw = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.AVATAR_INVALID, "头像读取失败，请重新上传");
        }

        byte[] compressed;
        try {
            // 压缩器内部会做魔数校验 + 真实解码校验（挡掉"把 txt 改名成 .jpg"）
            compressed = ImageCompressor.compress(
                    raw, properties.getMaxStoredBytes(), properties.getMaxEdge());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.AVATAR_INVALID, e.getMessage());
        }

        long version = nextFreeVersion(userId);
        Path target = fileOf(userId, version);
        try {
            Files.createDirectories(target.getParent());
            // 先写临时文件再原子移动：避免写到一半失败留下半张图（前端会显示成破图）
            Path temp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.write(temp, compressed);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("头像写盘失败: userId={}, path={}, err={}", userId, target, e.getMessage());
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "头像保存失败，请稍后再试");
        }

        log.info("头像已保存: userId={}, bytes={}->{}, path={}",
                userId, raw.length, compressed.length, target.getFileName());
        return URL_PREFIX + userId + "?v=" + version;
    }

    /**
     * 取一个尚未被占用的版本号（起点是当前毫秒）
     * <p>
     * <b>为什么不能直接用 {@code System.currentTimeMillis()}</b>：同一毫秒内的两次上传会算出
     * 同一个版本号 → 文件名相同、URL 相同 → ① 新图覆盖旧图，"删旧文件"会顺手把刚写的新文件删掉；
     * ② 浏览器那边 {@code ?v=} 没变，24 小时缓存直接命中，用户看到旧头像以为"没换成功"。
     * 正常点按不可能 1ms 内传两次，但脚本化/并发请求可以，所以这里主动避让。
     */
    private long nextFreeVersion(Long userId) {
        long version = System.currentTimeMillis();
        while (Files.exists(fileOf(userId, version))) {
            version++;
        }
        return version;
    }

    // ==================== 读取 / 清理 ====================

    /**
     * 读取头像字节
     *
     * @return 文件不存在或路径不合法时返回 {@code null}（调用方转 404，而不是报错）
     */
    public byte[] load(Long userId, String avatarUrl) {
        Path path = pathOf(userId, avatarUrl);
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            log.warn("头像读取失败: userId={}, path={}, err={}", userId, path, e.getMessage());
            return null;
        }
    }

    /** 删除旧头像文件（换头像后调用）。删除失败只记日志：留个孤儿文件远比让上传失败好 */
    public void deleteQuietly(Long userId, String avatarUrl) {
        Path path = pathOf(userId, avatarUrl);
        if (path == null) {
            return;
        }
        try {
            if (Files.deleteIfExists(path)) {
                log.info("旧头像已清理: userId={}, file={}", userId, path.getFileName());
            }
        } catch (IOException e) {
            log.warn("旧头像清理失败（忽略）: userId={}, path={}, err={}", userId, path, e.getMessage());
        }
    }

    /** 该用户是否已有头像文件（供健康检查/测试用） */
    public boolean exists(Long userId, String avatarUrl) {
        Path path = pathOf(userId, avatarUrl);
        return path != null && Files.isRegularFile(path);
    }

    // ==================== 内部 ====================

    /**
     * 由「访问路径」反推文件路径
     * <p>
     * 只有形如 {@code /api/v1/user/avatar/{id}?v={ts}} 且 id 与传入的 userId 一致时才认，
     * 其余一律返回 {@code null}（宁可不显示头像，也不能去读一个路径可控的文件）。
     */
    private Path pathOf(Long userId, String avatarUrl) {
        Optional<String> version = versionOf(userId, avatarUrl);
        return version.map(v -> fileOf(userId, Long.parseLong(v))).orElse(null);
    }

    private Optional<String> versionOf(Long userId, String avatarUrl) {
        if (userId == null || avatarUrl == null || avatarUrl.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = URL_PATTERN.matcher(avatarUrl.trim());
        if (!matcher.matches()) {
            log.warn("头像路径非法，已忽略: userId={}, avatarUrl={}", userId, avatarUrl);
            return Optional.empty();
        }
        if (!matcher.group(1).equals(String.valueOf(userId))) {
            // 路径里的 id 与当前用户不一致：数据串了，按"没有头像"处理并告警
            log.warn("头像路径与用户不匹配: userId={}, avatarUrl={}", userId, avatarUrl);
            return Optional.empty();
        }
        return Optional.of(matcher.group(2));
    }

    private Path fileOf(Long userId, long version) {
        return Paths.get(properties.getDir()).toAbsolutePath().normalize()
                .resolve(userId + "-" + version + EXTENSION);
    }
}

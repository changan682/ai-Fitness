package com.fitness.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 头像存储配置 — 对应 {@code application.yml} 的 {@code app.avatar.*}
 *
 * <h3>为什么头像落磁盘而不是存数据库</h3>
 * 存 Base64 会让单行膨胀到几百 KB：用户表被频繁查询（登录、档案、缓存回填），
 * 每次都要把这坨字符串读进内存和网络，收益为零。落盘 + 只存路径是常规做法。
 */
@Data
@ConfigurationProperties(prefix = "app.avatar")
public class AvatarProperties {

    /** 头像文件目录（相对项目根目录；已在 .gitignore 中忽略） */
    private String dir = "./data/avatars";

    /**
     * 上传允许的最大原始字节数（2MB）
     * <p>
     * 前端也会做一次同样大小的校验，但**服务端必须独立校验**：前端校验只是体验，
     * 绕过它发请求的成本极低。
     */
    private long maxUploadBytes = 2 * 1024 * 1024L;

    /** 落盘目标最大字节数（300KB）—— 超过就靠压缩阶梯降到这个量级 */
    private long maxStoredBytes = 300 * 1024L;

    /** 落盘最长边（像素）—— 头像最大显示 80px，256 足够覆盖 2x/3x 屏 */
    private int maxEdge = 256;
}

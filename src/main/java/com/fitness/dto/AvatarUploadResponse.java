package com.fitness.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 头像上传响应 — 返回可直接放进 {@code <img src>} 的访问路径 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AvatarUploadResponse {

    /**
     * 形如 {@code /api/v1/user/avatar/12?v=1789999999999}
     * <p>
     * 末尾的 {@code v} 是 epoch 毫秒版本号：头像 URL 要被浏览器缓存 24 小时，
     * 换头像后如果 URL 不变，用户会看到旧图（看起来像"没换成功"）。
     */
    private String avatarUrl;
}

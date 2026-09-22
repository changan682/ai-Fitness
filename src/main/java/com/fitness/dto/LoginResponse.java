package com.fitness.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 登录响应 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {
    private String token;
    private String expiresAt;
    private UserBrief user;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserBrief {
        private Long id;
        private String nickname;
        private Integer gender;
        private String trainingGoal;

        /** 头像访问路径（可空）；前端顶栏直接用它渲染 <img src>，省掉一次档案请求 */
        private String avatarUrl;
    }
}

package com.fitness.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JWT 白名单的配置级回归测试。
 *
 * <h3>为什么必须有这个文件</h3>
 * 白名单写错不会编译报错、也不会被任何服务层单测发现 —— 它只在**真实联调**时暴露，
 * 而且两种错法都很难看：
 * <ul>
 *   <li>把上传接口一起放行 → 未登录也能写（若哪天改成从请求体取 userId，就是越权改别人头像）；</li>
 *   <li>把读取接口漏掉 → 头像永远 401（{@code <img src>} 不带 Authorization 头）。</li>
 * </ul>
 * 实测踩过一次：写成 {@code /api/v1/user/avatar/**} 时，Ant 匹配器**也匹配
 * {@code /api/v1/user/avatar} 本身**，上传接口被静默放行，表现是
 * {@code getUserId()} 为 null → 拿 null 去 findById → 9999。
 * 所以这里用**真实的 {@link AntPathMatcher}** 对着真实常量做断言，而不是靠人眼 review。
 */
class WebConfigAuthWhitelistTest {

    private final PathMatcher matcher = new AntPathMatcher();

    /** 是否被白名单放行（= 免鉴权） */
    private boolean isPublic(String path) {
        return Arrays.stream(WebConfig.JWT_EXCLUDE_PATTERNS)
                .anyMatch(pattern -> matcher.match(pattern, path));
    }

    @Test
    @DisplayName("头像读取免鉴权（<img src> 不带 Authorization 头，否则头像永远 401）")
    void avatarReadIsPublic() {
        assertTrue(isPublic("/api/v1/user/avatar/36"));
        assertTrue(isPublic("/api/v1/user/avatar/1"));
    }

    @Test
    @DisplayName("头像上传必须鉴权：POST /api/v1/user/avatar 不能被白名单放行")
    void avatarUploadRequiresAuth() {
        assertFalse(isPublic("/api/v1/user/avatar"),
                "上传接口一旦免鉴权，userId 就取不到（联调时表现为 9999），且有越权写风险");
    }

    @Test
    @DisplayName("单星号才能区分「上传」与「读取」——双星号会把两者一起放行（记录这个坑）")
    void doubleStarWouldAlsoMatchTheUploadPath() {
        assertTrue(matcher.match("/api/v1/user/avatar/**", "/api/v1/user/avatar"),
                "Ant 的 /** 匹配 0 段及以上，所以它能匹配到 /api/v1/user/avatar 本身");
        assertFalse(matcher.match("/api/v1/user/avatar/*", "/api/v1/user/avatar"),
                "而 * 只匹配恰好一段，这正是白名单必须用单星号的原因");
    }

    @Test
    @DisplayName("其余受保护接口没有被白名单误放行")
    void protectedEndpointsStayProtected() {
        assertFalse(isPublic("/api/v1/user/profile"));
        assertFalse(isPublic("/api/v1/user/password"));
        assertFalse(isPublic("/api/v1/training/records"));
        assertFalse(isPublic("/api/ai/chat"));
        assertFalse(isPublic("/api/v1/body-metric/trend"));
    }

    @Test
    @DisplayName("登录/注册/回调/健康检查仍在白名单里（防止被误删）")
    void intendedPublicEndpointsRemainPublic() {
        assertTrue(isPublic("/api/v1/user/register"));
        assertTrue(isPublic("/api/v1/user/login"));
        assertTrue(isPublic("/api/v1/health"));
        assertTrue(isPublic("/api/ai/callback/weekly-plan"));
    }
}

package com.fitness.controller;

import com.fitness.common.BaseController;
import com.fitness.common.Result;
import com.fitness.dto.*;
import com.fitness.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;

/**
 * 用户模块 Controller — /api/v1/user/*（9个接口）
 * <p>
 * 成功响应中的 msg 文案按接口清单逐条对齐（规范里对多数接口指定了业务化提示语，
 * 前端直接展示 msg，因此不能统一返回 "success"）。
 * <p>
 * 其中 1.8 上传头像 / 1.9 读取头像是体验优化批次新增（规范接口清单之外），
 * 读取接口无鉴权，理由见方法注释。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/user")
@RequiredArgsConstructor
public class UserController extends BaseController {

    private final UserService userService;

    /** 1.1 用户注册 */
    @PostMapping("/register")
    public Result<RegisterResponse> register(@Valid @RequestBody RegisterRequest req) {
        return Result.ok(userService.register(req));
    }

    /** 1.2 用户登录 */
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        return Result.ok(userService.login(req));
    }

    /** 1.3 查询个人档案 */
    @GetMapping("/profile")
    public Result<UserProfileResponse> getProfile(HttpServletRequest request) {
        return Result.ok(userService.getProfile(getUserId(request)));
    }

    /** 1.4 修改个人档案 */
    @PutMapping("/profile")
    public Result<Void> updateProfile(HttpServletRequest request,
                                      @Valid @RequestBody UpdateProfileRequest req) {
        userService.updateProfile(getUserId(request), req);
        return Result.ok("档案已更新", null);
    }

    /** 1.5 修改密码 — 校验旧密码后作废该用户全部已签发 Token（iat 水位线） */
    @PutMapping("/password")
    public Result<Void> changePassword(HttpServletRequest request,
                                       @Valid @RequestBody ChangePasswordRequest req) {
        userService.changePassword(getUserId(request), getToken(request), req);
        return Result.ok("密码已修改，请重新登录", null);
    }

    /** 1.6 用户登出 — 将当前 Token（按 jti）加入黑名单 */
    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        Long userId = getUserId(request);
        userService.blacklistToken(getToken(request));
        log.info("用户登出: userId={}", userId);
        return Result.ok("已退出登录", null);
    }

    /** 1.7 刷新 JWT Token — 旧 Token 过期前 24 小时内可刷新 */
    @PostMapping("/refresh")
    public Result<RefreshTokenResponse> refreshToken(HttpServletRequest request) {
        RefreshTokenResponse response = userService.refreshToken(getUserId(request), getToken(request));
        return Result.ok("Token已刷新", response);
    }

    // ==================== 头像（1.8 / 1.9，体验优化批次 B） ====================

    /**
     * 1.8 上传/更换头像 — multipart，字段名 {@code file}
     * <p>
     * 校验（大小/魔数/真实解码）与压缩在 {@code AvatarStorageService} 里做，
     * 这里只负责把当前登录用户的 id 传进去 —— 用户 id **只从 Token 取**，
     * 不接受请求体里传 userId（否则任何人都能改别人的头像）。
     */
    @PostMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<AvatarUploadResponse> uploadAvatar(HttpServletRequest request,
                                                     @RequestPart("file") MultipartFile file) {
        return Result.ok("头像已更新", userService.uploadAvatar(getUserId(request), file));
    }

    /**
     * 1.9 读取头像 — 返回图片字节流，不是 JSON 信封
     * <p>
     * <b>这个接口在 JWT 白名单里</b>：浏览器给 {@code <img src>} 发请求时不会带
     * {@code Authorization} 头，不放行就会表现为"头像永远不显示（401）"。
     * 安全性由"只暴露头像字节、不含任何隐私字段"承担，且路径里的 userId 必须与
     * 库里记录一致才会返回文件（见 {@code AvatarStorageService#pathOf}）。
     * <p>
     * 未设置头像 → 404（而不是返回一张默认图：前端用图标兜底更清晰）。
     */
    @GetMapping("/avatar/{userId}")
    public ResponseEntity<byte[]> getAvatar(@PathVariable Long userId) {
        byte[] bytes = userService.loadAvatar(userId);
        if (bytes == null || bytes.length == 0) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                // 头像 URL 带版本号（?v=epoch），可以放心让浏览器长期缓存
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                .body(bytes);
    }
}

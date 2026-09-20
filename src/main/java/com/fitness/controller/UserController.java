package com.fitness.controller;

import com.fitness.common.BaseController;
import com.fitness.common.Result;
import com.fitness.dto.*;
import com.fitness.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 用户模块 Controller — /api/v1/user/*（7个接口）
 * <p>
 * 成功响应中的 msg 文案按接口清单逐条对齐（规范里对多数接口指定了业务化提示语，
 * 前端直接展示 msg，因此不能统一返回 "success"）。
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
}

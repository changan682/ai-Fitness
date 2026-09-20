package com.fitness.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 注册响应 — 对应提示词 1.1 的 data 结构
 * <p>
 * 只返回 id/nickname/phone（脱敏），不回传任何凭证或完整手机号。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterResponse {

    private Long id;

    private String nickname;

    /** 脱敏手机号，如 138****8000 */
    private String phone;
}

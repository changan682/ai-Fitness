package com.fitness.dto;

import com.fitness.common.ValidationPatterns;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 注册请求 — 对应提示词 1.1 */
@Data
public class RegisterRequest {

    @NotBlank(message = "昵称不能为空")
    @Size(min = 2, max = 20, message = "昵称长度需2-20字符")
    private String nickname;

    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = ValidationPatterns.PHONE, message = "手机号格式不正确，需为11位手机号")
    private String phone;

    @NotBlank(message = "密码不能为空")
    @Pattern(regexp = ValidationPatterns.PASSWORD, message = ValidationPatterns.PASSWORD_MESSAGE)
    private String password;

    /** 性别：0-未设置 1-男 2-女 */
    @Min(value = 0, message = "性别取值需为0-未设置/1-男/2-女")
    @Max(value = 2, message = "性别取值需为0-未设置/1-男/2-女")
    private Integer gender;

    /** 出生日期 yyyy-MM-dd */
    @Pattern(regexp = ValidationPatterns.DATE_OR_EMPTY, message = "出生日期格式需为yyyy-MM-dd")
    private String birthDate;

    /** 身高(cm) — 规范错误示例为「身高范围50-250cm」 */
    @DecimalMin(value = "50", message = "身高范围50-250cm")
    @DecimalMax(value = "250", message = "身高范围50-250cm")
    private Double height;

    /** 体重(kg) */
    @DecimalMin(value = "30", message = "体重范围30-300kg")
    @DecimalMax(value = "300", message = "体重范围30-300kg")
    private Double weight;

    /** 训练目标：增肌/减脂/保持 */
    @Pattern(regexp = "^(增肌|减脂|保持)?$", message = "训练目标需为：增肌/减脂/保持")
    private String trainingGoal;

    /** 训练年限：新手/进阶/老手 */
    @Pattern(regexp = "^(新手|进阶|老手)?$", message = "训练年限需为：新手/进阶/老手")
    private String trainingLevel;
}

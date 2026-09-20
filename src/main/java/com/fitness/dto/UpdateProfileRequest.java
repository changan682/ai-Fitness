package com.fitness.dto;

import com.fitness.common.ValidationPatterns;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 修改个人档案请求 — 所有字段均为可选，传了就更新 */
@Data
public class UpdateProfileRequest {

    /** 昵称规则与注册保持一致（规范 1.1） */
    @Size(min = 2, max = 20, message = "昵称长度需2-20字符")
    private String nickname;

    @Min(value = 0, message = "性别取值需为0-未设置/1-男/2-女")
    @Max(value = 2, message = "性别取值需为0-未设置/1-男/2-女")
    private Integer gender;

    @Pattern(regexp = ValidationPatterns.DATE_OR_EMPTY, message = "出生日期格式需为yyyy-MM-dd")
    private String birthDate;

    /** 身高(cm) */
    @DecimalMin(value = "50", message = "身高范围50-250cm")
    @DecimalMax(value = "250", message = "身高范围50-250cm")
    private Double height;

    /** 体重(kg) */
    @DecimalMin(value = "30", message = "体重范围30-300kg")
    @DecimalMax(value = "300", message = "体重范围30-300kg")
    private Double weight;

    @Pattern(regexp = "^(增肌|减脂|保持)?$", message = "训练目标需为：增肌/减脂/保持")
    private String trainingGoal;

    @Pattern(regexp = "^(新手|进阶|老手)?$", message = "训练年限需为：新手/进阶/老手")
    private String trainingLevel;

    /** 伤病记录 — 规范 1.4 入参为字符串数组，如 ["左肩旧伤","右膝"] */
    @Size(max = 20, message = "伤病记录最多20条")
    private List<String> injuryRecord;
}

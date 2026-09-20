package com.fitness.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 用户档案响应 — 对应提示词 1.3 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileResponse {
    private Long id;
    private String nickname;
    private Integer gender;
    private String birthDate;

    /** 身高(cm) — 与实体/DDL 的 DECIMAL(5,1) 对齐；JSON 输出与 Double 相同（如 175.0） */
    private BigDecimal height;

    /** 体重(kg) */
    private BigDecimal weight;

    private String trainingGoal;
    private String trainingLevel;

    /** 伤病记录数组，如 ["左肩旧伤"]（规范 1.3 要求为数组而非字符串） */
    private List<String> injuryRecord;

    private String phone;           // 脱敏后，如 138****8000
    private LocalDateTime createdAt;
}

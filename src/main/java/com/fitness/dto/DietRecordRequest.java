package com.fitness.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/** 饮食记录录入请求 */
@Data
public class DietRecordRequest {

    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "记录日期格式需为yyyy-MM-dd")
    private String recordDate;

    @NotBlank(message = "餐次不能为空")
    @Pattern(regexp = "^(早餐|午餐|晚餐|加餐)$", message = "餐次需为：早餐/午餐/晚餐/加餐")
    private String mealType;

    @NotBlank(message = "食物名称不能为空")
    private String foodName;

    @NotNull(message = "重量不能为空")
    @Min(value = 1, message = "重量至少为1克")
    private Integer weightG;
}

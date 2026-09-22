package com.fitness.exception;

import lombok.Getter;

/**
 * 统一错误码枚举
 * 按模块划分错误码范围，便于定位问题
 */
@Getter
public enum ErrorCode {

    // ==================== 系统级 ====================
    SUCCESS(0, "success"),
    TOKEN_INVALID(9001, "未登录或Token已过期"),
    SIGNATURE_INVALID(9002, "签名校验失败"),
    PARAM_INVALID(9003, "参数校验失败"),
    /** 刷新Token时机不合法（规范 1.7：仅过期前24小时内可刷新，每个Token仅1次）
     *  规范错误码表把 9001-9999 划给「系统/鉴权」，且只钉死 9001/9002/9003/9999，
     *  故此处在其区间内新增 9004，避免与「Token无效」语义混淆。 */
    TOKEN_REFRESH_NOT_ALLOWED(9004, "Token剩余有效期超过24小时或已刷新过，无需刷新"),
    SYSTEM_ERROR(9999, "系统内部错误"),

    // ==================== 用户模块 1001-1099 ====================
    PHONE_REGISTERED(1001, "该手机号已注册"),
    PASSWORD_ERROR(1002, "密码错误"),
    USER_NOT_FOUND(1003, "用户不存在"),
    /** 头像为空 / 超过 2MB / 非 JPG·PNG / 文件已损坏 —— 统一归为「上传的图片不合法」 */
    AVATAR_INVALID(1004, "头像格式或大小不合法"),

    // ==================== 训练记录模块 2001-2099 ====================
    TRAINING_RECORD_NOT_FOUND(2001, "训练记录不存在"),
    DATE_RANGE_INVALID(2002, "日期范围无效"),
    NO_TRAINING_RECORD(2003, "该日期无训练记录"),

    // ==================== 身体数据模块 3001-3099 ====================
    BODY_METRIC_DUPLICATE(3001, "当天已有身体数据记录，请使用修改接口"),
    /** 注：规范 3.1.1 的错误示例把「记录不存在」也写成 3001，与 3.1 的「重复录入」冲突。
     *  规范错误码表只钉死「3001-当天已记录」，故「不存在」按区间顺延取 3002，避免两个语义撞码。 */
    BODY_METRIC_NOT_FOUND(3002, "身体数据记录不存在"),

    // ==================== 饮食记录模块 4001-4099 ====================
    FOOD_NOT_FOUND(4001, "食物不存在于热量库"),

    // ==================== 训练计划模块 5001-5099 ====================
    TEMPLATE_NOT_FOUND(5001, "模板不存在"),

    // ==================== AI模块 6001-6099 ====================
    AI_TIMEOUT(6001, "AI服务超时"),
    AI_RESPONSE_ERROR(6002, "AI服务返回异常"),
    MILVUS_UNAVAILABLE(6003, "知识库未初始化/Milvus不可用"),
    EMBEDDING_ERROR(6004, "Embedding服务异常");

    /** 错误码 */
    private final int code;

    /** 错误消息 */
    private final String msg;

    ErrorCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }
}

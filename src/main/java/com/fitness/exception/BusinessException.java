package com.fitness.exception;

import lombok.Getter;

/**
 * 业务异常 — 抛出后由 GlobalExceptionHandler 统一捕获并返回友好提示
 * <p>
 * 使用方式：throw new BusinessException(ErrorCode.PASSWORD_ERROR);
 */
@Getter
public class BusinessException extends RuntimeException {

    /** 错误码 */
    private final int code;

    /** 使用 ErrorCode 枚举构造 */
    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMsg());
        this.code = errorCode.getCode();
    }

    /** 自定义消息（覆盖 ErrorCode 的默认消息） */
    public BusinessException(ErrorCode errorCode, String customMsg) {
        super(customMsg);
        this.code = errorCode.getCode();
    }

    /** 纯自定义错误（用于非标准错误场景） */
    public BusinessException(int code, String msg) {
        super(msg);
        this.code = code;
    }
}

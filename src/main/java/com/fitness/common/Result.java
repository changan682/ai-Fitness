package com.fitness.common;

import com.fitness.exception.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一API返回结构
 * T 为 data 字段的具体类型，null 表示无返回数据
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {

    /** 状态码：0=成功，非0=错误码 */
    private int code;

    /** 提示信息 */
    private String msg;

    /** 业务数据 */
    private T data;

    // ==================== 静态工厂方法 ====================

    /** 成功返回（带数据） */
    public static <T> Result<T> ok(T data) {
        return new Result<>(0, "success", data);
    }

    /** 成功返回（无数据） */
    public static <T> Result<T> ok() {
        return ok(null);
    }

    /**
     * 成功返回（带数据 + 自定义提示文案）
     * <p>
     * 接口清单中部分接口指定了业务化 msg（如 "档案已更新"、"成功录入3条训练记录"），
     * 用此重载返回，避免前端只能拿到 "success" 而无法直接展示。
     */
    public static <T> Result<T> ok(String msg, T data) {
        return new Result<>(0, msg, data);
    }

    /** 失败返回（错误码+自定义消息） */
    public static <T> Result<T> fail(int code, String msg) {
        return new Result<>(code, msg, null);
    }

    /** 失败返回（使用 ErrorCode 枚举） */
    public static <T> Result<T> fail(ErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMsg(), null);
    }

    /** 失败返回（ErrorCode + 自定义消息覆盖） */
    public static <T> Result<T> fail(ErrorCode errorCode, String msg) {
        return new Result<>(errorCode.getCode(), msg, null);
    }
}

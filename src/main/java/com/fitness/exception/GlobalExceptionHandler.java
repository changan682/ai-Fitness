package com.fitness.exception;

import com.fitness.common.Result;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.time.format.DateTimeParseException;
import java.util.stream.Collectors;

/**
 * 全局异常处理器 — 拦截所有未捕获异常，返回统一 Result 格式
 * <p>
 * 两条硬性约定（提示词「通用约定」+ 前端 axios 拦截器契约）：
 * <ol>
 *   <li><b>HTTP 状态码统一 200</b>：业务结果一律通过 body 里的 {@code code} 表达。
 *       前端按 2xx 分支解析 {@code data.code}，若这里返回 400/500 会走进网络错误分支，
 *       导致「参数校验失败」被当成系统故障提示。</li>
 *   <li><b>不向前端暴露堆栈</b>：堆栈只写服务端日志。</li>
 * </ol>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常 — 预期内的错误，返回对应错误码 */
    @ExceptionHandler(BusinessException.class)
    public Result<?> handleBusinessException(BusinessException e) {
        log.warn("业务异常: code={}, msg={}", e.getCode(), e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    /**
     * 请求体校验失败 — @Valid @RequestBody 触发
     * <p>
     * 文案按规范格式拼接为「参数校验失败：昵称长度需2-20字符」，
     * 不拼接字段名（规范的错误示例中没有字段名，前端直接展示 msg）。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<?> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getDefaultMessage() == null ? f.getField() : f.getDefaultMessage())
                .distinct()
                .collect(Collectors.joining("; "));
        String msg = detail.isBlank() ? ErrorCode.PARAM_INVALID.getMsg() : "参数校验失败：" + detail;
        log.warn("参数校验失败: {}", msg);
        return Result.fail(ErrorCode.PARAM_INVALID.getCode(), msg);
    }

    /** 方法参数校验失败 — @Validated + @RequestParam/@PathVariable 上的约束注解触发 */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<?> handleConstraintViolation(ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .distinct()
                .collect(Collectors.joining("; "));
        String msg = detail.isBlank() ? ErrorCode.PARAM_INVALID.getMsg() : "参数校验失败：" + detail;
        log.warn("参数校验失败: {}", msg);
        return Result.fail(ErrorCode.PARAM_INVALID.getCode(), msg);
    }

    /**
     * 缺少必填查询参数（如 /api/v1/training/records 未传 startDate）
     * <p>
     * 若不单独处理，会落到兜底 Exception 分支返回 9999，把「调用方少传参数」
     * 误报成「系统内部错误」。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<?> handleMissingParam(MissingServletRequestParameterException e) {
        String msg = "参数校验失败：缺少必填参数 " + e.getParameterName();
        log.warn(msg);
        return Result.fail(ErrorCode.PARAM_INVALID.getCode(), msg);
    }

    /** 参数类型不匹配（如 page=abc） */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<?> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String msg = "参数校验失败：参数 " + e.getName() + " 类型不正确";
        log.warn(msg);
        return Result.fail(ErrorCode.PARAM_INVALID.getCode(), msg);
    }

    /**
     * 缺少 multipart 必填部分（如 {@code /api/ai/pose-evaluate} 未传 image 文件）
     * <p>
     * 与 {@link MissingServletRequestParameterException} 同类：属于调用方漏传参数。
     * 若不单独处理就会落到兜底分支返回 9999，把「你没传图片」误报成「服务端内部错误」——
     * 这个坑就是靠 Java 侧 HTTP 入口的验收脚本（含 multipart）才发现的。
     */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public Result<?> handleMissingPart(MissingServletRequestPartException e) {
        String msg = "参数校验失败：缺少必填的文件字段 " + e.getRequestPartName();
        log.warn(msg);
        return Result.fail(ErrorCode.PARAM_INVALID.getCode(), msg);
    }

    /** multipart 请求本身不合法（未使用 multipart/form-data、分隔符损坏等） */
    @ExceptionHandler(MultipartException.class)
    public Result<?> handleMultipart(MultipartException e) {
        String msg = "参数校验失败：文件上传格式不正确，需为 multipart/form-data";
        log.warn("multipart 解析失败: {}", e.getMessage());
        return Result.fail(ErrorCode.PARAM_INVALID.getCode(), msg);
    }

    /**
     * 请求的 Content-Type 与接口声明不符
     * <p>
     * 典型场景：{@code /api/ai/pose-evaluate} 声明了 {@code consumes=multipart/form-data}，
     * 但调用方发的是 {@code application/x-www-form-urlencoded} 或 JSON。
     * 这本质上是调用方的用法问题，必须返回 9003，
     * 否则会落到兜底分支变成 9999「系统内部错误」，把人往排查服务端的方向带偏。
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public Result<?> handleMediaType(HttpMediaTypeNotSupportedException e) {
        String contentType = e.getContentType() == null ? "未知" : e.getContentType().toString();
        String supported = e.getSupportedMediaTypes().isEmpty()
                ? "见接口文档" : e.getSupportedMediaTypes().toString();
        String msg = "参数校验失败：不支持请求类型 " + contentType
                + "，该接口要求 " + supported;
        log.warn("Content-Type 不支持: {}", msg);
        return Result.fail(ErrorCode.PARAM_INVALID.getCode(), msg);
    }

    /** 请求体不是合法 JSON 或字段类型无法解析 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<?> handleNotReadable(HttpMessageNotReadableException e) {
        String msg = "参数校验失败：请求体格式错误，需为合法JSON";
        log.warn("请求体解析失败: {}", e.getMessage());
        return Result.fail(ErrorCode.PARAM_INVALID.getCode(), msg);
    }

    /**
     * 日期解析失败 — 覆盖控制器中 LocalDate.parse 的直调点
     * <p>
     * 这类错误本质是入参格式问题，必须返回 9003 而不是 9999。
     */
    @ExceptionHandler(DateTimeParseException.class)
    public Result<?> handleDateTimeParse(DateTimeParseException e) {
        String msg = "参数校验失败：日期格式错误，需为yyyy-MM-dd";
        log.warn("日期解析失败: 非法值={}", e.getParsedString());
        return Result.fail(ErrorCode.PARAM_INVALID.getCode(), msg);
    }

    /** 非法参数（如 PageRequest.of 收到 page<0） */
    @ExceptionHandler(IllegalArgumentException.class)
    public Result<?> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("非法参数: {}", e.getMessage());
        return Result.fail(ErrorCode.PARAM_INVALID);
    }

    /** 兜底异常 — 未预期的系统错误，堆栈只进服务端日志 */
    @ExceptionHandler(Exception.class)
    public Result<?> handleUnknown(Exception e) {
        log.error("系统异常: ", e);
        return Result.fail(ErrorCode.SYSTEM_ERROR);
    }
}

package com.fitness.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Python 侧统一响应信封 — 对应规范 models.py 的 {@code AgentResponse}
 * <p>
 * Python 所有 {@code /agent/v1/*} 接口都返回 {@code {success, message, data}}，
 * 因此 Java 用一个泛型信封承接，避免每个接口各写一份外壳。
 * <p>
 * {@code ignoreUnknown = true}：Python 后续版本新增字段时 Java 不应直接报错。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PythonEnvelope<T> {

    /** Python 侧业务是否成功 */
    private Boolean success;

    /** 提示信息 */
    private String message;

    /** 业务数据 */
    private T data;
}

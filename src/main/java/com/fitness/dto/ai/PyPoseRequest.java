package com.fitness.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 姿态评估请求 — Java → Python {@code POST /agent/v1/pose-evaluate}
 * <p>
 * 对应规范 models.py 的 {@code PoseEvaluateRequest}。
 * Java 侧接收 multipart 图片文件后，<b>先压缩到 ≤1MB 再转 Base64</b> 转发（规范 7.3）：
 * 原图直接 Base64 会膨胀约 33%（10MB → 约 13.3MB），同步接口扛不住。
 * Python 侧对 Base64 字符串长度和解码后体积都有上限校验，见 {@code PoseEvaluateRequest}。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PyPoseRequest {

    @JsonProperty("image_base64")
    private String imageBase64;

    @JsonProperty("action_name")
    private String actionName;
}

package com.fitness.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitness.common.Result;
import com.fitness.dto.ai.ToolInvokeRequest;
import com.fitness.dto.ai.ToolInvokeResult;
import com.fitness.exception.BusinessException;
import com.fitness.exception.ErrorCode;
import com.fitness.service.AgentToolService;
import com.fitness.util.HmacSignatureVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * Agent 工具网关 —— 接收 Python Agent 的**同步工具调用**（阶段二）。
 *
 * <h3>与 {@link AICallbackController} 的关系</h3>
 * 两者走**同一套 HMAC 认证**，都必须在 JWT 白名单里 —— 调用方是内网 Python，
 * 没有、也不该有用户 Token。区别只是方向与时机：
 * <ul>
 *   <li>回调：Python 主动推送结果，**异步**（MQ 消费后）；</li>
 *   <li>工具：Python 在 Agent 循环里**同步**取数，等结果再继续推理。</li>
 * </ul>
 * 复用同一套签名意味着**不需要新增任何认证机制** —— 这是阶段二成本最低的一环。
 *
 * <h3>为什么路径是固定的 {@code /invoke} 而不是 {@code /{toolName}}</h3>
 * 签名原文包含 path（{@code method\npath\ntimestamp\nsha256Hex(body)}）。
 * 路径里带变量意味着签名方与验签方要各自拼一遍，多一个工具就多一处可能拼错 ——
 * 而拼错的后果是「所有工具调用都验签失败」。因此工具名放在**签名保护的 body** 里。
 *
 * <h3>为什么用 {@code @RequestBody String} 而不是直接收 DTO</h3>
 * 与回调同理：验签必须基于**原始请求体字节**。先反序列化再重新序列化会让
 * 字段顺序/空格差异导致哈希不一致，表现为「验签随机失败」。
 */
@Slf4j
@RestController
@RequestMapping("/api/ai/tool")
@RequiredArgsConstructor
public class AIToolController {

    /**
     * 参与签名的路径 —— 必须与 Python 侧 {@code tool_gateway} 里的 path 逐字符一致。
     * 刻意写成常量而不是取 {@code request.getRequestURI()}（后者可能带 context-path）。
     */
    public static final String INVOKE_PATH = "/api/ai/tool/invoke";

    private final HmacSignatureVerifier signatureVerifier;
    private final ObjectMapper objectMapper;
    private final AgentToolService agentToolService;

    @PostMapping("/invoke")
    public Result<ToolInvokeResult> invoke(
            @RequestHeader(value = "X-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Signature", required = false) String signature,
            @RequestBody(required = false) String rawBody) throws Exception {

        byte[] bodyBytes = rawBody == null
                ? new byte[0] : rawBody.getBytes(StandardCharsets.UTF_8);

        // 防御 1：时间戳窗口（±5 分钟）—— 先查时间戳再验签，省一次 HMAC 计算
        if (!signatureVerifier.isTimestampFresh(timestamp, System.currentTimeMillis())) {
            log.warn("工具调用被拒：时间戳缺失/非法/超出窗口, X-Timestamp={}", timestamp);
            throw new BusinessException(ErrorCode.SIGNATURE_INVALID, "时间戳缺失或已过期");
        }

        // 防御 2：验签（签名原文覆盖 body 哈希，防止篡改 userId 越权取数）
        if (!signatureVerifier.isSignatureValid(
                "POST", INVOKE_PATH, timestamp, bodyBytes, signature)) {
            throw new BusinessException(ErrorCode.SIGNATURE_INVALID);
        }

        ToolInvokeRequest request = objectMapper.readValue(bodyBytes, ToolInvokeRequest.class);
        if (request == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "工具调用请求体不能为空");
        }

        ToolInvokeResult result = agentToolService.invoke(
                request.getTool(), request.getUserId(), request.getArgs());
        return Result.ok(result);
    }
}

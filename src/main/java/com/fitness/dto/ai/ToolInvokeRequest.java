package com.fitness.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Map;

/**
 * 工具调用请求 — Python Agent → Java 工具网关
 * <p>
 * 对应 {@code POST /api/ai/tool/invoke}。
 * <p>
 * <b>为什么是单一端点 + body 里带工具名，而不是 {@code /tool/{name}}</b>：
 * HMAC 签名原文里包含 <b>path</b>（见 {@code HmacSignatureVerifier}）。
 * 如果每个工具一个路径，签名方与验签方必须对「路径该怎么拼」达成一致，
 * 多一个工具就多一处可能拼错的地方；一旦不一致就是「所有调用都验签失败」。
 * 用固定路径 + body 描述工具，签名的 path 永远是同一个常量。
 * <p>
 * <b>为什么 {@code userId} 在 body 里而不是请求头</b>：它由签名保护。
 * 若放请求头且不在签名原文里，攻击者就能改 userId 去读别人的数据 ——
 * 而这种越权在日志里看起来完全正常。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ToolInvokeRequest {

    /** 工具名，必须命中 {@code AgentToolService.SUPPORTED_TOOLS} 白名单 */
    private String tool;

    /** 代表哪个用户执行（**由签名保护，绝不从请求头或 Token 取**） */
    @JsonProperty("user_id")
    private Long userId;

    /** 工具参数（各工具自行解析与校验） */
    private Map<String, Object> args;
}

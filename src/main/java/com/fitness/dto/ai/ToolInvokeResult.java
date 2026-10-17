package com.fitness.dto.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具调用结果 — 工具网关返回给 Python Agent 的 {@code data}
 * <p>
 * {@code data} 是工具的真实结果（结构化，交给大模型阅读）；
 * {@code meta} 是给编排层看的元信息，用于生成审计与降级说明。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolInvokeResult {

    /** 工具结果（各工具形状不同；训练记录会压缩成精简行以控 token） */
    private Object data;

    /** 元信息：{@code tool} / {@code elapsedMs} / {@code range} / {@code truncated} 等 */
    private Map<String, Object> meta;

    /**
     * 便捷构造：自动补 {@code elapsedMs}。
     * <p>
     * 刻意**不写 {@code source: mysql|redis}**：周统计等服务内部走
     * {@code RedisCacheService.getOrLoad}，Java 侧无法在不改动服务的情况下可靠得知
     * 这次是缓存命中还是回源 —— 与其给出可能错误的来源标记，
     * 不如只报工具名与耗时（"降级必须可见"的另一面是"来源也必须真实"）。
     */
    public static ToolInvokeResult of(Object data, String tool, long elapsedMs,
                                      Map<String, Object> extraMeta) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("tool", tool);
        meta.put("elapsedMs", elapsedMs);
        if (extraMeta != null) {
            meta.putAll(extraMeta);
        }
        return ToolInvokeResult.builder().data(data).meta(meta).build();
    }
}

package com.fitness.dto.ai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI 总结的缓存值 — Redis 里存的就是它
 * <p>
 * 为什么不直接缓存 {@code AiSummaryResponse}：响应里的 {@code cached} 字段是
 * 「本次读取是否命中缓存」的<b>调用结果</b>，而不是被缓存的内容本身 ——
 * 把它一起缓存会导致第二次读取时返回 cached=true 而第一次也变成 true，语义混乱。
 * <p>
 * 注意必须保留无参构造与 setter（用 {@code @Data} 而非 record）：
 * Redis 的 Jackson 序列化启用了 default typing（@class），
 * JDK record / 不可变集合在反序列化时会失败。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiSummaryCacheValue {

    /** Markdown 格式的训练总结 */
    private String summary;

    /** 生成时间（ISO8601，直接沿用 Python 返回的字符串，避免格式转换引入偏差） */
    private String generatedAt;

    /**
     * 输入数据快照（当日训练记录的 JSON 摘要）
     * <p>
     * 规范在 {@code t_ai_summary_cache.input_snapshot} 的注释里写明它的用途是
     * 「用于判断是否需要重新生成」。因此读取缓存时必须比对快照：
     * 如果用户又补录/修改了当天的训练记录，旧总结就不再可信，必须重新生成。
     * 这是「主动失效缓存」之外的第二道保险（防止漏删、或 Redis 与 DB 不同步）。
     */
    private String inputSnapshot;
}

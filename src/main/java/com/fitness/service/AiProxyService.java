package com.fitness.service;

import com.fitness.client.AiPythonClient;
import com.fitness.config.AiProperties;
import com.fitness.dto.AiChatRequest;
import com.fitness.dto.AiChatResponse;
import com.fitness.dto.AiKnowledgeHealthResponse;
import com.fitness.dto.AiPoseResponse;
import com.fitness.dto.AiRecommendRequest;
import com.fitness.dto.AiRecommendResponse;
import com.fitness.dto.ai.PyChatData;
import com.fitness.dto.ai.PyChatRequest;
import com.fitness.dto.ai.PyKnowledgeHealthData;
import com.fitness.dto.ai.PyPoseData;
import com.fitness.dto.ai.PyPoseRequest;
import com.fitness.dto.ai.PyRecommendData;
import com.fitness.dto.ai.PyRecommendRequest;
import com.fitness.exception.BusinessException;
import com.fitness.exception.ErrorCode;
import com.fitness.util.AiTimeUtil;
import com.fitness.util.ImageCompressor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * AI 代理服务 — 规范 7.2 / 7.3 / 7.4 / 7.5
 * <p>
 * 职责是「翻译」：把前端 camelCase 契约与 Python snake_case 协议互相转换，
 * 并在 Python 不可用时按规范第十一章第 7 条返回<b>预设兜底文案</b>（绝不让前端白屏）。
 * 业务组装逻辑（取数、对比、缓存）在 {@link AiSummaryService}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiProxyService {

    /** 规范 7.3 允许的动作名称 */
    private static final Set<String> SUPPORTED_POSE_ACTIONS =
            Set.of("深蹲", "卧推", "硬拉", "推举", "划船");

    /** 默认推荐数量（规范 7.2：count 可选，默认5） */
    private static final int DEFAULT_RECOMMEND_COUNT = 5;

    /** 前端允许上传的原图上限（与 application.yml 的 multipart 限制一致） */
    private static final long POSE_MAX_UPLOAD_BYTES = 10L * 1024 * 1024;

    /** 转发 Python 前的压缩目标：≤1MB（规范 7.3） */
    private static final long POSE_MAX_FORWARD_BYTES = ImageCompressor.DEFAULT_MAX_BYTES;

    /** 压缩时最长边上限 */
    private static final int POSE_MAX_FORWARD_EDGE = ImageCompressor.DEFAULT_MAX_EDGE;

    private final AiPythonClient aiPythonClient;
    private final AiProperties aiProperties;
    private final AiChatSessionService chatSessionService;

    /**
     * Agent 运行审计（阶段二）—— 字段注入而非构造参数：
     * 保持 {@code @RequiredArgsConstructor} 的三参构造不变，现有测试无需改动。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AgentRunAuditService agentRunAuditService;

    /** 7.2 动作智能推荐 */
    public AiRecommendResponse recommend(AiRecommendRequest req) {
        return withFallback(aiProperties.getFallback().getRecommend(), () -> {
            PyRecommendRequest py = new PyRecommendRequest();
            py.setTargetMuscle(req.getTargetMuscle());
            py.setEquipment(req.getEquipment());
            py.setCount(req.getCount() != null ? req.getCount() : DEFAULT_RECOMMEND_COUNT);

            PyRecommendData data = aiPythonClient.recommend(py);

            List<AiRecommendResponse.Recommendation> list = new ArrayList<>();
            if (data.getRecommendations() != null) {
                for (PyRecommendData.Recommendation item : data.getRecommendations()) {
                    list.add(AiRecommendResponse.Recommendation.builder()
                            .actionName(item.getActionName())
                            .targetMuscle(item.getTargetMuscle())
                            .focusArea(item.getFocusArea())
                            .recommendedSets(item.getRecommendedSets())
                            .recommendedReps(item.getRecommendedReps())
                            .difficulty(item.getDifficulty())
                            .notes(item.getNotes())
                            .equipment(item.getEquipment())
                            .build());
                }
            }

            return AiRecommendResponse.builder()
                    .recommendations(list)
                    .generatedAt(AiTimeUtil.parseIsoOrNow(data.getGeneratedAt()))
                    .build();
        });
    }

    /**
     * 7.3 动作姿态评估 — 图片压缩后转 Base64 再转发 Python
     * <p>
     * <b>为什么不直接把原图 Base64 转发</b>：Base64 会膨胀约 33%，10MB 原图变成约 13.3MB 的
     * JSON 字符串，同步接口的传输耗时和 Java 堆内存压力都不可接受。
     * 这里先压到 ≤1MB（Base64 后约 1.37MB）再转发 —— 多模态模型侧本来也会缩放输入，
     * 压缩不会降低姿态判定的有效信息量。
     */
    public AiPoseResponse evaluatePose(MultipartFile image, String actionName) {
        // 参数校验放在调用 Python 之前：明显的非法入参不该浪费一次跨语言往返
        if (image == null || image.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "图片文件不能为空");
        }
        if (actionName == null || actionName.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "动作名称不能为空");
        }
        if (!SUPPORTED_POSE_ACTIONS.contains(actionName)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "动作名称需为：" + String.join("/", SUPPORTED_POSE_ACTIONS));
        }

        return withFallback(aiProperties.getFallback().getPose(), () -> {
            byte[] compressed = compressPoseImage(image);

            PyPoseRequest py = new PyPoseRequest();
            py.setActionName(actionName);
            py.setImageBase64(Base64.getEncoder().encodeToString(compressed));

            PyPoseData data = aiPythonClient.evaluatePose(py);

            return AiPoseResponse.builder()
                    .score(data.getScore())
                    .scoreLevel(data.getScoreLevel())
                    .issues(data.getIssues())
                    .suggestions(data.getSuggestions())
                    .goodPoints(data.getGoodPoints())
                    // 如实透传来源：mock_local 表示这是模拟打分，前端必须显式标注出来
                    .dataSource(data.getDataSource())
                    .evaluatedAt(AiTimeUtil.parseIsoOrNow(data.getEvaluatedAt()))
                    .build();
        });
    }

    /**
     * 读取并压缩上传图片
     * <p>
     * 压缩失败（格式不支持 / 图片损坏）时直接抛 9003 参数错误，
     * <b>不降级为转发原图</b> —— 那会把一个明确的入参问题变成一次昂贵的无效跨语言调用。
     */
    private byte[] compressPoseImage(MultipartFile image) {
        byte[] raw;
        try {
            raw = image.getBytes();
        } catch (IOException e) {
            log.error("读取上传图片失败: filename={}", image.getOriginalFilename(), e);
            throw new BusinessException(ErrorCode.PARAM_INVALID, "图片读取失败，请重新上传");
        }

        if (raw.length > POSE_MAX_UPLOAD_BYTES) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "图片不能超过 10MB");
        }

        byte[] compressed;
        try {
            compressed = ImageCompressor.compress(raw, POSE_MAX_FORWARD_BYTES, POSE_MAX_FORWARD_EDGE);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, e.getMessage());
        }

        log.info("姿态评估图片已压缩: filename={}, 原始={}KB, 压缩后={}KB, Base64≈{}KB",
                image.getOriginalFilename(), raw.length / 1024, compressed.length / 1024,
                compressed.length * 4L / 3 / 1024);
        return compressed;
    }

    /**
     * 7.4 健身知识库 RAG 问答（含对话记忆 — 体验优化批次 C）
     * <p>
     * 规范明确「不做缓存，每次实时检索」—— 知识库会持续追加条目，
     * 缓存答案会让新知识失效。**注意区分**：这里不做的是"答案缓存"，
     * 而会话上下文是必须带的 —— 没有它，用户的追问（"那做几组？"）会答非所问。
     *
     * <h3>一轮问答的完整流程</h3>
     * <ol>
     *   <li>解析 sessionId：缺失/非法格式 → 新建一个（前端下次带回来）；</li>
     *   <li>取上下文：Redis 热层 → 未命中回表回填（read-through）；</li>
     *   <li>连同上文一起送 Python；</li>
     *   <li>把这一轮追加进记忆（Redis + 尽力而为落库）。</li>
     * </ol>
     * 记忆的任何一步失败都只降级为"这一轮没有上下文"，不影响用户拿到回答。
     */
    public AiChatResponse chat(Long userId, AiChatRequest req) {
        String sessionId = chatSessionService.normalizeSessionId(req.getSessionId());
        if (sessionId == null) {
            sessionId = chatSessionService.newSessionId();
        }
        List<AiChatSessionService.ChatTurn> history = chatSessionService.load(userId, sessionId);

        AiChatResponse response = withFallback(aiProperties.getFallback().getChat(), () -> {
            PyChatRequest py = new PyChatRequest();
            py.setQuestion(req.getQuestion());
            py.setCategory(req.getCategory());
            py.setUserId(userId);
            if (!history.isEmpty()) {
                py.setHistory(history.stream().map(turn -> {
                    PyChatRequest.Turn item = new PyChatRequest.Turn();
                    item.setRole(turn.getRole());
                    item.setContent(turn.getContent());
                    return item;
                }).toList());
            }

            PyChatData data = aiPythonClient.chat(py);

            List<AiChatResponse.Source> sources = new ArrayList<>();
            if (data.getSources() != null) {
                for (PyChatData.Source s : data.getSources()) {
                    sources.add(AiChatResponse.Source.builder()
                            .category(s.getCategory())
                            .title(s.getTitle())
                            .content(s.getContent())
                            .score(s.getScore())
                            // 分数口径必须一起透传：heuristic 表示这是内置兜底的合成分数
                            .scoreType(s.getScoreType())
                            .build());
                }
            }

            return AiChatResponse.builder()
                    .question(data.getQuestion() != null ? data.getQuestion() : req.getQuestion())
                    .answer(data.getAnswer())
                    .sources(sources)
                    // 降级标记原样透传给前端，让「兜底回答」在界面上可辨认
                    .dataSource(data.getDataSource())
                    .degraded(data.getDegraded())
                    .degradationReason(data.getDegradationReason())
                    // Agent 模式的工具调用证据：同样原样透传，不得吞掉 ——
                    // 它是「这轮到底有没有用知识库」的客观依据（非 Agent 模式为 null）
                    .toolCalls(data.getToolCalls())
                    .knowledgeUsed(data.getKnowledgeUsed())
                    .generatedAt(AiTimeUtil.parseIsoOrNow(data.getGeneratedAt()))
                    .build();
        });

        // 无论走的是真实链路还是兜底文案，都要记住这一轮；否则用户看到回答却"下一句就失忆"
        chatSessionService.appendTurn(userId, sessionId, req.getQuestion(), response.getAnswer(),
                response.getDataSource(), Boolean.TRUE.equals(response.getDegraded()));
        return response.toBuilder().sessionId(sessionId).build();
    }

    /** 「新对话」：只清 Redis 热层，长期历史保留（供后续"历史会话"能力使用） */
    public void resetChatSession(Long userId, String sessionId) {
        chatSessionService.clearHot(userId, chatSessionService.normalizeSessionId(sessionId));
    }

    /**
     * Agent 循环问答（阶段二）—— 与 {@link #chat} 并列的入口。
     *
     * <h3>为什么单独一个方法而不是给 chat 加开关</h3>
     * 两者失败语义与耗时量级都不同：<ol>
     *   <li>{@code /ai/chat} 走 30s 超时客户端，{@code /ai/agent} 走 90s 的
     *       （Agent 含多轮模型 + 工具往返，用 30s 会先超时）；</li>
     *   <li>Agent 需要落审计（{@link AgentRunAuditService}），chat 不需要；</li>
     *   <li>保留 {@code /ai/chat} 的**原样行为**是回滚保障 —— 新路径出问题，
     *       前端改一个路径就能退回去，不需要回滚代码。</li>
     * </ol>
     *
     * <h3>记入对话记忆</h3>
     * 与 chat 一致：无论真实链路还是兜底文案都把这一轮追加进记忆，
     * 否则用户"看到回答却下一句就失忆"。Agent 的多轮上下文同样依赖它。
     */
    public AiChatResponse agentChat(Long userId, AiChatRequest req) {
        String sessionId = chatSessionService.normalizeSessionId(req.getSessionId());
        if (sessionId == null) {
            sessionId = chatSessionService.newSessionId();
        }
        String resolvedSessionId = sessionId;
        List<AiChatSessionService.ChatTurn> history = chatSessionService.load(userId, resolvedSessionId);

        long started = System.currentTimeMillis();
        AiChatResponse response;
        try {
            response = withFallback(aiProperties.getFallback().getChat(), () -> {
                PyChatRequest py = new PyChatRequest();
                py.setQuestion(req.getQuestion());
                py.setCategory(req.getCategory());
                py.setUserId(userId);
                if (!history.isEmpty()) {
                    py.setHistory(history.stream().map(turn -> {
                        PyChatRequest.Turn item = new PyChatRequest.Turn();
                        item.setRole(turn.getRole());
                        item.setContent(turn.getContent());
                        return item;
                    }).toList());
                }

                PyChatData data = aiPythonClient.agentChat(py);
                return toAgentResponse(req.getQuestion(), data);
            });
        } catch (BusinessException e) {
            // 失败也要留审计：只记成功的审计是有偏的，出问题时最需要的是失败那一轮
            if (agentRunAuditService != null) {
                agentRunAuditService.recordFailure(userId, resolvedSessionId, req.getQuestion(),
                        System.currentTimeMillis() - started, e.getCode(), e.getMessage());
            }
            throw e;
        }

        long latencyMs = System.currentTimeMillis() - started;
        chatSessionService.appendTurn(userId, resolvedSessionId, req.getQuestion(),
                response.getAnswer(), response.getDataSource(),
                Boolean.TRUE.equals(response.getDegraded()));
        if (agentRunAuditService != null) {
            agentRunAuditService.record(userId, resolvedSessionId, req.getQuestion(), response, latencyMs);
        }
        return response.toBuilder().sessionId(resolvedSessionId).build();
    }

    /**
     * Agent 专用键名映射：Python 的 snake_case → 前端约定的 camelCase。
     * <p>
     * <b>为什么需要这一步</b>：{@code steps} / {@code agent} / {@code tool_calls} 在两侧都是
     * <b>未类型化的 Map/List</b>，因此 Jackson 的 {@code @JsonProperty} 帮不上忙 ——
     * 它们会原样透传，前端拿到的是 {@code elapsed_ms} 而不是 {@code elapsedMs}。
     * <p>
     * 这不是理论问题：阶段二验收时前端读 {@code stoppedBy} 拿到 {@code undefined}，
     * 界面上"停止原因"整块空白，而链路本身是通的 —— 这类缺陷最难查，
     * 因为没有任何一处报错。{@code AiProtocolContractTest} 用断言把它钉住。
     * <p>
     * 之所以在这里翻译而不是让 Python 直接吐 camelCase：Python 侧「字段一律 snake_case」
     * 是它与 Java 的通用约定，为两个字段破例会让后面的人分不清该跟哪套。
     */
    private static final Map<String, String> STEP_KEY_MAP = Map.of(
            "elapsed_ms", "elapsedMs"
    );

    private static final Map<String, String> AGENT_KEY_MAP = Map.of(
            "steps_used", "stepsUsed",
            "latency_ms", "latencyMs",
            "stopped_by", "stoppedBy",
            "max_steps", "maxSteps"
    );

    private static final Map<String, String> TOOL_CALL_KEY_MAP = Map.of(
            "knowledge_used", "knowledgeUsed",
            "best_score", "bestScore"
    );

    /** 按映射表重命名一层 Map 的键；未登记的键原样保留（未来加字段不会被吞掉） */
    private static Map<String, Object> renameKeys(Map<String, Object> source,
                                                  Map<String, String> mapping) {
        if (source == null) {
            return null;
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        source.forEach((key, value) -> result.put(mapping.getOrDefault(key, key), value));
        return result;
    }

    /** 对列表里的每个 Map 做键名映射 */
    private static List<Map<String, Object>> renameKeys(List<Map<String, Object>> source,
                                                        Map<String, String> mapping) {
        if (source == null) {
            return null;
        }
        List<Map<String, Object>> result = new ArrayList<>(source.size());
        for (Map<String, Object> item : source) {
            result.add(item == null ? null : renameKeys(item, mapping));
        }
        return result;
    }

    /** 把 Python 的 Agent 响应映射成前端契约（含 steps / agent —— 让「AI 用了什么数据」可验证） */
    private AiChatResponse toAgentResponse(String question, PyChatData data) {
        List<AiChatResponse.Source> sources = new ArrayList<>();
        if (data.getSources() != null) {
            for (PyChatData.Source s : data.getSources()) {
                sources.add(AiChatResponse.Source.builder()
                        .category(s.getCategory())
                        .title(s.getTitle())
                        .content(s.getContent())
                        .score(s.getScore())
                        .scoreType(s.getScoreType())
                        .build());
            }
        }

        List<Map<String, Object>> steps = renameKeys(data.getSteps(), STEP_KEY_MAP);
        List<Map<String, Object>> toolCalls = renameKeys(data.getToolCalls(), TOOL_CALL_KEY_MAP);

        Boolean knowledgeUsed = data.getKnowledgeUsed();
        if (knowledgeUsed == null && steps != null) {
            // Python 侧没给标记时自行推断：有一步成功且用的是知识库工具，就算用上了
            knowledgeUsed = steps.stream().anyMatch(step -> step != null
                    && Boolean.TRUE.equals(step.get("ok"))
                    && Boolean.TRUE.equals(step.get("knowledgeUsed")));
        }

        return AiChatResponse.builder()
                .question(data.getQuestion() != null ? data.getQuestion() : question)
                .answer(data.getAnswer())
                .sources(sources)
                .dataSource(data.getDataSource())
                .degraded(data.getDegraded())
                .degradationReason(data.getDegradationReason())
                .toolCalls(toolCalls)
                .knowledgeUsed(knowledgeUsed)
                .steps(steps)
                .agent(renameKeys(data.getAgent(), AGENT_KEY_MAP))
                .generatedAt(AiTimeUtil.parseIsoOrNow(data.getGeneratedAt()))
                .build();
    }

    /**
     * 7.5 Milvus 知识库健康检查
     * <p>
     * 该接口的失败语义与其它 AI 接口不同：规范要求 Milvus 不可用时返回
     * {@code code=6003} 且 {@code data={milvusConnected:false}}，
     * 属于「可展示的状态」而非「报错」，因此这里不做兜底文案替换。
     */
    public AiKnowledgeHealthResponse knowledgeHealth() {
        try {
            PyKnowledgeHealthData data = aiPythonClient.knowledgeHealth();
            return AiKnowledgeHealthResponse.builder()
                    .milvusConnected(data.getMilvusConnected())
                    .collectionName(data.getCollectionName())
                    .totalDocuments(data.getTotalDocuments())
                    .lastUpdated(data.getLastUpdated())
                    .indexType(data.getIndexType())
                    .embeddingDim(data.getEmbeddingDim())
                    .build();
        } catch (BusinessException e) {
            log.warn("知识库健康检查失败: code={}, msg={}", e.getCode(), e.getMessage());
            // 连不上 Python 也说明知识库不可用，统一按 6003 返回可展示状态
            throw new BusinessException(ErrorCode.MILVUS_UNAVAILABLE, "Milvus连接失败，知识库不可用");
        }
    }

    /**
     * 统一降级包装 — 规范第十一章第 7 条
     * <p>
     * Python 超时/报错时把 msg 换成预设兜底文案，错误码保持不变：
     * 前端既能按 code 做逻辑判断，又能直接把 msg 展示给用户。
     * <p>
     * <b>例外：入参错误（9003）原样抛出，不套兜底文案。</b>
     * 「照片无法判断姿态，请换一张清晰的」和「AI 服务暂时不可用」是两回事：
     * 前者要用户改输入，后者要用户等一等。把前者伪装成后者，
     * 用户只会拿着同一张看不清的照片反复重试。
     */
    private <T> T withFallback(String fallbackText, Supplier<T> call) {
        try {
            return call.get();
        } catch (BusinessException e) {
            if (e.getCode() == ErrorCode.PARAM_INVALID.getCode()) {
                log.warn("AI 入参校验失败，按原样返回给前端: {}", e.getMessage());
                throw e;
            }
            log.warn("AI 服务不可用，返回兜底文案: code={}, 原始信息={}", e.getCode(), e.getMessage());
            throw new BusinessException(e.getCode(), fallbackText);
        }
    }
}

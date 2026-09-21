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
     * 7.4 健身知识库 RAG 问答
     * <p>
     * 规范明确「不做缓存，每次实时检索」—— 知识库会持续追加条目，
     * 缓存答案会让新知识失效。
     */
    public AiChatResponse chat(Long userId, AiChatRequest req) {
        return withFallback(aiProperties.getFallback().getChat(), () -> {
            PyChatRequest py = new PyChatRequest();
            py.setQuestion(req.getQuestion());
            py.setCategory(req.getCategory());
            py.setUserId(userId);

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
                    .generatedAt(AiTimeUtil.parseIsoOrNow(data.getGeneratedAt()))
                    .build();
        });
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

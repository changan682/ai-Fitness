package com.fitness.client;

import com.fitness.config.AiProperties;
import com.fitness.dto.ai.PyAgentHealthData;
import com.fitness.dto.ai.PyChatData;
import com.fitness.dto.ai.PyChatRequest;
import com.fitness.dto.ai.PyKnowledgeHealthData;
import com.fitness.dto.ai.PyPoseData;
import com.fitness.dto.ai.PyPoseRequest;
import com.fitness.dto.ai.PyRecommendData;
import com.fitness.dto.ai.PyRecommendRequest;
import com.fitness.dto.ai.PySummaryData;
import com.fitness.dto.ai.PySummaryRequest;
import com.fitness.dto.ai.PythonEnvelope;
import com.fitness.exception.BusinessException;
import com.fitness.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Python Agent HTTP 客户端 — Java 侧唯一的跨语言出口
 * <p>
 * 设计要点：
 * <ul>
 *   <li><b>统一拆信封</b>：Python 所有接口返回 {@code {success, message, data}}，
 *       这里统一校验 success 并把 data 交给业务层，业务层不必重复判空。</li>
 *   <li><b>统一异常语义</b>：连接失败/超时 → 6001(AI_TIMEOUT)，Python 返回 5xx → 6002(AI_RESPONSE_ERROR)，
 *       由 AIController 侧按规范第十一章第 7 条转成兜底文案，绝不让前端白屏。</li>
 *   <li><b>降级不吞异常</b>：这里只负责把底层异常翻译成业务异常，
 *       兜底文案的选取交给调用方（不同功能文案不同，见 {@link AiProperties.Fallback}）。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiPythonClient {

    private static final String PATH_SUMMARY = "/agent/v1/summary";
    private static final String PATH_RECOMMEND = "/agent/v1/recommend";
    private static final String PATH_POSE = "/agent/v1/pose-evaluate";
    private static final String PATH_CHAT = "/agent/v1/chat";
    private static final String PATH_HEALTH = "/agent/v1/health";
    private static final String PATH_KNOWLEDGE_HEALTH = "/agent/v1/knowledge/health";

    /**
     * 仅用于解析 Python 的错误响应体。
     * <p>
     * 用静态实例而不注入 Spring 的 ObjectMapper：这里只需要读一个字段，
     * 而 Jackson 的 {@code ObjectMapper} 读取操作本身是线程安全的，
     * 没必要为它增加构造依赖（也就不影响现有测试的构造方式）。
     */
    private static final ObjectMapper ERROR_MAPPER = new ObjectMapper();

    private final RestClient aiRestClient;

    /** 训练总结 — Python 侧生成 Markdown */
    public PySummaryData generateSummary(PySummaryRequest request) {
        PySummaryData data = post(PATH_SUMMARY, request,
                new ParameterizedTypeReference<PythonEnvelope<PySummaryData>>() {});
        // 验证标准（提示词第726行）：日志需能打印出「Java调Python成功」
        log.info("Java调Python成功: path={}, summaryLength={}",
                PATH_SUMMARY, data.getSummary() == null ? 0 : data.getSummary().length());
        return data;
    }

    /** 动作推荐 */
    public PyRecommendData recommend(PyRecommendRequest request) {
        PyRecommendData data = post(PATH_RECOMMEND, request,
                new ParameterizedTypeReference<PythonEnvelope<PyRecommendData>>() {});
        log.info("Java调Python成功: path={}, 推荐动作数={}",
                PATH_RECOMMEND, data.getRecommendations() == null ? 0 : data.getRecommendations().size());
        return data;
    }

    /** 姿态评估（图片已由调用方转 Base64） */
    public PyPoseData evaluatePose(PyPoseRequest request) {
        PyPoseData data = post(PATH_POSE, request,
                new ParameterizedTypeReference<PythonEnvelope<PyPoseData>>() {});
        log.info("Java调Python成功: path={}, score={}", PATH_POSE, data.getScore());
        return data;
    }

    /** 知识库 RAG 问答 */
    public PyChatData chat(PyChatRequest request) {
        PyChatData data = post(PATH_CHAT, request,
                new ParameterizedTypeReference<PythonEnvelope<PyChatData>>() {});
        log.info("Java调Python成功: path={}, 引用来源数={}",
                PATH_CHAT, data.getSources() == null ? 0 : data.getSources().size());
        return data;
    }

    /**
     * Python 服务健康探测
     * <p>
     * 这个方法不抛异常（返回 null 表示不可用）：它被 {@code /api/v1/health} 调用，
     * 健康检查接口本身不能因为依赖不可用而失败。
     */
    public PyAgentHealthData health() {
        try {
            PythonEnvelope<PyAgentHealthData> envelope = aiRestClient.get()
                    .uri(PATH_HEALTH)
                    .retrieve()
                    .body(new ParameterizedTypeReference<PythonEnvelope<PyAgentHealthData>>() {});
            return unwrap(envelope, PATH_HEALTH);
        } catch (Exception e) {
            log.debug("Python 健康检查失败（第4周后 Python 未启动时属正常）: {}", e.getMessage());
            return null;
        }
    }

    /** Milvus 知识库健康（第5-6周接入 Milvus 后才有真实数据） */
    public PyKnowledgeHealthData knowledgeHealth() {
        return get(PATH_KNOWLEDGE_HEALTH,
                new ParameterizedTypeReference<PythonEnvelope<PyKnowledgeHealthData>>() {});
    }

    // ==================== 内部通用逻辑 ====================

    private <T> T post(String path, Object body, ParameterizedTypeReference<PythonEnvelope<T>> type) {
        try {
            PythonEnvelope<T> envelope = aiRestClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(type);
            return unwrap(envelope, path);
        } catch (ResourceAccessException e) {
            // 连接失败或读取超时 —— 属于「Python 不可用」，走降级文案
            log.warn("调用Python失败(连接/超时): path={}, reason={}", path, e.getMessage());
            throw new BusinessException(ErrorCode.AI_TIMEOUT);
        } catch (RestClientResponseException e) {
            // Python 返回 4xx/5xx
            log.warn("调用Python返回错误: path={}, status={}, body={}",
                    path, e.getStatusCode(), abbreviate(e.getResponseBodyAsString()));
            if (e.getStatusCode().is4xxClientError()) {
                // 4xx 表示「Python 认为入参有问题」（FastAPI 校验失败 / AgentInputError），
                // 语义是参数错误 9003，而不是「AI 服务挂了」6002。
                // 必须把具体原因带上去：否则用户上传一张看不清的照片，
                // 只会看到「AI 服务暂时不可用」而反复重试，永远不知道要换一张照片。
                throw new BusinessException(ErrorCode.PARAM_INVALID, extractPythonMessage(e));
            }
            throw new BusinessException(ErrorCode.AI_RESPONSE_ERROR);
        }
    }

    private <T> T get(String path, ParameterizedTypeReference<PythonEnvelope<T>> type) {
        try {
            return unwrap(aiRestClient.get().uri(path).retrieve().body(type), path);
        } catch (ResourceAccessException e) {
            log.warn("调用Python失败(连接/超时): path={}, reason={}", path, e.getMessage());
            throw new BusinessException(ErrorCode.AI_TIMEOUT);
        } catch (RestClientResponseException e) {
            log.warn("调用Python返回错误: path={}, status={}", path, e.getStatusCode());
            throw new BusinessException(ErrorCode.AI_RESPONSE_ERROR);
        }
    }

    /** 拆信封：校验 success 并取出 data */
    private <T> T unwrap(PythonEnvelope<T> envelope, String path) {
        if (envelope == null) {
            log.warn("Python 返回空响应: path={}", path);
            throw new BusinessException(ErrorCode.AI_RESPONSE_ERROR);
        }
        if (!Boolean.TRUE.equals(envelope.getSuccess())) {
            log.warn("Python 业务失败: path={}, message={}", path, envelope.getMessage());
            throw new BusinessException(ErrorCode.AI_RESPONSE_ERROR, envelope.getMessage());
        }
        if (envelope.getData() == null) {
            log.warn("Python 业务成功但 data 为空: path={}", path);
            throw new BusinessException(ErrorCode.AI_RESPONSE_ERROR);
        }
        return envelope.getData();
    }

    /** 截断响应体，避免把整页 HTML 错误页打进日志 */
    private String abbreviate(String body) {
        if (body == null) return null;
        return body.length() <= 300 ? body : body.substring(0, 300) + "...";
    }

    /**
     * 从 Python 的错误信封里取出可读的 message
     * <p>
     * Python 侧所有错误都走统一信封 {@code {success:false, message:"...", data:null}}
     * （见 {@code app/main.py} 的 exception_handler），因此优先取 {@code message}；
     * 同时兼容 FastAPI 原生的 {@code {"detail": ...}} 形态以防漏接。
     * 解析失败就回退到 9003 的默认文案 —— 「取错误信息」这一步本身绝不能再抛异常。
     */
    private String extractPythonMessage(RestClientResponseException e) {
        try {
            JsonNode root = ERROR_MAPPER.readTree(e.getResponseBodyAsString());
            String message = root.path("message").asText("");
            if (!message.isBlank()) {
                return message;
            }
            String detail = root.path("detail").asText("");
            if (!detail.isBlank()) {
                return detail;
            }
        } catch (Exception ignored) {
            // 见方法注释：解析失败不是错误，用默认文案即可
        }
        return ErrorCode.PARAM_INVALID.getMsg();
    }
}

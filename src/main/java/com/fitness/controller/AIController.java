package com.fitness.controller;

import com.fitness.common.BaseController;
import com.fitness.common.Result;
import com.fitness.dto.AiChatRequest;
import com.fitness.dto.AiChatResponse;
import com.fitness.dto.AiKnowledgeHealthResponse;
import com.fitness.dto.AiPoseResponse;
import com.fitness.dto.AiRecommendRequest;
import com.fitness.dto.AiRecommendResponse;
import com.fitness.dto.AiSummaryRequest;
import com.fitness.dto.AiSummaryResponse;
import com.fitness.service.AiProxyService;
import com.fitness.service.AiSummaryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;

/**
 * AI 代理模块 Controller — /api/ai/*（5个接口，Java 转发 Python）
 * <p>
 * 路径约定（规范第十一章第 3 条）：Java 代理 AI 接口统一用 {@code /api/ai/xxx}，
 * 内部转发到 Python 的 {@code /agent/v1/xxx}（该前缀仅内网可见，不对外暴露）。
 * <p>
 * 全部接口需要 JWT 鉴权：它们都涉及用户私有数据（训练记录、图片）。
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AIController extends BaseController {

    private final AiSummaryService aiSummaryService;
    private final AiProxyService aiProxyService;

    /**
     * 7.1 生成训练智能总结 ⭐MVP核心
     * <p>
     * 请求体可为空（等同于「不传 date」= 统计今天），因此 date 用可选包装。
     */
    @PostMapping("/summary")
    public Result<AiSummaryResponse> generateSummary(
            HttpServletRequest request,
            @Valid @RequestBody(required = false) AiSummaryRequest req) {
        LocalDate date = (req != null && req.getDate() != null && !req.getDate().isBlank())
                ? LocalDate.parse(req.getDate())
                : LocalDate.now();
        AiSummaryResponse response = aiSummaryService.generateSummary(getUserId(request), date);
        return Result.ok(response);
    }

    /** 7.2 动作智能推荐 */
    @PostMapping("/recommend")
    public Result<AiRecommendResponse> recommend(@Valid @RequestBody AiRecommendRequest req) {
        return Result.ok(aiProxyService.recommend(req));
    }

    /** 7.3 动作姿态评估（multipart/form-data：image + actionName） */
    @PostMapping(value = "/pose-evaluate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<AiPoseResponse> evaluatePose(
            @RequestPart("image") MultipartFile image,
            @RequestParam("actionName") String actionName) {
        return Result.ok(aiProxyService.evaluatePose(image, actionName));
    }

    /** 7.4 健身知识库 RAG 问答 ⭐Milvus核心（含对话记忆） */
    @PostMapping("/chat")
    public Result<AiChatResponse> chat(HttpServletRequest request,
                                      @Valid @RequestBody AiChatRequest req) {
        return Result.ok(aiProxyService.chat(getUserId(request), req));
    }

    /**
     * 7.6 开启新对话（体验优化批次 C）
     * <p>
     * 只清该会话的 Redis 热层，`t_ai_chat_history` 里的长期历史保留 ——
     * 因此"新对话"的语义是"切断上下文"，而不是"删掉聊天记录"。
     * 前端拿到新会话后应换一个 sessionId 继续提问。
     */
    @PostMapping("/chat/new-session")
    public Result<Void> newChatSession(HttpServletRequest request,
                                       @RequestParam(required = false) String sessionId) {
        aiProxyService.resetChatSession(getUserId(request), sessionId);
        return Result.ok("已开启新对话", null);
    }

    /** 7.5 Milvus 知识库健康检查 */
    @GetMapping("/knowledge/health")
    public Result<AiKnowledgeHealthResponse> knowledgeHealth() {
        return Result.ok(aiProxyService.knowledgeHealth());
    }
}

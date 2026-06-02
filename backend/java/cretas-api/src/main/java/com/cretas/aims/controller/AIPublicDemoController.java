package com.cretas.aims.controller;

import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.IntentExecutorService;
import com.cretas.aims.service.LinUCBService;
import com.cretas.aims.service.ResultFormatterService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import com.cretas.aims.util.ErrorSanitizer;

/**
 * AI公开演示控制器
 *
 * 提供无需登录的AI意图识别演示API：
 * - 查询类意图(LOW敏感度)可正常执行
 * - 写入类意图(MEDIUM/HIGH/CRITICAL)返回权限提示
 *
 * 用于演示页面展示AI能力，不暴露敏感操作
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-18
 */
@Slf4j
@RestController
@RequestMapping("/api/public/ai-demo")
@RequiredArgsConstructor
@Tag(name = "AI公开演示", description = "无需登录的AI意图识别演示API")
@CrossOrigin(origins = {"https://www.cretaceousfuture.com", "http://139.196.165.140:8086", "http://localhost:5173"})
public class AIPublicDemoController {

    private final AIIntentService aiIntentService;
    private final IntentExecutorService intentExecutorService;
    private final ResultFormatterService resultFormatterService;
    private final LinUCBService linUCBService;

    // 安全：演示用沙盒工厂 ID（非真实生产工厂）
    // 若 F_DEMO 不存在则查询会返回空结果（安全降级），不会暴露真实业务数据
    private static final String DEMO_FACTORY_ID = "F_DEMO";

    // 演示用的默认用户角色 — 降级为只读角色，减少攻击面
    private static final String DEMO_USER_ROLE = "viewer";

    // 允许执行的敏感度级别（只允许查询类）
    private static final Set<String> ALLOWED_SENSITIVITY_LEVELS = new HashSet<>(Arrays.asList("LOW"));

    // 需要上下文解析的指代词列表
    private static final Set<String> REFERENCE_WORDS = new HashSet<>(Arrays.asList(
            "这批", "那批", "上一批", "刚才那个", "这个", "那个", "它", "这些", "那些",
            "上面的", "前面的", "刚刚的", "之前的"
    ));

    // ==================== IP Rate Limiting ====================

    /**
     * IP rate limiter: max 10 requests per minute per IP
     * Prevents LLM quota abuse from public demo endpoints
     */
    private final Cache<String, AtomicLong> ipRequestCounts = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .maximumSize(5000)
            .build();

    private static final long MAX_REQUESTS_PER_MINUTE = 10;

    private boolean isRateLimited(jakarta.servlet.http.HttpServletRequest httpRequest) {
        String ip = httpRequest.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty()) {
            ip = httpRequest.getRemoteAddr();
        } else {
            ip = ip.split(",")[0].trim();
        }
        AtomicLong count = ipRequestCounts.get(ip, k -> new AtomicLong(0));
        return count.incrementAndGet() > MAX_REQUESTS_PER_MINUTE;
    }

    // ==================== Demo 会话管理 ====================

    /**
     * 临时用户ID生成器
     * 使用负数ID区分演示用户和真实用户
     */
    private static final AtomicLong tempUserIdGenerator = new AtomicLong(-1000L);

    /**
     * Demo 会话缓存
     * - 30分钟过期
     * - 最多1000个并发会话
     * - 启用统计以便监控
     */
    private final Cache<String, DemoSession> demoSessions = Caffeine.newBuilder()
            .expireAfterAccess(30, TimeUnit.MINUTES)
            .maximumSize(1000)
            .recordStats()
            .build();

    /**
     * Demo 会话信息
     */
    @Data
    @AllArgsConstructor
    public static class DemoSession {
        private Long tempUserId;
        private String sessionId;
        private LocalDateTime createdAt;
        private LocalDateTime lastAccessedAt;
    }

    @PostMapping("/execute")
    @Operation(summary = "演示执行AI意图", description = "无需登录的AI意图识别与执行演示。查询类操作可执行，写入类操作需要权限验证。支持多轮对话。")
    public ResponseEntity<ApiResponse<IntentExecuteResponse>> executeDemo(
            @RequestBody DemoExecuteRequest request,
            jakarta.servlet.http.HttpServletRequest httpRequest) {

        if (isRateLimited(httpRequest)) {
            log.warn("AI Demo rate limited: IP={}", httpRequest.getRemoteAddr());
            return ResponseEntity.status(429).body(ApiResponse.error("请求过于频繁，请稍后再试"));
        }

        String userInput = request.getUserInput();
        log.info("AI演示请求: input='{}', sessionId={}",
                userInput != null && userInput.length() > 50 ? userInput.substring(0, 50) + "..." : userInput,
                request.getSessionId());

        // 0. 获取或创建 Demo 会话
        DemoSession session = getOrCreateSession(request.getSessionId());
        String sessionId = session.getSessionId();
        Long demoUserId = session.getTempUserId();

        log.debug("Demo会话: sessionId={}, tempUserId={}, isNew={}",
                sessionId, demoUserId, request.getSessionId() == null);

        // 1. 检测未解析的指代词（只有在没有会话上下文时才需要）
        // 如果有会话ID，说明可能在多轮对话中，指代词可能可以解析
        if (request.getSessionId() == null) {
            String unresolvedRef = detectUnresolvedReference(userInput);
            if (unresolvedRef != null) {
                log.info("AI演示检测到未解析指代词: '{}', 返回会话ID供后续对话", unresolvedRef);
                IntentExecuteResponse response = IntentExecuteResponse.builder()
                        .intentRecognized(false)
                        .status("NEED_MORE_INFO")
                        .message(buildClarificationMessage(unresolvedRef))
                        .clarificationQuestions(buildClarificationQuestions(unresolvedRef))
                        .sessionId(sessionId)  // 返回会话ID供前端续接
                        .executedAt(LocalDateTime.now())
                        .build();
                return ResponseEntity.ok(ApiResponse.success(response));
            }
        }

        // 2. 识别意图（传递完整的会话参数）
        IntentMatchResult matchResult = aiIntentService.recognizeIntentWithConfidence(
                userInput, DEMO_FACTORY_ID, 1, demoUserId, DEMO_USER_ROLE, sessionId);

        // 2.5. W0 (2026-06-02): 边界弃权 —— 识别引擎判定置信度不足/top-2 接近，
        // 返回澄清问题让用户在 top-2 之间选择，而不是静默猜测或直接报"未识别"。
        if (!matchResult.hasMatch() && matchResult.getClarificationQuestion() != null) {
            java.util.List<String> labels = matchResult.getTopCandidates() != null
                    ? matchResult.getTopCandidates().stream()
                        .map(c -> c.getIntentName() + " (" + String.format("%.0f%%", c.getConfidence() * 100) + ")")
                        .collect(java.util.stream.Collectors.toList())
                    : java.util.List.of();
            return ResponseEntity.ok(ApiResponse.success(IntentExecuteResponse.builder()
                    .intentRecognized(false).status("NEED_MORE_INFO")
                    .message(matchResult.getClarificationQuestion())
                    .clarificationQuestions(labels).sessionId(sessionId)
                    .executedAt(java.time.LocalDateTime.now()).build()));
        }

        // 3. 如果没有匹配到意图
        if (!matchResult.hasMatch()) {
            IntentExecuteResponse response = IntentExecuteResponse.builder()
                    .intentRecognized(false)
                    .status("NOT_RECOGNIZED")
                    .message("未能识别您的意图，请尝试更具体的描述")
                    .sessionId(sessionId)  // 返回会话ID供前端续接对话
                    .executedAt(LocalDateTime.now())
                    .build();
            return ResponseEntity.ok(ApiResponse.success(response));
        }

        AIIntentConfig matchedIntent = matchResult.getBestMatch();
        String sensitivityLevel = matchedIntent.getSensitivityLevel();

        // 修复 NPE: 部分意图的 sensitivityLevel 可能为 null，默认为 LOW (允许查询操作)
        if (sensitivityLevel == null) {
            sensitivityLevel = "LOW";
            log.warn("意图 {} 的 sensitivityLevel 为空，使用默认值 LOW", matchedIntent.getIntentCode());
        }

        log.info("AI演示意图匹配: intentCode={}, sensitivity={}, confidence={}",
                matchedIntent.getIntentCode(), sensitivityLevel, matchResult.getConfidence());

        // 4. 检查敏感度级别 - 只允许LOW级别的查询操作
        if (!ALLOWED_SENSITIVITY_LEVELS.contains(sensitivityLevel)) {
            // 写入类操作，返回权限提示
            IntentExecuteResponse response = IntentExecuteResponse.builder()
                    .intentRecognized(true)
                    .intentCode(matchedIntent.getIntentCode())
                    .intentName(matchedIntent.getIntentName())
                    .intentCategory(matchedIntent.getIntentCategory())
                    .sensitivityLevel(sensitivityLevel)
                    .confidence(matchResult.getConfidence())
                    .matchMethod(matchResult.getMatchMethod() != null ?
                            matchResult.getMatchMethod().name() : "KEYWORD")
                    .status("NO_PERMISSION")
                    .message(buildPermissionDeniedMessage(matchedIntent))
                    .requiresApproval(matchedIntent.needsApproval())
                    .quotaCost(matchedIntent.getQuotaCost())
                    .sessionId(sessionId)  // 返回会话ID供前端续接对话
                    .executedAt(LocalDateTime.now())
                    .build();

            log.info("AI演示权限拒绝: intentCode={}, sensitivity={}",
                    matchedIntent.getIntentCode(), sensitivityLevel);

            return ResponseEntity.ok(ApiResponse.success(response));
        }

        // 5. LOW敏感度的查询操作，正常执行
        try {
            IntentExecuteRequest executeRequest = IntentExecuteRequest.builder()
                    .userInput(userInput)
                    .intentCode(matchedIntent.getIntentCode())
                    .sessionId(sessionId)  // 传递会话ID支持多轮对话
                    .build();

            IntentExecuteResponse response = intentExecutorService.execute(
                    DEMO_FACTORY_ID, executeRequest, demoUserId, DEMO_USER_ROLE);

            // 补充意图识别信息
            response.setIntentRecognized(true);
            response.setIntentCode(matchedIntent.getIntentCode());
            response.setIntentName(matchedIntent.getIntentName());
            response.setIntentCategory(matchedIntent.getIntentCategory());
            response.setSensitivityLevel(sensitivityLevel);
            response.setConfidence(matchResult.getConfidence());
            response.setMatchMethod(matchResult.getMatchMethod() != null ?
                    matchResult.getMatchMethod().name() : "KEYWORD");
            response.setSessionId(sessionId);  // 确保响应包含会话ID

            // 格式化结果为自然语言文本
            resultFormatterService.formatAndSet(response);

            log.info("AI演示执行成功: intentCode={}, sessionId={}", matchedIntent.getIntentCode(), sessionId);

            return ResponseEntity.ok(ApiResponse.success(response));

        } catch (Exception e) {
            log.error("AI演示执行失败: intentCode={}, sessionId={}, error={}",
                    matchedIntent.getIntentCode(), sessionId, ErrorSanitizer.sanitize(e), e);

            IntentExecuteResponse response = IntentExecuteResponse.builder()
                    .intentRecognized(true)
                    .intentCode(matchedIntent.getIntentCode())
                    .intentName(matchedIntent.getIntentName())
                    .status("FAILED")
                    .message("演示执行失败: " + ErrorSanitizer.sanitize(e))
                    .sessionId(sessionId)  // 返回会话ID供前端续接对话
                    .executedAt(LocalDateTime.now())
                    .build();

            return ResponseEntity.ok(ApiResponse.success(response));
        }
    }

    @PostMapping("/recognize")
    @Operation(summary = "演示意图识别", description = "仅识别意图，不执行操作。支持多轮对话。")
    public ResponseEntity<ApiResponse<IntentRecognizeResponse>> recognizeDemo(
            @RequestBody DemoExecuteRequest request,
            jakarta.servlet.http.HttpServletRequest httpRequest) {

        if (isRateLimited(httpRequest)) {
            log.warn("AI Demo rate limited: IP={}", httpRequest.getRemoteAddr());
            return ResponseEntity.status(429).body(ApiResponse.error("请求过于频繁，请稍后再试"));
        }

        String userInput = request.getUserInput();
        log.info("AI演示识别请求: input='{}', sessionId={}",
                userInput != null && userInput.length() > 50 ? userInput.substring(0, 50) + "..." : userInput,
                request.getSessionId());

        // 获取或创建 Demo 会话
        DemoSession session = getOrCreateSession(request.getSessionId());
        String sessionId = session.getSessionId();
        Long demoUserId = session.getTempUserId();

        IntentMatchResult matchResult = aiIntentService.recognizeIntentWithConfidence(
                userInput, DEMO_FACTORY_ID, 1, demoUserId, DEMO_USER_ROLE, sessionId);

        IntentRecognizeResponse response = new IntentRecognizeResponse();
        response.setUserInput(userInput);
        response.setMatched(matchResult.hasMatch());
        response.setSessionId(sessionId);  // 返回会话ID供前端续接

        // v12.2: 检查是否需要澄清
        if (matchResult.getClarificationQuestion() != null && !matchResult.getClarificationQuestion().isEmpty()) {
            response.setNeedsClarification(true);
            response.setClarificationQuestion(matchResult.getClarificationQuestion());
            response.setMatchMethod(matchResult.getMatchMethod() != null ?
                    matchResult.getMatchMethod().name() : "REJECTED");
            response.setConfidence(0.0);
            return ResponseEntity.ok(ApiResponse.success(response));
        }

        if (matchResult.hasMatch()) {
            AIIntentConfig intent = matchResult.getBestMatch();
            response.setIntentCode(intent.getIntentCode());
            response.setIntentName(intent.getIntentName());
            response.setCategory(intent.getIntentCategory());
            response.setSensitivityLevel(intent.getSensitivityLevel());
            response.setConfidence(matchResult.getConfidence());
            response.setMatchMethod(matchResult.getMatchMethod() != null ?
                    matchResult.getMatchMethod().name() : null);
            response.setMatchLayer(determineMatchLayer(matchResult));
            response.setCanExecuteInDemo(ALLOWED_SENSITIVITY_LEVELS.contains(intent.getSensitivityLevel()));
            response.setToolName(intent.getToolName());
        }

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * 构建权限拒绝消息
     */
    private String buildPermissionDeniedMessage(AIIntentConfig intent) {
        String sensitivityDesc;
        // 修复 NPE: sensitivityLevel 可能为 null
        String level = intent.getSensitivityLevel() != null ? intent.getSensitivityLevel() : "LOW";
        switch (level) {
            case "MEDIUM":
                sensitivityDesc = "中等敏感度操作";
                break;
            case "HIGH":
                sensitivityDesc = "高敏感度操作";
                break;
            case "CRITICAL":
                sensitivityDesc = "关键操作";
                break;
            default:
                sensitivityDesc = "敏感操作";
        }

        StringBuilder message = new StringBuilder();
        message.append("该操作属于").append(sensitivityDesc).append("（")
               .append(intent.getIntentName()).append("），需要验证权限。\n\n");
        message.append("在正式系统中，您需要：\n");
        message.append("1. 使用有效账号登录系统\n");
        message.append("2. 拥有「").append(intent.getIntentCategory()).append("」模块的操作权限\n");

        if (intent.needsApproval()) {
            message.append("3. 该操作还需要经过审批流程\n");
        }

        message.append("\n当前为演示模式，仅支持查询类操作。");

        return message.toString();
    }

    /**
     * 确定匹配层级
     */
    private int determineMatchLayer(IntentMatchResult matchResult) {
        if (matchResult.getMatchMethod() == null) return 6;

        switch (matchResult.getMatchMethod()) {
            case REGEX:
                return 1;
            case KEYWORD:
                Double conf = matchResult.getConfidence();
                if (conf != null && conf >= 0.9) return 2;
                if (conf != null && conf >= 0.7) return 3;
                return 4;
            case SEMANTIC:
                return 5;
            case LLM:
                return 6;
            default:
                return 6;
        }
    }

    /**
     * 检测输入中未解析的指代词
     * 演示模式没有对话上下文，无法解析指代词，需要提示用户澄清
     *
     * @param input 用户输入
     * @return 检测到的指代词，如果没有则返回null
     */
    private String detectUnresolvedReference(String input) {
        if (input == null || input.isEmpty()) {
            return null;
        }

        for (String refWord : REFERENCE_WORDS) {
            if (input.contains(refWord)) {
                return refWord;
            }
        }
        return null;
    }

    /**
     * 构建澄清消息
     */
    private String buildClarificationMessage(String refWord) {
        if (refWord.contains("批")) {
            return "您提到了「" + refWord + "」，但当前没有上下文信息。请问您想查询哪个批次？您可以提供批次号，例如：MB20260115001";
        } else if (refWord.contains("个") || refWord.equals("它")) {
            return "您提到了「" + refWord + "」，请具体说明您要查询的对象。例如：查询批次号MB20260115001的信息";
        } else {
            return "您的描述中包含指代词「" + refWord + "」，请提供更具体的信息。例如指定具体的批次号、产品名称或时间范围。";
        }
    }

    /**
     * 构建澄清问题示例
     */
    private java.util.List<String> buildClarificationQuestions(String refWord) {
        java.util.List<String> questions = new java.util.ArrayList<>();

        if (refWord.contains("批")) {
            questions.add("查询批次 MB20260115001 的质检结果");
            questions.add("查询今天的所有批次");
            questions.add("查询最近一周的质检记录");
        } else {
            questions.add("查询批次号为XXX的详情");
            questions.add("查询产品名称包含XXX的记录");
            questions.add("查询今天的所有记录");
        }

        return questions;
    }

    // ==================== Demo 会话管理方法 ====================

    /**
     * 获取或创建 Demo 会话
     *
     * @param requestSessionId 请求中携带的会话ID（可能为null）
     * @return DemoSession 会话对象
     */
    private DemoSession getOrCreateSession(String requestSessionId) {
        // 如果请求中有会话ID，尝试获取已有会话
        if (requestSessionId != null && !requestSessionId.isEmpty()) {
            DemoSession existingSession = demoSessions.getIfPresent(requestSessionId);
            if (existingSession != null) {
                // 更新最后访问时间
                existingSession.setLastAccessedAt(LocalDateTime.now());
                log.debug("复用已有会话: sessionId={}, tempUserId={}",
                        existingSession.getSessionId(), existingSession.getTempUserId());
                return existingSession;
            }
            // 会话已过期或不存在，记录日志后创建新会话
            log.info("会话不存在或已过期，创建新会话: requestedSessionId={}", requestSessionId);
        }

        // 创建新会话
        String newSessionId = "demo-" + UUID.randomUUID().toString();
        Long newTempUserId = tempUserIdGenerator.decrementAndGet();
        LocalDateTime now = LocalDateTime.now();

        DemoSession newSession = new DemoSession(newTempUserId, newSessionId, now, now);
        demoSessions.put(newSessionId, newSession);

        log.info("创建新的Demo会话: sessionId={}, tempUserId={}", newSessionId, newTempUserId);

        return newSession;
    }

    /**
     * 获取会话统计信息（用于调试）
     */
    @GetMapping("/session-stats")
    @Operation(summary = "获取Demo会话统计", description = "返回当前活跃的Demo会话数量和缓存状态")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> getSessionStats() {
        java.util.Map<String, Object> stats = new java.util.HashMap<>();
        stats.put("activeSessions", demoSessions.estimatedSize());
        stats.put("cacheStats", demoSessions.stats().toString());
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    // ==================== LinUCB 测试端点 ====================

    @PostMapping("/linucb-test")
    @Operation(summary = "测试LinUCB工人推荐", description = "公开测试端点，验证LinUCB算法和策略干预集成")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> testLinUCB(
            @RequestBody LinUCBTestRequest request) {

        log.info("LinUCB测试请求: taskType={}", request.getTaskType());

        java.util.Map<String, Object> result = new java.util.HashMap<>();

        try {
            // 构造任务特征
            java.util.Map<String, Object> taskFeatures = new java.util.HashMap<>();
            taskFeatures.put("quantity", request.getQuantity() != null ? request.getQuantity() : 500);
            taskFeatures.put("deadlineHours", request.getDeadlineHours() != null ? request.getDeadlineHours() : 8);
            taskFeatures.put("productType", request.getTaskType() != null ? request.getTaskType() : "frozen_fish");
            taskFeatures.put("priority", request.getPriority() != null ? request.getPriority() : 5);
            taskFeatures.put("complexity", request.getComplexity() != null ? request.getComplexity() : 2);

            // 提取特征向量
            double[] featureArray = linUCBService.extractTaskFeatures(taskFeatures);

            // 候选工人ID (使用默认测试数据)
            java.util.List<Long> candidateIds = request.getCandidateWorkerIds();
            if (candidateIds == null || candidateIds.isEmpty()) {
                candidateIds = java.util.Arrays.asList(1L, 2L, 3L, 4L, 5L);
            }

            // 调用LinUCB推荐
            java.util.List<LinUCBService.WorkerRecommendation> recommendations =
                    linUCBService.recommendWorkers(DEMO_FACTORY_ID, featureArray, candidateIds);

            // 构造返回结果
            result.put("success", true);
            result.put("taskFeatures", taskFeatures);
            result.put("candidateCount", candidateIds.size());
            result.put("recommendations", recommendations);
            result.put("message", "LinUCB推荐完成，包含策略干预重排序 (新人培训/公平轮换/疲劳控制/紧急任务加权)");

            log.info("LinUCB测试成功: 返回 {} 个推荐", recommendations.size());

        } catch (Exception e) {
            log.error("LinUCB测试失败: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("message", "LinUCB测试失败: " + ErrorSanitizer.sanitize(e));
        }

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // ==================== DTO Classes ====================

    @lombok.Data
    public static class DemoExecuteRequest {
        /**
         * 用户输入的自然语言
         */
        private String userInput;

        /**
         * 会话ID (可选)
         * - 如果提供：尝试延续已有会话
         * - 如果不提供：创建新会话
         */
        private String sessionId;
    }

    @lombok.Data
    public static class LinUCBTestRequest {
        private String taskType;
        private Integer quantity;
        private Integer deadlineHours;
        private Integer priority;
        private Integer complexity;
        private java.util.List<Long> candidateWorkerIds;
    }

    @lombok.Data
    public static class IntentRecognizeResponse {
        private String userInput;
        private boolean matched;
        private String intentCode;
        private String intentName;
        private String category;
        private String sensitivityLevel;
        private Double confidence;
        private String matchMethod;
        private int matchLayer;
        private boolean canExecuteInDemo;
        private String toolName;

        /**
         * 会话ID
         * 前端可以保存此ID用于后续的多轮对话
         */
        private String sessionId;

        /**
         * v12.2: 澄清问题
         * 当输入模糊需要澄清时，返回此字段引导用户提供更多信息
         */
        private String clarificationQuestion;

        /**
         * v12.2: 是否需要澄清
         */
        private boolean needsClarification;
    }
}

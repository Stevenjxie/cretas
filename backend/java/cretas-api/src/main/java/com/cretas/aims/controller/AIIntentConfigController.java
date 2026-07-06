package com.cretas.aims.controller;

import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.ai.ParameterConfirmationRequest;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.intent.CleanupRequest;
import com.cretas.aims.dto.intent.IntentFeedbackRequest;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.intent.KeywordEffectiveness;
import com.cretas.aims.utils.JwtUtil;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.IntentExecutorService;
import com.cretas.aims.service.KeywordEffectivenessService;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.cretas.aims.service.impl.IntentConfigRollbackService;
import com.cretas.aims.entity.learning.ParameterExtractionRule;
import com.cretas.aims.entity.config.AIIntentConfigHistory;
import com.cretas.aims.utils.CookieAuthHelper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.cretas.aims.annotation.RateLimit;
import com.cretas.aims.annotation.RateLimit.LimitType;
import com.cretas.aims.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AI意图配置控制器
 *
 * 提供AI意图识别配置的管理API:
 * - 意图配置的CRUD操作
 * - 意图识别测试
 * - 分类和权限查询
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-31
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/ai-intents")
@RequiredArgsConstructor
@Tag(name = "AI意图配置", description = "AI意图识别配置管理API")
public class AIIntentConfigController {

    private final AIIntentService aiIntentService;
    private final IntentExecutorService intentExecutorService;
    private final KeywordEffectivenessService keywordEffectivenessService;
    private final IntentConfigRollbackService rollbackService;
    private final ParameterExtractionLearningService parameterExtractionLearningService;
    private final JwtUtil jwtUtil;

    /**
     * Sprint 11.5 P0 fix (2026-05-23): Extract JWT token from request supporting BOTH
     * Bearer header (mobile clients) AND HttpOnly cookie (web admin clients).
     *
     * <p>Pre-fix: all 9 endpoints used {@code @RequestHeader("Authorization") String authorization}
     * which is mandatory by Spring default. Web admin sends cookie-only requests (no Authorization
     * header) → Spring throws {@code MissingRequestHeaderException} → falls to generic
     * {@link com.cretas.aims.exception.GlobalExceptionHandler#handleRuntimeException} → HTTP 500
     * "系统处理异常 (追踪码: XXX)". 100% of web-admin UI clients hitting AI intent endpoints
     * saw cryptic 500. {@link com.cretas.aims.config.JwtAuthInterceptor#extractToken} already
     * supports both paths for authentication; the controller-level header dependency was the gap.
     *
     * <p>Post-fix: this helper mirrors {@link com.cretas.aims.config.JwtAuthInterceptor#extractToken}.
     * Bearer header takes priority (no behavior change for mobile); cookie fallback unlocks web admin.
     *
     * @return token string, or null if neither header nor cookie present (caller should treat as auth failure)
     */
    private String extractToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String token = authorization.substring(7).trim();
            if (!token.isEmpty()) {
                return token;
            }
        }
        return CookieAuthHelper.extractCookieValue(request, CookieAuthHelper.ACCESS_TOKEN_COOKIE);
    }

    // ==================== 意图查询 ====================

    @GetMapping
    @Operation(summary = "获取所有意图配置", description = "获取所有启用的AI意图配置列表（租户隔离）")
    public ResponseEntity<ApiResponse<List<AIIntentConfig>>> getAllIntents(
            @Parameter(description = "工厂ID") @PathVariable String factoryId) {

        List<AIIntentConfig> intents = aiIntentService.getAllIntents(factoryId);
        return ResponseEntity.ok(ApiResponse.success(intents));
    }

    @GetMapping("/category/{category}")
    @Operation(summary = "按分类获取意图", description = "根据分类获取意图配置列表（租户隔离）")
    public ResponseEntity<ApiResponse<List<AIIntentConfig>>> getIntentsByCategory(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @Parameter(description = "意图分类 (ANALYSIS, DATA_OP, FORM, SCHEDULE, SYSTEM)")
            @PathVariable String category) {

        List<AIIntentConfig> intents = aiIntentService.getIntentsByCategory(factoryId, category);
        return ResponseEntity.ok(ApiResponse.success(intents));
    }

    @GetMapping("/categories")
    @Operation(summary = "获取所有分类", description = "获取所有可用的意图分类列表（租户隔离）")
    public ResponseEntity<ApiResponse<List<String>>> getAllCategories(
            @Parameter(description = "工厂ID") @PathVariable String factoryId) {

        List<String> categories = aiIntentService.getAllCategories(factoryId);
        return ResponseEntity.ok(ApiResponse.success(categories));
    }

    @GetMapping("/sensitivity/{level}")
    @Operation(summary = "按敏感度获取意图", description = "根据敏感度级别获取意图配置（租户隔离）")
    public ResponseEntity<ApiResponse<List<AIIntentConfig>>> getIntentsBySensitivity(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @Parameter(description = "敏感度级别 (LOW, MEDIUM, HIGH, CRITICAL)")
            @PathVariable String level) {

        List<AIIntentConfig> intents = aiIntentService.getIntentsBySensitivity(factoryId, level);
        return ResponseEntity.ok(ApiResponse.success(intents));
    }

    @GetMapping("/keyword-stats")
    @Operation(summary = "获取关键词效果统计", description = "获取指定意图的关键词效果统计数据")
    public ResponseEntity<ApiResponse<List<KeywordEffectiveness>>> getKeywordStats(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @Parameter(description = "意图代码") @RequestParam(required = false) String intentCode,
            @Parameter(description = "效果阈值") @RequestParam(required = false, defaultValue = "0") java.math.BigDecimal threshold) {

        List<KeywordEffectiveness> stats = keywordEffectivenessService.getEffectiveKeywords(
                factoryId, intentCode, threshold);
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    @GetMapping("/keyword-stats/count")
    @Operation(summary = "获取关键词数量", description = "获取指定意图的关键词数量")
    public ResponseEntity<ApiResponse<Long>> getKeywordCount(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @Parameter(description = "意图代码") @RequestParam(required = false) String intentCode) {

        long count = keywordEffectivenessService.countKeywords(factoryId, intentCode);
        return ResponseEntity.ok(ApiResponse.success(count));
    }

    @RequirePermission({"system:read_write"})
    @PostMapping("/keywords/cleanup")
    @PreAuthorize("hasAnyRole('FACTORY_SUPER_ADMIN', 'FACTORY_ADMIN')")
    @Operation(summary = "清理低效关键词", description = "清理效果评分低于阈值的关键词（仅工厂管理员）")
    public ResponseEntity<ApiResponse<Integer>> cleanupLowEffectivenessKeywords(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @RequestBody CleanupRequest request) {

        int cleaned = keywordEffectivenessService.cleanupLowEffectivenessKeywords(
                factoryId, request.getThreshold(), request.getMinNegative());
        log.info("Cleaned {} low-effectiveness keywords for factory {}", cleaned, factoryId);
        return ResponseEntity.ok(ApiResponse.success("清理完成，共删除 " + cleaned + " 个低效关键词", cleaned));
    }

    @GetMapping("/{intentCode}")
    @Operation(summary = "获取单个意图", description = "根据意图代码获取意图配置详情")
    public ResponseEntity<ApiResponse<AIIntentConfig>> getIntent(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @Parameter(description = "意图代码") @PathVariable String intentCode) {

        return aiIntentService.getIntentByCode(factoryId, intentCode)
                .map(i -> ResponseEntity.ok(ApiResponse.success(i)))
                .orElseThrow(() -> new BusinessException(404, "意图配置不存在: " + intentCode).withHint("请检查 ID 是否正确"));
    }

    // ==================== 意图识别 ====================

    // Sprint 11 Round 2 (2026-05-22): removed @RequirePermission({"system:read_write"}).
    // Intent recognition is read-only diagnostic — gated by JWT auth alone.
    // Per-intent sensitivity is enforced by AIIntentService.hasPermission() in
    // IntentExecutionOrchestrator (line 240) — controller-level system:read_write
    // was blocking ALL non-super-admin roles (e.g. qhj_warehouse_mgr) from even
    // PROBING what they can do. See docs/superpowers/handoffs/2026-05-22-mealclaw-e2e-rounds.md
    @PostMapping("/recognize")
    @Operation(summary = "测试意图识别", description = "输入文本测试意图识别结果（支持操作类型检测）")
    public ResponseEntity<ApiResponse<IntentRecognitionResult>> recognizeIntent(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @RequestBody IntentRecognitionRequest request) {

        log.debug("Recognizing intent for input: {}", request.getUserInput());

        // 使用带操作类型检测的增强版识别方法 (BUG-001/002 修复)
        IntentMatchResult matchResult = aiIntentService.recognizeIntentWithConfidence(
                request.getUserInput(), factoryId, 1, null, null);

        IntentRecognitionResult result = new IntentRecognitionResult();
        result.setUserInput(request.getUserInput());
        result.setMatched(matchResult.hasMatch());

        if (matchResult.hasMatch()) {
            AIIntentConfig intent = matchResult.getBestMatch();
            result.setIntentCode(intent.getIntentCode());
            result.setIntentName(intent.getIntentName());
            result.setCategory(intent.getIntentCategory());
            result.setSensitivityLevel(intent.getSensitivityLevel());
            result.setQuotaCost(intent.getQuotaCost());
            result.setRequiresApproval(intent.needsApproval());
            // 额外添加置信度和匹配方法信息
            result.setConfidence(matchResult.getConfidence());
            result.setMatchMethod(matchResult.getMatchMethod() != null ?
                    matchResult.getMatchMethod().name() : null);
        }
        result.setTimingMs(matchResult.getTimingMs());

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // Sprint 11 Round 2: removed @RequirePermission({"system:read_write"}) — read-only diagnostic.
    @PostMapping("/recognize-all")
    @Operation(summary = "识别所有匹配意图", description = "获取所有可能匹配的意图列表")
    public ResponseEntity<ApiResponse<List<AIIntentConfig>>> recognizeAllIntents(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @RequestBody IntentRecognitionRequest request) {

        List<AIIntentConfig> matchedIntents = aiIntentService.recognizeAllIntents(factoryId, request.getUserInput());
        return ResponseEntity.ok(ApiResponse.success(matchedIntents));
    }

    // ==================== 意图执行 ====================

    // Sprint 11 Round 2 (2026-05-22): removed @RequirePermission({"system:read_write"}).
    // Was a P0 — qhj_warehouse_mgr / qhj_finance_mgr / qhj_sales_mgr / qhj_operator (all
    // RES_3101_009 spec'd accounts) blocked at the controller before ever reaching the
    // intent-level permission check. Per-intent sensitivity + required_roles is now the
    // sole permission gate (enforced in IntentExecutionOrchestrator.execute line 240
    // via aiIntentService.hasPermission(intentCode, userRole) → returns FORBIDDEN
    // response when role not in intent's required_roles JSON array; intents with
    // sensitivity_level=LOW + empty required_roles allow all authenticated users).
    @PostMapping("/execute")
    @Operation(summary = "执行AI意图", description = "识别用户输入的意图并执行对应操作")
    @RateLimit(count = 20, period = 60, limitType = LimitType.USER, message = "AI请求过于频繁，请稍后再试")
    public ResponseEntity<ApiResponse<IntentExecuteResponse>> executeIntent(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @RequestBody IntentExecuteRequest request,
            HttpServletRequest httpRequest) {

        // Sprint 11.5 P0 (2026-05-23): extract token via cookie-aware helper (was @RequestHeader only)
        String token = extractToken(httpRequest);
        Long userId = jwtUtil.getUserIdFromToken(token);
        String userRole = jwtUtil.getRoleFromToken(token);

        applyRestaurantReportIntentShortcut(factoryId, request);

        log.info("执行AI意图: factoryId={}, userInput={}, userId={}, role={}",
                factoryId,
                request.getUserInput().length() > 30 ?
                        request.getUserInput().substring(0, 30) + "..." : request.getUserInput(),
                userId, userRole);

        IntentExecuteResponse response = intentExecutorService.execute(factoryId, request, userId, userRole);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    private void applyRestaurantReportIntentShortcut(String factoryId, IntentExecuteRequest request) {
        if (request == null || (request.getIntentCode() != null && !request.getIntentCode().isBlank())) {
            return;
        }
        if (!isRestaurantFactoryId(factoryId)) {
            return;
        }
        String input = request.getUserInput();
        if (input == null || input.isBlank() || hasExplicitReadVeto(input)) {
            return;
        }
        String q = input.replaceAll("\\s+", "");
        boolean salesMetric = containsAny(q,
                "\u8425\u6536",
                "\u8425\u4e1a\u989d",
                "\u9500\u552e\u989d",
                "\u9500\u552e",
                "\u5ba2\u5355\u4ef7",
                "\u8ba2\u5355");
        boolean reviewMetric = containsAny(q,
                "\u8bc4\u4ef7",
                "\u8bc4\u8bba",
                "\u8bc4\u5206",
                "\u53e3\u7891",
                "\u5dee\u8bc4",
                "\u6295\u8bc9",
                "\u987e\u5ba2\u6ee1\u610f",
                "\u5927\u4f17\u70b9\u8bc4");
        boolean reportMetric = salesMetric || reviewMetric;
        boolean reportAction = containsAny(q,
                "\u67e5",
                "\u67e5\u8be2",
                "\u770b",
                "\u770b\u770b",
                "\u4eca\u5929",
                "\u672c\u5468",
                "\u8fd9\u5468",
                "\u672c\u6708",
                "\u600e\u4e48\u6837",
                "\u60c5\u51b5",
                "\u6c47\u603b",
                "\u7edf\u8ba1",
                "\u5206\u6790");
        boolean reviewAction = reportAction || containsAny(q,
                "\u5e94\u8be5\u600e\u4e48",
                "\u600e\u4e48\u6539\u5584",
                "\u5982\u4f55\u6539\u5584",
                "\u6539\u5584",
                "\u6539\u8fdb",
                "\u5904\u7406",
                "\u89e3\u51b3",
                "\u5efa\u8bae");
        boolean trendAction = containsAny(q,
                "\u540c\u6bd4",
                "\u73af\u6bd4",
                "\u8d8b\u52bf",
                "\u8d70\u52bf",
                "\u589e\u957f",
                "\u4e0b\u964d",
                "\u6708\u5ea6\u53d8\u5316");
        if (reviewMetric && !salesMetric && reviewAction) {
            if (containsAny(q, "\u5dee\u8bc4", "\u6295\u8bc9", "\u5410\u69fd", "\u4f4e\u661f")) {
                request.setIntentCode("RESTAURANT_REVIEW_COMPLAINT");
            } else if (trendAction) {
                request.setIntentCode("RESTAURANT_REVIEW_TREND");
            } else {
                request.setIntentCode("RESTAURANT_REVIEW_SUMMARY");
            }
            log.info("[RestaurantDemoIntentShortcut] route review report phrase before intent recognition: factoryId={}, intentCode={}",
                    factoryId, request.getIntentCode());
            return;
        }
        if (reportMetric && trendAction) {
            request.setIntentCode("RESTAURANT_OPS_TREND_ANALYSIS");
            log.info("[RestaurantDemoIntentShortcut] route trend report phrase before intent recognition: factoryId={}, intentCode={}",
                    factoryId, request.getIntentCode());
            return;
        }
        if (reportMetric && reportAction) {
            request.setIntentCode("RESTAURANT_OPS_SALES_SUMMARY");
            log.info("[RestaurantDemoIntentShortcut] route report phrase before intent recognition: factoryId={}, intentCode={}",
                    factoryId, request.getIntentCode());
        }
    }

    private boolean isRestaurantFactoryId(String factoryId) {
        return factoryId != null && (factoryId.startsWith("RES_") || "DEMO_REST".equalsIgnoreCase(factoryId));
    }

    private boolean hasExplicitReadVeto(String input) {
        if (input == null || input.isBlank()) {
            return false;
        }
        String q = input.replaceAll("\\s+", "");
        return containsAny(q,
                "\u4e0d\u8981\u67e5",
                "\u4e0d\u8981\u770b",
                "\u522b\u67e5",
                "\u522b\u770b",
                "\u4e0d\u7528\u67e5",
                "\u4e0d\u7528\u770b",
                "\u4e0d\u9700\u8981\u67e5",
                "\u4e0d\u9700\u8981\u770b",
                "\u4e0d\u60f3\u67e5",
                "\u4e0d\u60f3\u770b",
                "\u5148\u522b\u67e5",
                "\u5148\u522b\u770b",
                "\u65e0\u9700\u67e5",
                "\u65e0\u9700\u770b",
                "\u4e0d\u67e5",
                "\u4e0d\u770b");
    }

    private boolean containsAny(String input, String... terms) {
        if (input == null || terms == null) {
            return false;
        }
        for (String term : terms) {
            if (term != null && !term.isBlank() && input.contains(term)) {
                return true;
            }
        }
        return false;
    }

    // Sprint 11 Round 2: removed @RequirePermission({"system:read_write"}) — same rationale as /execute.
    @PostMapping("/execute/multi")
    @Operation(summary = "执行多意图 (Multi-Label Classification)",
               description = "使用 Sigmoid-based 多标签分类识别并执行多个意图")
    @RateLimit(count = 20, period = 60, limitType = LimitType.USER, message = "AI请求过于频繁，请稍后再试")
    public ResponseEntity<ApiResponse<IntentExecuteResponse>> executeMultiIntent(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @RequestBody IntentExecuteRequest request,
            HttpServletRequest httpRequest) {

        // Sprint 11.5 P0 (2026-05-23): cookie-aware token extraction
        String token = extractToken(httpRequest);
        Long userId = jwtUtil.getUserIdFromToken(token);
        String userRole = jwtUtil.getRoleFromToken(token);

        log.info("执行多意图: factoryId={}, userInput={}, userId={}, role={}",
                factoryId,
                request.getUserInput().length() > 30 ?
                        request.getUserInput().substring(0, 30) + "..." : request.getUserInput(),
                userId, userRole);

        IntentExecuteResponse response = intentExecutorService.executeMultiIntent(factoryId, request, userId, userRole);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // Sprint 11 Round 2: removed @RequirePermission({"system:read_write"}) — same rationale as /execute.
    @PostMapping(value = "/execute/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式执行AI意图 (SSE)", description = "通过 Server-Sent Events 实时返回执行进度")
    @RateLimit(count = 20, period = 60, limitType = LimitType.USER, message = "AI请求过于频繁，请稍后再试")
    public SseEmitter executeIntentStream(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @RequestBody IntentExecuteRequest request,
            HttpServletRequest httpRequest) {

        // Sprint 11.5 P0 (2026-05-23): cookie-aware token extraction
        String token = extractToken(httpRequest);
        Long userId = jwtUtil.getUserIdFromToken(token);
        String userRole = jwtUtil.getRoleFromToken(token);

        log.info("流式执行AI意图: factoryId={}, userInput={}, userId={}, role={}",
                factoryId,
                request.getUserInput().length() > 30 ?
                        request.getUserInput().substring(0, 30) + "..." : request.getUserInput(),
                userId, userRole);

        return intentExecutorService.executeStream(factoryId, request, userId, userRole);
    }

    // Sprint 11 Round 2: removed @RequirePermission({"system:read_write"}) — preview is read-only.
    @PostMapping("/preview")
    @Operation(summary = "预览AI意图执行结果", description = "识别意图并预览执行结果，不实际执行")
    public ResponseEntity<ApiResponse<IntentExecuteResponse>> previewIntent(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @RequestBody IntentExecuteRequest request,
            HttpServletRequest httpRequest) {

        // Sprint 11.5 P0 (2026-05-23): cookie-aware token extraction
        String token = extractToken(httpRequest);
        Long userId = jwtUtil.getUserIdFromToken(token);
        String userRole = jwtUtil.getRoleFromToken(token);

        log.info("预览AI意图: factoryId={}, userInput={}", factoryId, request.getUserInput());

        IntentExecuteResponse response = intentExecutorService.preview(factoryId, request, userId, userRole);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // Sprint 11 Round 2: removed @RequirePermission({"system:read_write"}). The confirm step
    // is the second half of preview/confirm pair — perm gate (if any) belongs on the original
    // preview which already established intent permission. Intent-level perm via tokenized
    // confirmToken still enforces the original requestor's role.
    @PostMapping("/confirm/{confirmToken}")
    @Operation(summary = "确认执行预览的意图", description = "确认执行之前预览的意图操作")
    public ResponseEntity<ApiResponse<IntentExecuteResponse>> confirmIntent(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @Parameter(description = "确认Token") @PathVariable String confirmToken,
            HttpServletRequest httpRequest) {

        // Sprint 11.5 P0 (2026-05-23): cookie-aware token extraction
        String token = extractToken(httpRequest);
        Long userId = jwtUtil.getUserIdFromToken(token);
        String userRole = jwtUtil.getRoleFromToken(token);

        log.info("确认执行AI意图: factoryId={}, confirmToken={}", factoryId, confirmToken);

        IntentExecuteResponse response = intentExecutorService.confirm(factoryId, confirmToken, userId, userRole);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ==================== 参数确认和规则学习 ====================

    // Sprint 11 Round 2: removed @RequirePermission({"system:read_write"}). Parameter learning
    // is per-user-per-intent. If executeAfterConfirm=true, the intent-level perm gate in
    // IntentExecutionOrchestrator still applies.
    @PostMapping("/params/confirm")
    @Operation(summary = "确认参数并学习规则",
               description = "用户确认 LLM 提取的参数后，系统学习提取规则，下次可直接使用规则提取（无需调用 LLM）")
    public ResponseEntity<ApiResponse<IntentExecuteResponse>> confirmParameters(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @RequestBody ParameterConfirmationRequest request,
            HttpServletRequest httpRequest) {

        // Sprint 11.5 P0 (2026-05-23): cookie-aware token extraction
        String token = extractToken(httpRequest);
        Long userId = jwtUtil.getUserIdFromToken(token);
        String userRole = jwtUtil.getRoleFromToken(token);

        log.info("确认参数并学习规则: factoryId={}, intentCode={}, params={}",
                factoryId, request.getIntentCode(), request.getConfirmedParams().keySet());

        // 1. 学习提取规则
        parameterExtractionLearningService.learnAndConfirm(
                factoryId,
                request.getIntentCode(),
                request.getUserInput(),
                request.getConfirmedParams());

        // 2. 如果需要执行，构建执行请求
        if (Boolean.TRUE.equals(request.getExecuteAfterConfirm())) {
            // W0 write-guard (intent-w0): parameter confirmation IS the user's confirmation — flag it
            // so the downstream W0 guard (Site A) does not re-prompt WRITE_CONFIRM_REQUIRED on a write
            // intent the user just confirmed. Copy into a mutable map (getConfirmedParams() may be null/immutable).
            Map<String, Object> context = new java.util.HashMap<>(
                    request.getConfirmedParams() != null ? request.getConfirmedParams() : Map.of());
            context.put("confirmed", true);
            IntentExecuteRequest executeRequest = IntentExecuteRequest.builder()
                    .userInput(request.getUserInput())
                    .intentCode(request.getIntentCode())
                    .context(context)
                    .build();

            IntentExecuteResponse response = intentExecutorService.execute(factoryId, executeRequest, userId, userRole);
            return ResponseEntity.ok(ApiResponse.success("参数已确认并执行", response));
        }

        // 只学习规则，不执行
        return ResponseEntity.ok(ApiResponse.success("参数已确认，规则已学习", null));
    }

    @GetMapping("/params/rules/{intentCode}")
    @Operation(summary = "获取意图的参数提取规则", description = "获取指定意图的所有活跃参数提取规则")
    public ResponseEntity<ApiResponse<List<ParameterExtractionRule>>> getExtractionRules(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @Parameter(description = "意图代码") @PathVariable String intentCode) {

        List<ParameterExtractionRule> rules = parameterExtractionLearningService.getActiveRules(factoryId, intentCode);
        return ResponseEntity.ok(ApiResponse.success(rules));
    }

    @RequirePermission({"system:read_write"})
    @DeleteMapping("/params/rules/{ruleId}")
    @PreAuthorize("hasAnyRole('FACTORY_SUPER_ADMIN', 'FACTORY_ADMIN')")
    @Operation(summary = "删除参数提取规则", description = "删除指定的参数提取规则（仅管理员）")
    public ResponseEntity<ApiResponse<Void>> deleteExtractionRule(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @Parameter(description = "规则ID") @PathVariable String ruleId) {

        log.info("删除参数提取规则: factoryId={}, ruleId={}", factoryId, ruleId);
        parameterExtractionLearningService.deleteRule(ruleId);
        return ResponseEntity.ok(ApiResponse.success("规则已删除", null));
    }

    @RequirePermission({"system:read_write"})
    @PostMapping("/params/rules/cleanup")
    @PreAuthorize("hasAnyRole('FACTORY_SUPER_ADMIN', 'FACTORY_ADMIN')")
    @Operation(summary = "清理低成功率规则", description = "清理成功率低于阈值的参数提取规则")
    public ResponseEntity<ApiResponse<Integer>> cleanupLowSuccessRules(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @Parameter(description = "最小命中次数") @RequestParam(defaultValue = "10") int minHitCount,
            @Parameter(description = "最大成功率阈值") @RequestParam(defaultValue = "0.3") double maxSuccessRate) {

        log.info("清理低成功率规则: factoryId={}, minHitCount={}, maxSuccessRate={}",
                factoryId, minHitCount, maxSuccessRate);
        int count = parameterExtractionLearningService.cleanupLowSuccessRules(minHitCount, maxSuccessRate);
        return ResponseEntity.ok(ApiResponse.success("已清理 " + count + " 条规则", count));
    }

    // ==================== 权限查询 ====================

    @GetMapping("/{intentCode}/permission")
    @Operation(summary = "检查意图权限", description = "检查指定角色是否有权限执行意图")
    public ResponseEntity<ApiResponse<PermissionCheckResult>> checkPermission(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @Parameter(description = "意图代码") @PathVariable String intentCode,
            @Parameter(description = "用户角色") @RequestParam String userRole) {

        boolean hasPermission = aiIntentService.hasPermission(intentCode, userRole);
        boolean requiresApproval = aiIntentService.requiresApproval(intentCode);
        int quotaCost = aiIntentService.getQuotaCost(intentCode);

        PermissionCheckResult result = new PermissionCheckResult();
        result.setIntentCode(intentCode);
        result.setUserRole(userRole);
        result.setHasPermission(hasPermission);
        result.setRequiresApproval(requiresApproval);
        result.setQuotaCost(quotaCost);

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // ==================== 意图管理 ====================

    @RequirePermission({"system:read_write"})
    @PostMapping
    @PreAuthorize("hasAnyRole('FACTORY_SUPER_ADMIN', 'FACTORY_ADMIN')")
    @Operation(summary = "创建意图配置", description = "创建新的AI意图配置（仅工厂管理员）")
    public ResponseEntity<ApiResponse<AIIntentConfig>> createIntent(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @RequestBody AIIntentConfig intentConfig) {

        AIIntentConfig created = aiIntentService.createIntent(intentConfig);
        log.info("Created AI intent: {} for factory context: {}", intentConfig.getIntentCode(), factoryId);
        return ResponseEntity.ok(ApiResponse.success("意图配置创建成功", created));
    }

    @RequirePermission({"system:read_write"})
    @PutMapping("/{intentCode}")
    @PreAuthorize("hasAnyRole('FACTORY_SUPER_ADMIN', 'FACTORY_ADMIN')")
    @Operation(summary = "更新意图配置", description = "更新现有的AI意图配置（仅工厂管理员）")
    public ResponseEntity<ApiResponse<AIIntentConfig>> updateIntent(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @Parameter(description = "意图代码") @PathVariable String intentCode,
            @RequestBody AIIntentConfig intentConfig) {

        intentConfig.setIntentCode(intentCode);
        AIIntentConfig updated = aiIntentService.updateIntent(intentConfig);
        log.info("Updated AI intent: {}", intentCode);
        return ResponseEntity.ok(ApiResponse.success("意图配置更新成功", updated));
    }

    @RequirePermission({"system:read_write"})
    @PatchMapping("/{intentCode}/active")
    @PreAuthorize("hasAnyRole('FACTORY_SUPER_ADMIN', 'FACTORY_ADMIN')")
    @Operation(summary = "启用/禁用意图", description = "切换意图的启用状态（仅工厂管理员）")
    public ResponseEntity<ApiResponse<Void>> setIntentActive(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @Parameter(description = "意图代码") @PathVariable String intentCode,
            @RequestBody ActiveStatusRequest request) {

        aiIntentService.setIntentActive(intentCode, request.isActive());
        String action = request.isActive() ? "启用" : "禁用";
        log.info("{} AI intent: {}", action, intentCode);
        return ResponseEntity.ok(ApiResponse.successMessage("意图已" + action));
    }

    @RequirePermission({"system:read_write"})
    @DeleteMapping("/{intentCode}")
    @PreAuthorize("hasAnyRole('FACTORY_SUPER_ADMIN', 'FACTORY_ADMIN')")
    @Operation(summary = "删除意图配置", description = "软删除意图配置（仅工厂管理员）")
    public ResponseEntity<ApiResponse<Void>> deleteIntent(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @Parameter(description = "意图代码") @PathVariable String intentCode) {

        aiIntentService.deleteIntent(intentCode);
        log.info("Deleted AI intent: {}", intentCode);
        return ResponseEntity.ok(ApiResponse.successMessage("意图配置删除成功"));
    }

    // ==================== 版本回滚 ====================

    @RequirePermission({"system:read_write"})
    @PostMapping("/{intentCode}/rollback")
    @PreAuthorize("hasAnyRole('FACTORY_SUPER_ADMIN', 'FACTORY_ADMIN')")
    @Operation(summary = "回滚意图配置", description = "回滚单个意图配置到上个版本（仅工厂管理员）")
    public ResponseEntity<ApiResponse<AIIntentConfig>> rollbackIntent(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @Parameter(description = "意图代码") @PathVariable String intentCode,
            @RequestBody RollbackRequest request,
            HttpServletRequest httpRequest) {

        // Sprint 11.5 P0 (2026-05-23): cookie-aware token extraction
        String token = extractToken(httpRequest);
        Long userId = jwtUtil.getUserIdFromToken(token);
        String username = jwtUtil.getUsernameFromToken(token);

        // 先获取配置ID
        AIIntentConfig config = aiIntentService.getIntentByCode(factoryId, intentCode)
                .orElseThrow(() -> new IllegalArgumentException("意图配置不存在: " + intentCode));

        AIIntentConfig rolled = rollbackService.rollbackToLastVersion(
                config.getId(), userId, username, request.getReason());

        log.info("Rolled back AI intent: {} to version {}", intentCode, rolled.getConfigVersion());
        return ResponseEntity.ok(ApiResponse.success("意图配置回滚成功", rolled));
    }

    @GetMapping("/{intentCode}/history")
    @Operation(summary = "获取版本历史", description = "获取意图配置的版本历史记录")
    public ResponseEntity<ApiResponse<java.util.List<AIIntentConfigHistory>>> getVersionHistory(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @Parameter(description = "意图代码") @PathVariable String intentCode) {

        AIIntentConfig config = aiIntentService.getIntentByCode(factoryId, intentCode)
                .orElseThrow(() -> new IllegalArgumentException("意图配置不存在: " + intentCode));

        java.util.List<AIIntentConfigHistory> history = rollbackService.getVersionHistory(config.getId());
        return ResponseEntity.ok(ApiResponse.success(history));
    }

    @RequirePermission({"system:read_write"})
    @PostMapping("/rollback-all")
    @PreAuthorize("hasAnyRole('FACTORY_SUPER_ADMIN', 'FACTORY_ADMIN')")
    @Operation(summary = "批量回滚工厂意图", description = "回滚工厂的所有意图配置到上个版本（仅工厂管理员）")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> rollbackAllIntents(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @RequestBody RollbackRequest request,
            HttpServletRequest httpRequest) {

        // Sprint 11.5 P0 (2026-05-23): cookie-aware token extraction
        String token = extractToken(httpRequest);
        Long userId = jwtUtil.getUserIdFromToken(token);
        String username = jwtUtil.getUsernameFromToken(token);

        java.util.Map<String, Object> result = rollbackService.rollbackFactoryToLastVersion(
                factoryId, userId, username, request.getReason());

        log.info("Batch rollback for factory {}: {}", factoryId, result);
        return ResponseEntity.ok(ApiResponse.success("批量回滚完成", result));
    }

    // ==================== 反馈记录 ====================

    // Sprint 11 Round 2: removed @RequirePermission({"system:read_write"}). Feedback is
    // per-user signal data — every authenticated user who can use AI must be able to
    // submit feedback (positive/negative). JWT auth alone is sufficient.
    @PostMapping("/feedback/positive")
    @Operation(summary = "记录正向反馈", description = "当用户确认意图匹配正确时调用，用于关键词效果追踪")
    public ResponseEntity<ApiResponse<Void>> recordPositiveFeedback(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @RequestBody PositiveFeedbackRequest request) {

        log.info("记录正向反馈: factoryId={}, intentCode={}, keywords={}",
                factoryId, request.getIntentCode(), request.getMatchedKeywords());

        aiIntentService.recordPositiveFeedback(
                factoryId,
                request.getIntentCode(),
                request.getMatchedKeywords());

        return ResponseEntity.ok(ApiResponse.successMessage("正向反馈已记录"));
    }

    // Sprint 11 Round 2: removed @RequirePermission({"system:read_write"}) — feedback path.
    @PostMapping("/feedback/negative")
    @Operation(summary = "记录负向反馈", description = "当用户拒绝匹配结果并选择其他意图时调用")
    public ResponseEntity<ApiResponse<Void>> recordNegativeFeedback(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @RequestBody NegativeFeedbackRequest request) {

        log.info("记录负向反馈: factoryId={}, rejected={}, selected={}, keywords={}",
                factoryId, request.getRejectedIntentCode(),
                request.getSelectedIntentCode(), request.getMatchedKeywords());

        aiIntentService.recordNegativeFeedback(
                factoryId,
                request.getRejectedIntentCode(),
                request.getSelectedIntentCode(),
                request.getMatchedKeywords());

        return ResponseEntity.ok(ApiResponse.successMessage("负向反馈已记录"));
    }

    /**
     * 意图识别反馈接口
     * 用户可以纠正错误的意图识别结果，系统自动学习
     */
    // Sprint 11 Round 2: removed @RequirePermission({"system:read_write"}) — feedback path.
    @PostMapping("/feedback")
    @Operation(summary = "提交意图识别反馈", description = "用户可以纠正错误的意图识别结果，系统自动学习")
    public ResponseEntity<ApiResponse<Void>> submitIntentFeedback(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @RequestBody IntentFeedbackRequest request,
            HttpServletRequest httpRequest) {

        // Sprint 11.5 P0 (2026-05-23): cookie-aware token extraction
        String token = extractToken(httpRequest);
        Long userId = jwtUtil.getUserIdFromToken(token);

        log.info("提交意图反馈: factoryId={}, userId={}, input='{}', matched={}, correct={}, isCorrect={}",
                factoryId, userId, request.getUserInput(), request.getMatchedIntentCode(),
                request.getCorrectIntentCode(), request.getIsCorrect());

        aiIntentService.processIntentFeedback(factoryId, userId, request);

        return ResponseEntity.ok(ApiResponse.successMessage("反馈已记录，系统将自动学习"));
    }

    // ==================== 缓存管理 ====================

    @RequirePermission({"system:read_write"})
    @PostMapping("/cache/refresh")
    @PreAuthorize("hasAnyRole('FACTORY_SUPER_ADMIN', 'FACTORY_ADMIN')")
    @Operation(summary = "刷新意图缓存", description = "清除并重新加载意图配置缓存（仅工厂管理员）")
    public ResponseEntity<ApiResponse<Void>> refreshCache(
            @Parameter(description = "工厂ID") @PathVariable String factoryId) {

        aiIntentService.refreshCache();
        log.info("Refreshed AI intent cache");
        return ResponseEntity.ok(ApiResponse.successMessage("意图缓存已刷新"));
    }

    @RequirePermission({"system:read_write"})
    @PostMapping("/cache/clear")
    @PreAuthorize("hasAnyRole('FACTORY_SUPER_ADMIN', 'FACTORY_ADMIN')")
    @Operation(summary = "清除意图缓存", description = "清除意图配置缓存（仅工厂管理员）")
    public ResponseEntity<ApiResponse<Void>> clearCache(
            @Parameter(description = "工厂ID") @PathVariable String factoryId) {

        aiIntentService.clearCache();
        log.info("Cleared AI intent cache");
        return ResponseEntity.ok(ApiResponse.successMessage("意图缓存已清除"));
    }

    // ==================== DTO Classes ====================

    /**
     * 意图识别请求
     */
    @lombok.Data
    public static class IntentRecognitionRequest {
        private String userInput;
    }

    /**
     * 意图识别结果
     */
    @lombok.Data
    public static class IntentRecognitionResult {
        private String userInput;
        private boolean matched;
        private String intentCode;
        private String intentName;
        private String category;
        private String sensitivityLevel;
        private Integer quotaCost;
        private Boolean requiresApproval;
        private Double confidence;      // 匹配置信度 (BUG-001/002 修复新增)
        private String matchMethod;     // 匹配方法 (REGEX/KEYWORD/SEMANTIC/LLM)
        private Map<String, Long> timingMs;  // 各阶段耗时
    }

    /**
     * 权限检查结果
     */
    @lombok.Data
    public static class PermissionCheckResult {
        private String intentCode;
        private String userRole;
        private boolean hasPermission;
        private boolean requiresApproval;
        private int quotaCost;
    }

    /**
     * 启用状态请求
     */
    @lombok.Data
    public static class ActiveStatusRequest {
        private boolean active;
    }

    /**
     * 正向反馈请求
     */
    @lombok.Data
    public static class PositiveFeedbackRequest {
        /** 意图代码 */
        private String intentCode;
        /** 匹配到的关键词列表 */
        private java.util.List<String> matchedKeywords;
    }

    /**
     * 负向反馈请求
     */
    @lombok.Data
    public static class NegativeFeedbackRequest {
        /** 被拒绝的意图代码 */
        private String rejectedIntentCode;
        /** 用户选择的正确意图代码 */
        private String selectedIntentCode;
        /** 原匹配到的关键词列表 */
        private java.util.List<String> matchedKeywords;
    }

    /**
     * 回滚请求
     */
    @lombok.Data
    public static class RollbackRequest {
        /** 回滚原因 */
        private String reason;
    }
}

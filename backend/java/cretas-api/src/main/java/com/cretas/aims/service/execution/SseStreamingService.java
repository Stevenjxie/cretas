package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.capability.FactoryCapabilityPack;
import com.cretas.aims.ai.capability.FactoryCapabilityPackRoutingPolicy;
import com.cretas.aims.ai.dto.ChatCompletionRequest;
import com.cretas.aims.ai.dto.ChatMessage;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.config.IntentKnowledgeBase.QuestionType;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.cache.SemanticCacheHit;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.exception.LlmSchemaValidationException;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.AnalysisRouterService;
import com.cretas.aims.service.ResultFormatterService;
import com.cretas.aims.service.SemanticCacheService;
import com.cretas.aims.service.SlotFillingService;
import com.cretas.aims.util.ErrorSanitizer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

/**
 * SSE 流式执行服务
 *
 * 负责 SseEmitter 管理、流式意图执行、流式对话回复、
 * SSE 事件格式化与发送。拥有独占的 SSE 线程池。
 */
@Slf4j
@Service
public class SseStreamingService {

    // R16: 餐饮租户 SSE no-tool 死胡同出口先走 tiered 路由 (非反转路径的保险)。
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.ai.tool.impl.restaurant.TieredIntentDelegate sseTieredIntentDelegate;

    // Card4 (2026-07-28): SSE tiered-first parity flag — mirrors
    // IntentExecutionOrchestrator's cretas.restaurant.tiered-first.enabled binding so the
    // streaming channel can be toggled in lockstep with the synchronous /execute path.
    // Default true in a real Spring context; plain `new SseStreamingService(...)` unit tests
    // that don't go through Spring get Java's default `false` unless a test explicitly opts
    // in via ReflectionTestUtils, which keeps existing SSE tests unaffected.
    @org.springframework.beans.factory.annotation.Value("${cretas.restaurant.tiered-first.enabled:true}")
    private boolean tieredFirstEnabled;

    private static final long SSE_TIMEOUT_MS = 120_000L;

    private final ExecutorService sseExecutor = java.util.concurrent.Executors.newFixedThreadPool(
            8, r -> { Thread t = new Thread(r, "intent-sse"); t.setDaemon(true); return t; });

    private final AIIntentService aiIntentService;
    private final SemanticCacheService semanticCacheService;
    private final IntentKnowledgeBase knowledgeBase;
    private final AnalysisRouterService analysisRouterService;
    private final ObjectMapper objectMapper;
    private final ToolRegistry toolRegistry;
    private final ToolDispatchService toolDispatchService;
    private final DynamicToolSelectionService dynamicToolSelectionService;
    private final DashScopeClient dashScopeClient;
    private final DashScopeConfig dashScopeConfig;

    @Autowired(required = false)
    private ResultFormatterService resultFormatterService;

    @Autowired(required = false)
    private SlotFillingService slotFillingService;

    // Sprint 13 #305 业态门控: shared gate so the SSE path gates identically to /execute.
    @Autowired
    private BusinessTypeGate businessTypeGate;

    /** Default-off boundary shared with the non-streaming execute path. */
    @Autowired
    private FactoryCapabilityPackRoutingPolicy factoryCapabilityPackRoutingPolicy;

    @Autowired
    private WriteGuardService writeGuardService;

    @Autowired(required = false)
    private com.cretas.aims.service.restaurant.RestaurantGrossMarginChatRouteSelector
            restaurantGrossMarginChatRouteSelector;

    @Autowired
    public SseStreamingService(@Lazy AIIntentService aiIntentService,
                               SemanticCacheService semanticCacheService,
                               IntentKnowledgeBase knowledgeBase,
                               AnalysisRouterService analysisRouterService,
                               ObjectMapper objectMapper,
                               ToolRegistry toolRegistry,
                               ToolDispatchService toolDispatchService,
                               DynamicToolSelectionService dynamicToolSelectionService,
                               DashScopeClient dashScopeClient,
                               DashScopeConfig dashScopeConfig) {
        this.aiIntentService = aiIntentService;
        this.semanticCacheService = semanticCacheService;
        this.knowledgeBase = knowledgeBase;
        this.analysisRouterService = analysisRouterService;
        this.objectMapper = objectMapper;
        this.toolRegistry = toolRegistry;
        this.toolDispatchService = toolDispatchService;
        this.dynamicToolSelectionService = dynamicToolSelectionService;
        this.dashScopeClient = dashScopeClient;
        this.dashScopeConfig = dashScopeConfig;
    }

    @PreDestroy
    public void destroy() {
        log.info("关闭 SSE 执行器线程池...");
        sseExecutor.shutdown();
    }

    /**
     * SSE 线程池 — 暴露给 MultiIntentExecutionService 用于并行执行
     */
    public ExecutorService getSseExecutor() {
        return sseExecutor;
    }

    // ==================== 公开方法 ====================

    /**
     * SSE 流式执行意图
     */
    public SseEmitter executeStream(String factoryId, IntentExecuteRequest request,
                                     Long userId, String userRole) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        emitter.onCompletion(() -> log.debug("SSE connection completed: factoryId={}", factoryId));
        emitter.onTimeout(() -> log.warn("SSE connection timeout: factoryId={}", factoryId));
        emitter.onError(e -> log.error("SSE connection error: factoryId={}", factoryId, e));

        sseExecutor.execute(() -> executeStreamAsync(emitter, factoryId, request, userId, userRole));

        return emitter;
    }

    // ==================== 内部方法 ====================

    /**
     * 异步执行流式意图处理
     */
    private void executeStreamAsync(SseEmitter emitter, String factoryId,
                                     IntentExecuteRequest request, Long userId, String userRole) {
        try {
            long startTime = System.currentTimeMillis();

            // 1. 开始事件
            sendSseEvent(emitter, "start", Map.of(
                    "message", "开始处理...",
                    "timestamp", LocalDateTime.now().toString()
            ));

            String userInput = request.getUserInput();

            FactoryCapabilityPackRoutingPolicy.Route factoryPackRoute =
                    evaluateFactoryPackRoute(factoryId, request, userRole);
            if (factoryPackRoute.shouldBlock()) {
                streamTerminalResponse(
                        emitter,
                        buildFactoryPackNoMatch(factoryPackRoute, factoryPackRoute.reason()),
                        startTime);
                return;
            }
            boolean factoryPackConstrained = factoryPackRoute.isConstrained();

            // The stream historically ignored explicit intent codes. A constrained Factory Pack
            // must converge explicit and recognized intents before any Restaurant/general/skill
            // route can run, without adding an LLM call.
            if (factoryPackConstrained
                    && request.getIntentCode() != null
                    && !request.getIntentCode().isBlank()) {
                Optional<AIIntentConfig> explicitIntent =
                        aiIntentService.getIntentByCode(factoryId, request.getIntentCode());
                if (explicitIntent.isEmpty()) {
                    streamTerminalResponse(
                            emitter,
                            buildFactoryPackNoMatch(factoryPackRoute, "explicit-intent-not-found"),
                            startTime);
                    return;
                }
                AIIntentConfig intent = explicitIntent.get();
                IntentMatchResult explicitMatch = IntentMatchResult.builder()
                        .bestMatch(intent)
                        .confidence(1.0d)
                        .matchMethod(IntentMatchResult.MatchMethod.EXACT)
                        .requiresConfirmation(false)
                        .questionType(QuestionType.OPERATIONAL_COMMAND)
                        .build();
                sendSseEvent(emitter, "intent_recognized", Map.of(
                        "intentCode", intent.getIntentCode(),
                        "intentName", intent.getIntentName(),
                        "intentCategory", intent.getIntentCategory(),
                        "confidence", 1.0d,
                        "matchMethod", "EXPLICIT"));
                executeAndStreamResult(
                        emitter, factoryId, request, intent, userId, userRole, startTime,
                        explicitMatch, factoryPackRoute);
                return;
            }

            // Card4 (2026-07-28): restaurant tiered-first, aligned to
            // IntentExecutionOrchestrator.execute() :371-400. Restaurant natural-language reads
            // get one top-level semantic authority on the SSE channel too — ahead of the bounded
            // Runtime selector, early question-type detection and the semantic cache below, so a
            // streamed answer can no longer diverge from /execute's answer to the same question.
            // Explicit intent codes are handled above and never reach here; write verbs and
            // explicit read-vetoes fall through unchanged to the legacy branches.
            //
            // hasExplicitReadVeto/isRestaurantWriteRequest are called on IntentExecutionOrchestrator
            // itself (package-private static there) rather than duplicated here — a card4 fix-round
            // finding (2026-07-28): an earlier copy of the veto phrase-matcher in this file silently
            // diverged from orchestrator's dimension-contrast-aware rewrite (卡1, PR #1914), which
            // reintroduced exactly the "same question, different entry, different answer" bug this
            // card exists to close. Single source of truth — never copy this logic again.
            boolean restaurantTenant = isRestaurantTenant(factoryId);
            boolean requiresRestaurantSemanticPlan = tieredFirstEnabled
                    && !factoryPackConstrained
                    && !Boolean.TRUE.equals(request.getPreviewOnly())
                    && restaurantTenant
                    && userInput != null && !userInput.isEmpty()
                    && !IntentExecutionOrchestrator.hasExplicitReadVeto(userInput)
                    && !IntentExecutionOrchestrator.isRestaurantWriteRequest(userInput);
            if (requiresRestaurantSemanticPlan) {
                IntentExecuteResponse tieredFirst = tryRestaurantTieredDelegate(
                        factoryId, userInput, request, "sse_tiered_first");
                if (tieredFirst != null) {
                    log.info("[SSE][Branch:TieredFirst] restaurant semantic planner accepted: factoryId={}",
                            factoryId);
                    streamTerminalResponse(emitter, tieredFirst, startTime);
                    return;
                }
                // Fail closed: no delegate available (or it errored) means no 8-layer legacy
                // fallback and no stale-cache leak for this restaurant question — an explicit
                // "no answer this turn" beats silently returning a possibly wrong/old answer.
                log.warn("[SSE][Branch:TieredFirst] restaurant semantic planner unavailable; "
                        + "fail closed: factoryId={}", factoryId);
                streamTerminalResponse(emitter, buildRestaurantTieredFirstFailClosedResponse(request), startTime);
                return;
            }

            // Keep the stream front door aligned with /execute. This returns a bounded launch
            // instruction only; it never proxies the restaurant Runtime's own SSE stream.
            if (!factoryPackConstrained
                    && !Boolean.TRUE.equals(request.getPreviewOnly())
                    && (request.getIntentCode() == null || request.getIntentCode().isBlank())) {
                Optional<IntentExecuteResponse> restaurantAgentRoute =
                        restaurantGrossMarginChatRouteSelector == null
                                ? Optional.empty()
                                : restaurantGrossMarginChatRouteSelector.select(
                                        factoryId, userInput, userRole);
                if (restaurantAgentRoute.isPresent()) {
                    IntentExecuteResponse response = restaurantAgentRoute.get();
                    sendSseEvent(emitter, "intent_recognized", Map.of(
                            "intentCode", response.getIntentCode(),
                            "intentName", response.getIntentName(),
                            "intentCategory", response.getIntentCategory(),
                            "confidence", response.getConfidence(),
                            "matchMethod", response.getMatchMethod()));
                    sendSseEvent(emitter, "result", response);
                    sendSseEvent(emitter, "complete", Map.of(
                            "status", response.getStatus(),
                            "cacheHit", false,
                            "totalLatencyMs", System.currentTimeMillis() - startTime));
                    emitter.complete();
                    return;
                }
            }

            // 1.5. 早期问题类型检测
            if (!factoryPackConstrained && userInput != null && !userInput.isEmpty()) {
                QuestionType earlyQuestionType = knowledgeBase.detectQuestionType(userInput);
                if (earlyQuestionType == QuestionType.GENERAL_QUESTION ||
                    earlyQuestionType == QuestionType.CONVERSATIONAL) {

                    boolean isAnalysis = earlyQuestionType == QuestionType.GENERAL_QUESTION
                            && analysisRouterService.isAnalysisRequest(userInput, earlyQuestionType);
                    Optional<String> foodPhrase = knowledgeBase.matchPhrase(userInput);
                    boolean isFood = foodPhrase.isPresent() && "FOOD_KNOWLEDGE_QUERY".equals(foodPhrase.get());

                    if (!isAnalysis && !isFood) {
                        log.info("Stream: 早期检测到{}，使用流式 LLM 回复", earlyQuestionType);
                        streamConversationalResponse(emitter, factoryId, userInput, earlyQuestionType,
                                request.getEnableThinking(), request.getThinkingBudget(), startTime);
                        return;
                    }
                }
            }

            // 2. 查询语义缓存
            // Card4 (2026-07-28): restaurant tenants never read the semantic cache on the SSE
            // channel, even when tiered-first above didn't fire (write verb / read-veto /
            // tiered-first disabled). The cache can hold an answer up to 1h stale; the tiered
            // planner (when eligible) already produced a fresh answer above, and every other
            // restaurant branch below re-derives its own answer rather than replaying an old one.
            boolean skipCacheForRestaurant = factoryPackConstrained || restaurantTenant;
            SemanticCacheHit cacheHit = skipCacheForRestaurant
                    ? SemanticCacheHit.miss(0L)
                    : semanticCacheService.queryCache(factoryId, userInput);

            if (!skipCacheForRestaurant && cacheHit.isHit()) {
                sendSseEvent(emitter, "cache_hit", Map.of(
                        "hitType", cacheHit.getHitType(),
                        "similarity", cacheHit.getSimilarity() != null ? cacheHit.getSimilarity() : 1.0,
                        "latencyMs", cacheHit.getLatencyMs()
                ));

                if (cacheHit.hasExecutionResult()) {
                    IntentExecuteResponse cachedResponse = deserializeResponse(cacheHit.getExecutionResult());
                    if (cachedResponse != null) {
                        sendSseEvent(emitter, "result", cachedResponse);
                        sendSseEvent(emitter, "complete", Map.of(
                                "status", "SUCCESS",
                                "cacheHit", true,
                                "totalLatencyMs", System.currentTimeMillis() - startTime
                        ));
                        emitter.complete();
                        return;
                    }
                }

                IntentMatchResult cachedMatch = deserializeIntentResult(cacheHit.getIntentResult());
                if (cachedMatch != null && cachedMatch.hasMatch()) {
                    sendSseEvent(emitter, "intent_recognized", Map.of(
                            "intentCode", cachedMatch.getBestMatch().getIntentCode(),
                            "intentName", cachedMatch.getBestMatch().getIntentName(),
                            "confidence", cachedMatch.getConfidence(),
                            "matchMethod", "CACHE"
                    ));
                    executeAndStreamResult(emitter, factoryId, request, cachedMatch.getBestMatch(),
                            userId, userRole, startTime, cachedMatch, factoryPackRoute);
                    return;
                }
            }

            // 3. 缓存未命中
            sendSseEvent(emitter, "cache_miss", Map.of(
                    "latencyMs", cacheHit.getLatencyMs()
            ));

            // 4. 意图识别
            sendSseEvent(emitter, "progress", Map.of(
                    "stage", "intent_recognition",
                    "message", "正在识别意图..."
            ));

            IntentMatchResult matchResult;
            try {
                matchResult = aiIntentService.recognizeIntentWithConfidence(
                        userInput, factoryId, 3, userId, userRole, request.getSessionId());
            } catch (LlmSchemaValidationException e) {
                IntentExecuteResponse validationFailureResponse = buildValidationFailureResponse(e);
                sendSseEvent(emitter, "result", validationFailureResponse);
                sendSseEvent(emitter, "complete", Map.of(
                        "status", "VALIDATION_FAILED",
                        "cacheHit", false,
                        "totalLatencyMs", System.currentTimeMillis() - startTime
                ));
                emitter.complete();
                return;
            }

            // 5. 处理识别结果
            if (!matchResult.hasMatch()) {
                if (factoryPackConstrained) {
                    String reason = matchResult.getQuestionType() == QuestionType.GENERAL_QUESTION
                            || matchResult.getQuestionType() == QuestionType.CONVERSATIONAL
                            ? "general-analysis-not-allowed"
                            : "intent-not-allowed";
                    streamTerminalResponse(
                            emitter, buildFactoryPackNoMatch(factoryPackRoute, reason), startTime);
                } else {
                    IntentExecuteResponse noMatchResponse = buildNoMatchResponseForStream(matchResult, factoryId);
                    sendSseEvent(emitter, "result", noMatchResponse);
                    sendSseEvent(emitter, "complete", Map.of(
                            "status", noMatchCompleteStatus(matchResult),
                            "cacheHit", false,
                            "totalLatencyMs", System.currentTimeMillis() - startTime
                    ));
                    emitter.complete();
                }
                return;
            }

            AIIntentConfig intent = matchResult.getBestMatch();
            sendSseEvent(emitter, "intent_recognized", Map.of(
                    "intentCode", intent.getIntentCode(),
                    "intentName", intent.getIntentName(),
                    "intentCategory", intent.getIntentCategory(),
                    "confidence", matchResult.getConfidence(),
                    "matchMethod", matchResult.getMatchMethod() != null ? matchResult.getMatchMethod().name() : "UNKNOWN"
            ));

            // Pack-constrained intents authorize and terminate here. This deliberately precedes
            // generic confirmation, legacy Skill and slot-filling branches so none can bypass the
            // Pack allowlist or turn a declared workflow mutation into execution.
            if (factoryPackConstrained) {
                executeAndStreamResult(
                        emitter, factoryId, request, intent, userId, userRole, startTime,
                        matchResult, factoryPackRoute);
                return;
            }

            // 6. 需要确认的情况
            if (Boolean.TRUE.equals(matchResult.getRequiresConfirmation())
                    && !Boolean.TRUE.equals(request.getForceExecute())) {
                IntentExecuteResponse clarificationResponse = buildClarificationResponseForStream(matchResult, factoryId);
                sendSseEvent(emitter, "result", clarificationResponse);
                sendSseEvent(emitter, "complete", Map.of(
                        "status", "NEED_CLARIFICATION",
                        "cacheHit", false,
                        "totalLatencyMs", System.currentTimeMillis() - startTime
                ));
                emitter.complete();
                return;
            }

            // 7a. Skill 路由
            if (dynamicToolSelectionService.isSkillsEnabled()) {
                try {
                    IntentExecuteResponse skillResponse = dynamicToolSelectionService.trySkillRoute(
                            request.getUserInput(), factoryId, userId);
                    if (skillResponse != null) {
                        sendSseEvent(emitter, "result", skillResponse);
                        sendSseEvent(emitter, "complete", Map.of(
                                "status", skillResponse.getStatus(),
                                "cacheHit", false,
                                "totalLatencyMs", System.currentTimeMillis() - startTime
                        ));
                        emitter.complete();
                        return;
                    }

                    // Sprint 9 P0.1 fix (2026-05-21): tool_name=NULL intent (WORKDESK 等)
                    // 当 trySkillRoute (keyword 匹用户原文) 失败时, 用 intent_code 显式查 Skill.
                    String streamBoundToolName = intent.getToolName();
                    if (streamBoundToolName == null || streamBoundToolName.isBlank()) {
                        IntentExecuteResponse explicitSkillResp = dynamicToolSelectionService
                                .tryExplicitSkillRouteForIntent(intent, request.getUserInput(), factoryId, userId);
                        if (explicitSkillResp != null) {
                            sendSseEvent(emitter, "result", explicitSkillResp);
                            sendSseEvent(emitter, "complete", Map.of(
                                    "status", explicitSkillResp.getStatus(),
                                    "cacheHit", false,
                                    "totalLatencyMs", System.currentTimeMillis() - startTime
                            ));
                            emitter.complete();
                            return;
                        }
                    }
                } catch (Exception e) {
                    log.warn("[SSE] Skill 路由异常，回退到 Tool: {}", e.getMessage());
                }
            }

            // 7b. Slot Filling
            if (userId != null && !Boolean.TRUE.equals(request.getSkipSlotFilling())
                    && slotFillingService != null) {
                try {
                    IntentExecuteResponse slotFillingResponse = slotFillingService.checkAndStartSlotFilling(
                            factoryId, userId, intent, request, matchResult);
                    if (slotFillingResponse != null) {
                        log.info("[SSE] 触发 Slot Filling: intentCode={}", intent.getIntentCode());
                        if (slotFillingResponse.getFormattedText() == null
                                && slotFillingResponse.getMessage() != null
                                && slotFillingResponse.getMessage().length() >= 5) {
                            slotFillingResponse.setFormattedText(slotFillingResponse.getMessage());
                        }
                        sendSseEvent(emitter, "result", slotFillingResponse);
                        sendSseEvent(emitter, "complete", Map.of(
                                "status", "NEED_MORE_INFO",
                                "cacheHit", false,
                                "totalLatencyMs", System.currentTimeMillis() - startTime
                        ));
                        emitter.complete();
                        return;
                    }
                } catch (Exception e) {
                    log.warn("[SSE] Slot Filling 异常，直接执行 Tool: {}", e.getMessage());
                }
            }

            // 7c. 执行意图 (Tool)
            executeAndStreamResult(
                    emitter, factoryId, request, intent, userId, userRole, startTime,
                    matchResult, factoryPackRoute);

        } catch (Exception e) {
            log.error("SSE 执行失败: factoryId={}, error={}", factoryId, e.getMessage(), e);
            try {
                sendSseEvent(emitter, "error", Map.of(
                        "message", ErrorSanitizer.sanitize(e),
                        "type", ErrorSanitizer.getSafeTypeName(e)
                ));
            } catch (Exception ignored) {
            }
            emitter.completeWithError(e);
        }
    }

    /**
     * 执行意图并流式返回结果
     */
    private void executeAndStreamResult(SseEmitter emitter, String factoryId,
                                         IntentExecuteRequest request, AIIntentConfig intent,
                                         Long userId, String userRole, long startTime,
                                         IntentMatchResult matchResult,
                                         FactoryCapabilityPackRoutingPolicy.Route factoryPackRoute)
            throws IOException {
        // 权限检查
        if (!aiIntentService.hasPermission(intent.getIntentCode(), userRole)) {
            IntentExecuteResponse noPermissionResponse = IntentExecuteResponse.builder()
                    .intentRecognized(true)
                    .intentCode(intent.getIntentCode())
                    .intentName(intent.getIntentName())
                    .status("NO_PERMISSION")
                    .message("您没有权限执行此操作。需要角色: " + intent.getRequiredRoles())
                    .executedAt(LocalDateTime.now())
                    .build();
            sendSseEvent(emitter, "result", noPermissionResponse);
            sendSseEvent(emitter, "complete", Map.of(
                    "status", "NO_PERMISSION",
                    "totalLatencyMs", System.currentTimeMillis() - startTime
            ));
            emitter.complete();
            return;
        }

        IntentExecuteResponse factoryPackResponse =
                applyFactoryPackDecision(factoryPackRoute, intent);
        if (factoryPackResponse != null) {
            streamTerminalResponse(emitter, factoryPackResponse, startTime);
            return;
        }

        // 审批检查
        if (intent.needsApproval() && !Boolean.TRUE.equals(request.getForceExecute())) {
            IntentExecuteResponse approvalResponse = IntentExecuteResponse.builder()
                    .intentRecognized(true)
                    .intentCode(intent.getIntentCode())
                    .intentName(intent.getIntentName())
                    .status("PENDING_APPROVAL")
                    .message("此操作需要审批，已提交审批请求")
                    .requiresApproval(true)
                    .executedAt(LocalDateTime.now())
                    .build();
            sendSseEvent(emitter, "result", approvalResponse);
            sendSseEvent(emitter, "complete", Map.of(
                    "status", "PENDING_APPROVAL",
                    "totalLatencyMs", System.currentTimeMillis() - startTime
            ));
            emitter.complete();
            return;
        }

        // 业态门控 (Sprint 13 #305): domain-exclusive intent on a mismatched factory.type →
        // honest empty-state + domain next-action, instead of executing a tool with no data.
        if (!factoryPackRoute.isConstrained()) {
            Optional<IntentExecuteResponse> gate = businessTypeGate.check(factoryId, intent);
            if (gate.isPresent()) {
                IntentExecuteResponse gateResponse = gate.get();
                sendSseEvent(emitter, "result", gateResponse);
                sendSseEvent(emitter, "complete", Map.of(
                        "status", gateResponse.getStatus() != null ? gateResponse.getStatus() : "NOT_APPLICABLE",
                        "totalLatencyMs", System.currentTimeMillis() - startTime
                ));
                emitter.complete();
                return;
            }
        }

        // 发送执行中事件
        sendSseEvent(emitter, "executing", Map.of(
                "intentCode", intent.getIntentCode(),
                "intentName", intent.getIntentName()
        ));

        // 路由到 Tool
        String toolName = intent.getToolName();
        IntentExecuteResponse response;

        if (toolName != null && !toolName.isEmpty()) {
            Optional<ToolExecutor> toolOpt = toolRegistry.getExecutor(toolName);
            if (toolOpt.isPresent()) {
                log.info("[SSE] 使用 Tool 执行: intentCode={}, toolName={}", intent.getIntentCode(), toolName);
                response = toolDispatchService.executeWithTool(toolOpt.get(), factoryId, request, intent, userId, userRole, matchResult);
            } else {
                log.warn("[SSE] Tool 未找到: toolName={}", toolName);
                response = noToolResponseWithRestaurantFallback(intent, factoryId, request);
            }
        } else {
            // Sprint 9 P0.1 fix (2026-05-21): tool_name=NULL — 走 explicit Skill fallback (cover
            // 7a Skill route 已尝试但仍 fall through 到 executeAndStreamResult 的 corner case).
            IntentExecuteResponse explicitSkillFallback = dynamicToolSelectionService
                    .tryExplicitSkillRouteForIntent(intent, request.getUserInput(), factoryId, userId);
            if (explicitSkillFallback != null) {
                log.info("[SSE] 显式 Skill 路由 (executeAndStreamResult fallback) 成功: intentCode={}",
                        intent.getIntentCode());
                response = explicitSkillFallback;
            } else {
                log.warn("[SSE] 无 Tool 绑定 + 无 Skill 匹配: intentCode={}", intent.getIntentCode());
                response = noToolResponseWithRestaurantFallback(intent, factoryId, request);
            }
        }

        // 格式化结果
        boolean isSuccessStatus = "SUCCESS".equals(response.getStatus()) || "COMPLETED".equals(response.getStatus());
        if (resultFormatterService != null && isSuccessStatus && response.getResultData() != null) {
            try {
                resultFormatterService.formatAndSet(response);
            } catch (Exception e) {
                log.warn("[SSE] 结果格式化异常: {}", e.getMessage());
            }
        }
        if (response.getFormattedText() == null && response.getMessage() != null
                && response.getMessage().length() >= 5) {
            response.setFormattedText(response.getMessage());
        }

        sendSseEvent(emitter, "result", response);

        // 缓存结果
        if ("COMPLETED".equals(response.getStatus()) || "SUCCESS".equals(response.getStatus())) {
            try {
                semanticCacheService.cacheResult(factoryId, request.getUserInput(), matchResult, response);
            } catch (Exception e) {
                log.warn("缓存执行结果失败: {}", e.getMessage());
            }
        }

        sendSseEvent(emitter, "complete", Map.of(
                "status", response.getStatus(),
                "cacheHit", false,
                "totalLatencyMs", System.currentTimeMillis() - startTime
        ));
        emitter.complete();
    }

    /**
     * 流式生成对话回复
     */
    private void streamConversationalResponse(SseEmitter emitter, String factoryId,
                                               String userInput, QuestionType questionType,
                                               Boolean enableThinking, Integer thinkingBudget,
                                               long startTime) throws IOException {
        String systemPrompt;
        if (questionType == QuestionType.GENERAL_QUESTION) {
            systemPrompt = """
                你是白垩纪AI Agent的智能助手。用户正在询问一个关于生产管理、质量控制或食品安全的通用咨询问题。

                请根据以下原则回答：
                1. 提供专业、实用的建议
                2. 结合食品加工行业的最佳实践
                3. 如果问题涉及具体数据查询，建议用户使用系统的具体功能
                4. 回答简洁明了，不超过300字
                5. 使用中文回答

                注意：这不是一个具体的系统操作指令，而是通用知识咨询。
                """;
        } else {
            systemPrompt = """
                你是白垩纪AI Agent的智能助手。用户发起了一个日常对话。

                请根据以下原则回答：
                1. 友好、亲切地回应
                2. 如果用户打招呼，简单回应并询问是否需要帮助
                3. 适时引导用户使用系统功能
                4. 回答简洁，不超过100字
                5. 使用中文回答
                """;
        }

        boolean useThinking = Boolean.TRUE.equals(enableThinking)
                && questionType == QuestionType.GENERAL_QUESTION
                && dashScopeConfig.isThinkingEnabled();

        String model = useThinking ? dashScopeConfig.getModel() : dashScopeConfig.getFastModel();
        int maxTokens = useThinking ? 2000 : 500;

        ChatCompletionRequest aiRequest = ChatCompletionRequest.builder()
                .model(model)
                .messages(List.of(ChatMessage.system(systemPrompt), ChatMessage.user(userInput)))
                .temperature(0.7)
                .maxTokens(maxTokens)
                .extraBody(ChatCompletionRequest.ExtraBody.builder()
                        .enableThinking(useThinking).build())
                .build();

        sendSseEvent(emitter, "meta", Map.of(
                "model", model,
                "thinking", useThinking,
                "questionType", questionType.name()));

        try {
            dashScopeClient.chatCompletionStream(aiRequest,
                token -> {
                    try {
                        emitter.send(SseEmitter.event().name("token").data(token));
                    } catch (Exception e) {
                        log.debug("Client disconnected during stream token");
                    }
                },
                response -> {
                    try {
                        String fullContent = response.getContent() != null ? response.getContent() : "";
                        Integer tokensUsed = response.getUsage() != null
                                ? response.getUsage().getTotalTokens() : null;
                        String finishReason = response.getChoices() != null && !response.getChoices().isEmpty()
                                ? response.getChoices().get(0).getFinishReason() : "stop";

                        IntentExecuteResponse fullResult = IntentExecuteResponse.builder()
                                .intentRecognized(false)
                                .status("COMPLETED")
                                .message(fullContent)
                                .formattedText(fullContent)
                                .executedAt(LocalDateTime.now())
                                .build();
                        sendSseEvent(emitter, "result", fullResult);

                        sendSseEvent(emitter, "complete", Map.of(
                                "status", "SUCCESS",
                                "fullContent", fullContent,
                                "tokensUsed", tokensUsed != null ? tokensUsed : 0,
                                "finishReason", finishReason,
                                "model", model,
                                "totalLatencyMs", System.currentTimeMillis() - startTime
                        ));
                        emitter.complete();
                    } catch (Exception e) {
                        log.debug("Error sending stream complete: {}", e.getMessage());
                    }
                });
        } catch (Exception e) {
            log.error("Stream conversational response failed: {}", e.getMessage());
            String fallback = questionType == QuestionType.GENERAL_QUESTION
                    ? "抱歉，我暂时无法回答您的问题。您可以尝试询问具体的系统操作。"
                    : "您好！有什么可以帮您的吗？";

            IntentExecuteResponse fallbackResult = IntentExecuteResponse.builder()
                    .intentRecognized(false)
                    .status("COMPLETED")
                    .message(fallback)
                    .formattedText(fallback)
                    .executedAt(LocalDateTime.now())
                    .build();
            sendSseEvent(emitter, "result", fallbackResult);
            sendSseEvent(emitter, "complete", Map.of(
                    "status", "LLM_ERROR",
                    "fullContent", fallback,
                    "totalLatencyMs", System.currentTimeMillis() - startTime
            ));
            emitter.complete();
        }
    }

    // ==================== 工具方法 ====================

    private FactoryCapabilityPackRoutingPolicy.Route evaluateFactoryPackRoute(
            String factoryId, IntentExecuteRequest request, String userRole) {
        if (factoryCapabilityPackRoutingPolicy == null) {
            return new FactoryCapabilityPackRoutingPolicy.Route(
                    FactoryCapabilityPackRoutingPolicy.RouteStatus.DISABLED,
                    null,
                    null,
                    null,
                    "feature-disabled");
        }
        return factoryCapabilityPackRoutingPolicy.evaluate(
                factoryId, userRole, request != null ? request.getUserInput() : null);
    }

    private IntentExecuteResponse applyFactoryPackDecision(
            FactoryCapabilityPackRoutingPolicy.Route route, AIIntentConfig intent) {
        if (route == null || !route.isConstrained()) {
            return null;
        }
        boolean writeIntent = writeGuardService.isWriteIntent(intent);
        FactoryCapabilityPackRoutingPolicy.ExecutionDecision decision =
                factoryCapabilityPackRoutingPolicy.authorize(
                        route, intent.getIntentCode(), intent.getToolName(), writeIntent);
        return switch (decision.status()) {
            case ALLOW_READ, NOT_APPLICABLE -> null;
            case GUIDANCE -> buildFactoryPackWorkflowGuidance(
                    route, intent, decision.workflowReference());
            case NO_MATCH -> buildFactoryPackNoMatch(route, decision.reason());
        };
    }

    private IntentExecuteResponse buildFactoryPackNoMatch(
            FactoryCapabilityPackRoutingPolicy.Route route, String reason) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("routeType", "FACTORY_CAPABILITY_PACK");
        metadata.put("policyResult", "NO_MATCH");
        metadata.put("reason", reason == null ? "not-allowed" : reason);
        if (route != null && route.pack() != null) {
            metadata.put("packId", route.pack().packId());
            metadata.put("packVersion", route.pack().version());
        }
        String message = "该请求不在当前工厂岗位能力包的受控范围内。"
                + "请改为询问本岗位已配置的查询、表单或固定导航事项。";
        return IntentExecuteResponse.builder()
                .intentRecognized(false)
                .status("FACTORY_PACK_NO_MATCH")
                .message(message)
                .formattedText(message)
                .metadata(Map.copyOf(metadata))
                .executedAt(LocalDateTime.now())
                .build();
    }

    private IntentExecuteResponse buildFactoryPackWorkflowGuidance(
            FactoryCapabilityPackRoutingPolicy.Route route,
            AIIntentConfig intent,
            FactoryCapabilityPack.WorkflowReference reference) {
        boolean mutation = reference.mutation();
        String message = mutation
                ? "该请求涉及业务写入，AI 助手不会代为执行。请通过受控入口「"
                        + reference.referenceId() + "」核对并确认。"
                : "请通过能力包声明的固定入口「" + reference.referenceId() + "」继续。";

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("workflowReference", reference.referenceId());
        parameters.put("workflowType", reference.type().name());
        parameters.put("requiresUserConfirmation", mutation);
        parameters.put("approvalRequired", reference.approvalRequired());

        IntentExecuteResponse.SuggestedAction action =
                IntentExecuteResponse.SuggestedAction.builder()
                        .actionCode("OPEN_FACTORY_WORKFLOW")
                        .actionName(mutation ? "打开确认入口" : "打开固定入口")
                        .description(message)
                        .parameters(Map.copyOf(parameters))
                        .build();

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("routeType", "FACTORY_CAPABILITY_PACK");
        metadata.put("policyResult", "WORKFLOW_GUIDANCE");
        metadata.put("packId", route.pack().packId());
        metadata.put("packVersion", route.pack().version());
        metadata.put("workflowReference", reference.referenceId());
        metadata.put("workflowType", reference.type().name());
        metadata.put("mutation", mutation);
        metadata.put("approvalRequired", reference.approvalRequired());

        return IntentExecuteResponse.builder()
                .intentRecognized(true)
                .intentCode(intent.getIntentCode())
                .intentName(intent.getIntentName())
                .intentCategory(intent.getIntentCategory())
                .sensitivityLevel(intent.getSensitivityLevel())
                .status("FACTORY_PACK_WORKFLOW_GUIDANCE")
                .message(message)
                .formattedText(message)
                .requiresApproval(reference.approvalRequired())
                .suggestedActions(List.of(action))
                .metadata(Map.copyOf(metadata))
                .executedAt(LocalDateTime.now())
                .build();
    }

    private void streamTerminalResponse(
            SseEmitter emitter, IntentExecuteResponse response, long startTime) throws IOException {
        sendSseEvent(emitter, "result", response);
        sendSseEvent(emitter, "complete", Map.of(
                "status", response.getStatus() != null ? response.getStatus() : "COMPLETED",
                "cacheHit", false,
                "totalLatencyMs", System.currentTimeMillis() - startTime));
        emitter.complete();
    }

    void sendSseEvent(SseEmitter emitter, String eventName, Object data) throws IOException {
        try {
            String json = objectMapper.writeValueAsString(data);
            emitter.send(SseEmitter.event().name(eventName).data(json));
        } catch (JsonProcessingException e) {
            log.warn("序列化 SSE 事件数据失败: {}", e.getMessage());
            throw new IOException("Failed to serialize SSE event data", e);
        }
    }

    private IntentExecuteResponse deserializeResponse(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            return objectMapper.readValue(json, IntentExecuteResponse.class);
        } catch (JsonProcessingException e) {
            log.warn("反序列化执行结果失败: {}", e.getMessage());
            return null;
        }
    }

    private IntentMatchResult deserializeIntentResult(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            return objectMapper.readValue(json, IntentMatchResult.class);
        } catch (JsonProcessingException e) {
            log.warn("反序列化意图结果失败: {}", e.getMessage());
            return null;
        }
    }

    private IntentExecuteResponse buildValidationFailureResponse(LlmSchemaValidationException e) {
        return IntentExecuteResponse.builder()
                .intentRecognized(false)
                .status("VALIDATION_FAILED")
                .message("AI 无法准确理解您的意图，请重新描述或从常用操作中选择。")
                .executedAt(LocalDateTime.now())
                .build();
    }

    /**
     * Select the streamed no-match message. Priority: conversationMessage → clarificationQuestion → generic.
     * W1b: a negation VETO_READ (e.g. "不用查库存了"/"别给我看订单") produces a REJECTED result
     * (bestMatch=null → hasMatch()=false reaches the no-match branch) that carries a clarificationQuestion.
     * Surface it so the streaming chat reply is the negation clarification, NOT the generic "我没有理解您的意图".
     */
    static String selectNoMatchStreamMessage(IntentMatchResult matchResult) {
        if (matchResult.getConversationMessage() != null && !matchResult.getConversationMessage().isEmpty()) {
            return matchResult.getConversationMessage();
        }
        if (matchResult.getClarificationQuestion() != null && !matchResult.getClarificationQuestion().isEmpty()) {
            return matchResult.getClarificationQuestion();
        }
        return "我没有理解您的意图，请更详细地描述您的需求。";
    }

    /**
     * Returns the SSE complete-event status string for a no-match result.
     * W1b: when the no-match carries a clarificationQuestion (negation VETO_READ), the complete
     * status must be NEED_CLARIFICATION to match the result event — not the generic NO_MATCH.
     * Mirrors the selectNoMatchStreamMessage priority: conversationMessage → clarificationQuestion → generic.
     */
    static String noMatchCompleteStatus(IntentMatchResult matchResult) {
        boolean isNegationClarification = matchResult.getClarificationQuestion() != null
                && !matchResult.getClarificationQuestion().isEmpty();
        return isNegationClarification ? "NEED_CLARIFICATION" : "NO_MATCH";
    }

    private IntentExecuteResponse buildNoMatchResponseForStream(IntentMatchResult matchResult, String factoryId) {
        String message = selectNoMatchStreamMessage(matchResult);
        return IntentExecuteResponse.builder()
                .intentRecognized(false)
                .status("NEED_CLARIFICATION")
                .message(message)
                .formattedText(message)
                .executedAt(LocalDateTime.now())
                .build();
    }

    private IntentExecuteResponse buildClarificationResponseForStream(IntentMatchResult matchResult, String factoryId) {
        AIIntentConfig matchedIntent = matchResult.getBestMatch();
        String clarificationMessage = matchResult.getClarificationQuestion();
        if (clarificationMessage == null || clarificationMessage.isEmpty()) {
            clarificationMessage = "您的请求可能匹配多个操作，请确认您想要执行的操作。";
        }
        return IntentExecuteResponse.builder()
                .intentRecognized(true)
                .intentCode(matchedIntent.getIntentCode())
                .intentName(matchedIntent.getIntentName())
                .intentCategory(matchedIntent.getIntentCategory())
                .status("NEED_CLARIFICATION")
                .message(clarificationMessage)
                .formattedText(clarificationMessage)
                .confidence(matchResult.getConfidence())
                .executedAt(LocalDateTime.now())
                .build();
    }

    /** R16: SSE no-tool 出口的餐饮 tiered 兜底 — 未命中回落原死胡同提示。 */
    private IntentExecuteResponse noToolResponseWithRestaurantFallback(
            AIIntentConfig intent, String factoryId, IntentExecuteRequest request) {
        IntentExecuteResponse delegated = tryRestaurantTieredDelegate(
                factoryId, request != null ? request.getUserInput() : null, request, "sse_no_tool");
        if (delegated != null) {
            log.info("[SSE][Branch:TieredDelegate] no-tool 出口被 tiered 路由接管: intentCode={}",
                    intent != null ? intent.getIntentCode() : null);
            return delegated;
        }
        return toolDispatchService.buildNoToolResponse(intent);
    }

    /**
     * Card4 (2026-07-28): shared restaurant tiered-delegate call, used by both the new
     * top-of-stream tiered-first gate ("sse_tiered_first") and the pre-existing R16 no-tool
     * dead-end fallback ("sse_no_tool"). Returns null on no-match, unavailable delegate or
     * delegate error — callers decide what "no answer" means for their branch.
     */
    private IntentExecuteResponse tryRestaurantTieredDelegate(
            String factoryId, String userInput, IntentExecuteRequest request, String origin) {
        if (sseTieredIntentDelegate == null || !isRestaurantTenant(factoryId) || userInput == null) {
            return null;
        }
        try {
            Map<String, Object> delegateParams = new java.util.HashMap<>();
            delegateParams.put("userInput", userInput);
            Map<String, Object> delegateContext = new java.util.HashMap<>();
            delegateContext.put("request", request);
            Map<String, Object> delegated = sseTieredIntentDelegate.tryDelegate(
                    factoryId, delegateParams, delegateContext, origin);
            if (delegated == null || delegated.get("message") == null) {
                return null;
            }
            String delegatedMessage = delegated.get("message").toString();
            Map<String, Object> delegatedData = new java.util.HashMap<>();
            delegatedData.put("charts", delegated.getOrDefault("charts", java.util.List.of()));
            delegatedData.put("kpis", delegated.getOrDefault("kpis", java.util.List.of()));
            delegatedData.put("source", "restaurant_ops_gold");
            return IntentExecuteResponse.builder()
                    .intentRecognized(true)
                    .intentCode(delegated.get("code") != null ? delegated.get("code").toString() : null)
                    .status("SUCCESS")
                    .message(delegatedMessage)
                    .formattedText(delegatedMessage)
                    .resultData(delegatedData)
                    .executedAt(java.time.LocalDateTime.now())
                    .build();
        } catch (Exception e) {
            log.warn("[SSE][Branch:TieredDelegate] delegate 失败 (origin={}): {}", origin, e.getMessage());
            return null;
        }
    }

    /**
     * Card4 (2026-07-28): fail-closed response for the tiered-first gate, mirrors
     * IntentExecutionOrchestrator's buildRestaurantDeterministicResponse(request,
     * "NEED_CLARIFICATION", true, ...) call at execute() :395-399.
     */
    private IntentExecuteResponse buildRestaurantTieredFirstFailClosedResponse(IntentExecuteRequest request) {
        String message = "餐饮语义规划暂时不可用，本次没有执行任何分析。请稍后重试。";
        return IntentExecuteResponse.builder()
                .intentRecognized(true)
                .status("NEED_CLARIFICATION")
                .message(message)
                .formattedText(message)
                .sessionId(request != null ? request.getSessionId() : null)
                .executedAt(LocalDateTime.now())
                .build();
    }

    /**
     * Card4 (2026-07-28): SSE-local restaurant-tenant ID pattern check, mirrors the
     * ID-only fallback branch of IntentExecutionOrchestrator#isRestaurantOwnerActionFactory
     * (:3451-3453) — DEMO_REST exact match, or a RES_/REST_ id prefix. This is the same
     * duplication R16's noToolResponseWithRestaurantFallback already accepted (no factory-domain
     * DB lookup is wired into SseStreamingService); this change only adds the missing REST_
     * prefix so the two SSE call sites (this and the no-tool fallback) now agree.
     *
     * <p><b>NOT equivalent</b> to orchestrator's {@code isRestaurantTenant()} (:2113), which
     * additionally resolves the factory's actual domain via {@code resolveFactoryDomainSafe()}
     * (a DB-backed lookup) and treats {@code "RESTAURANT".equalsIgnoreCase(factoryDomain)} as a
     * standalone true — a factory whose id doesn't match RES_/REST_/DEMO_REST but whose domain
     * IS resolved as RESTAURANT would gate tiered-first in /execute but NOT here. Accepted as
     * pre-existing scope (same gap R16 shipped with, card4 review 2026-07-28), not a card4
     * regression — but do not assume the two checks always agree.
     */
    private static boolean isRestaurantTenant(String factoryId) {
        if (factoryId == null || factoryId.isBlank()) {
            return false;
        }
        String normalized = factoryId.trim().toUpperCase(java.util.Locale.ROOT);
        return "DEMO_REST".equals(normalized)
                || normalized.startsWith("RES_")
                || normalized.startsWith("REST_");
    }
}

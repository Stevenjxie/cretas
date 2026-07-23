package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.dto.ChatCompletionResponse;
import com.cretas.aims.ai.dto.Tool;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRbacEnforcer;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.gateway.AuthenticatedToolPrincipalFactory;
import com.cretas.aims.ai.tool.gateway.ExecutionPrincipal;
import com.cretas.aims.ai.tool.gateway.LegacyToolMigrationRegistry;
import com.cretas.aims.ai.tool.gateway.ToolExecutionCommand;
import com.cretas.aims.ai.tool.gateway.ToolExecutionGateway;
import com.cretas.aims.ai.tool.gateway.ToolExecutionMode;
import com.cretas.aims.ai.tool.gateway.ToolExecutionResult;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import com.cretas.aims.config.TimeNormalizationRules;
import com.cretas.aims.dev.faultinjection.ToolExecutionFaultInjector;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.ai.PreprocessedQuery;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.calibration.CorrectionRecord;
import com.cretas.aims.entity.calibration.ToolCallRecord;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.cretas.aims.service.PreviewTokenService;
import com.cretas.aims.service.calibration.CorrectionAgentService;
import com.cretas.aims.service.calibration.ExternalVerifierService;
import com.cretas.aims.service.calibration.SelfCorrectionService;
import com.cretas.aims.service.calibration.ToolCallRedundancyService;
import com.cretas.aims.service.calibration.ToolResultValidatorService;
import com.cretas.aims.util.ErrorSanitizer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Tool 调度服务
 *
 * 负责直接 Tool 执行、Tool 预览、参数提取（LLM + 规则学习）、
 * 冗余检查、重试 + CRITIC 纠错循环。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolDispatchService {

    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final DashScopeClient dashScopeClient;
    private final ToolCallRedundancyService redundancyService;
    private final SelfCorrectionService selfCorrectionService;
    private final CorrectionAgentService correctionAgentService;
    private final ExternalVerifierService externalVerifierService;
    private final ToolResultValidatorService toolResultValidatorService;
    private final ParameterExtractionLearningService parameterExtractionLearningService;

    @Autowired(required = false)
    private PreviewTokenService previewTokenService;

    // W0 write-guard (intent-w0) — SITE B. This is the direct-Tool execution choke point reached
    // by the main execute() ToolDirect branch AND by executeWithExplicitIntent. The guard blocks a
    // write tool (polymorphic getActionType() WRITE/UPDATE/DELETE) unless previewOnly or confirmed.
    // It is NOT conditioned on forceExecute (defense-in-depth even though Site A also guards the
    // explicit path) — a misroute to a write tool cannot silently execute here.
    @Autowired
    private com.cretas.aims.ai.tool.WriteGuardService writeGuardService;

    // W9 红线 (AI-RBAC 系统性收口) — SITE B: central RBAC enforce for sensitive write tools whose
    // controllers carry @RequirePermission. The legacy tool.requiresPermission()/hasPermission() check
    // below only covers the ~47 tools that override those (default false → vast majority skipped),
    // so customer_delete / order_delete / finance_invoice_approve / transfer_approve / user_* etc.
    // had ZERO AI-path RBAC. This enforcer uses the same PermissionService matrix as HTTP (fail-closed).
    @Autowired
    private com.cretas.aims.ai.tool.ToolRbacEnforcer toolRbacEnforcer;

    /**
     * F5 fault-injection hook (optional). Bean exists only under
     * {@code dev-fault-injection} Spring profile; remains {@code null} in prod.
     * When active, {@link #executeWithTool} throws RuntimeException for tool
     * names listed in {@code MOCK_TOOL_THROW} env var (comma-separated).
     */
    @Autowired(required = false)
    private ToolExecutionFaultInjector toolExecutionFaultInjector;

    @Autowired
    private ToolExecutionGateway toolExecutionGateway;

    @Autowired
    private LegacyToolMigrationRegistry legacyToolMigrationRegistry;

    @Autowired
    private AuthenticatedToolPrincipalFactory authenticatedToolPrincipalFactory;

    @Value("${cretas.ai.tool-gateway.intent-dispatch-migration.enabled:false}")
    private boolean intentDispatchGatewayMigrationEnabled;

    // ==================== 公开方法 ====================

    /**
     * 使用 Tool 执行意图
     */
    public IntentExecuteResponse executeWithTool(ToolExecutor tool, String factoryId,
                                                  IntentExecuteRequest request,
                                                  AIIntentConfig intent,
                                                  Long userId, String userRole,
                                                  IntentMatchResult matchResult) {
        try {
            // Round 8-β Fix: Canvas factory_tool_configs was a "write-only" table.
            // Factory admins toggled tools on/off in Canvas UI (ToolSkillMatrix.vue) but
            // ToolRegistry.isToolEnabledForFactory() was never called on the execution
            // path — the switch was decorative. Now we check it here so a disabled tool
            // returns a clear error instead of silently running.
            if (toolRegistry != null && !toolRegistry.isToolEnabledForFactory(factoryId, tool.getToolName())) {
                log.warn("Tool disabled for factory: tool={}, factoryId={}", tool.getToolName(), factoryId);
                // Sprint 12: 4-element error UX (≥80-char). Covers both genuine admin-disable
                // and cross-factory cases (e.g. F999 not configured → tool not enabled).
                String disabledMsg = String.format(
                        "「%s」功能当前在工厂 %s 不可用。可能原因: 1. 该功能已被工厂管理员在功能配置中关闭;"
                        + " 2. 当前工厂 (%s) 未开通此模块或工厂编号不存在; 3. 您的角色暂无此功能权限。"
                        + "建议: 联系工厂管理员在「功能配置」中开启, 或确认已切换到正确的工厂。",
                        intent.getIntentName(), factoryId, factoryId);
                return IntentExecuteResponse.builder()
                        .intentRecognized(true)
                        .intentCode(intent.getIntentCode())
                        .intentName(intent.getIntentName())
                        .intentCategory(intent.getIntentCategory())
                        .status("TOOL_DISABLED")
                        .message(disabledMsg)
                        .formattedText(disabledMsg)
                        .executedAt(LocalDateTime.now())
                        .build();
            }

            // previewOnly is a hard no-write contract. If the tool has no explicit safe preview,
            // fail closed here instead of falling through to the normal execute() path.
            if (Boolean.TRUE.equals(request.getPreviewOnly()) && !tool.supportsPreview()) {
                log.warn("Tool preview unsupported; execution blocked: tool={}, intentCode={}",
                        tool.getToolName(), intent != null ? intent.getIntentCode() : null);
                return IntentExecuteResponse.builder()
                        .intentRecognized(true)
                        .intentCode(intent != null ? intent.getIntentCode() : null)
                        .intentName(intent != null ? intent.getIntentName() : null)
                        .intentCategory(intent != null ? intent.getIntentCategory() : null)
                        .status("PREVIEW_UNSUPPORTED")
                        .message("该工具不支持安全预览，未执行任何操作。")
                        .formattedText("该工具不支持安全预览，未执行任何操作。")
                        .executedAt(LocalDateTime.now())
                        .build();
            }

            // P1 读写分块 §4.4 (SITE B 兜底): 咨询 tab (mode=READ) 路由到写工具/写意图 → 不执行,
            // 返回跳转提示卡。confirm() 二阶段构造的内部请求不带 mode, 不受影响。
            if ("READ".equalsIgnoreCase(request.getMode())
                    && (writeGuardService.isWriteTool(tool)
                        || (intent != null && writeGuardService.isWriteIntent(intent)))) {
                log.info("READ 模式拦截写工具 (tool-dispatch): tool={}", tool.getToolName());
                String readBlockedMsg = "这是操作类请求，请切换到【操作】页处理。";
                return IntentExecuteResponse.builder()
                        .intentRecognized(true)
                        .intentCode(intent != null ? intent.getIntentCode() : null)
                        .intentName(intent != null ? intent.getIntentName() : null)
                        .intentCategory(intent != null ? intent.getIntentCategory() : null)
                        .status("READ_MODE_WRITE_BLOCKED")
                        .message(readBlockedMsg)
                        .formattedText(readBlockedMsg)
                        .aiMode("WRITE")
                        .executedAt(LocalDateTime.now())
                        .build();
            }

            // W0 write-guard (intent-w0) — SITE B: block a write tool unless safely previewed or confirmed.
            // Runs BEFORE the role-permission check so a misroute to a destructive operation cannot
            // silently execute. NOT conditioned on forceExecute (the multi-intent bypass flag).
            // The bound AIIntentConfig is available here (Site B only) — add a curated-sensitivity
            // backstop so HIGH/CRITICAL write intents (e.g. PROCESSING_WORKER_ASSIGN) are blocked even
            // if the tool-NAME heuristic misses the verb. Sites C/D/E/F lack the bound intent and rely
            // on the expanded WRITE_SUFFIXES list.
            Map<String, Object> wgCtx = TrustedExecutionContext.merge(
                    request.getContext(), factoryId, userId, userRole);
            if ((writeGuardService.isWriteTool(tool)
                    || (intent != null && writeGuardService.isWriteIntent(intent)))
                    && !Boolean.TRUE.equals(request.getPreviewOnly())
                    && !writeGuardService.isConfirmed(wgCtx)) {
                log.info("W0 write-guard (tool-dispatch): blocked write tool {} (confirmed=false)", tool.getToolName());
                return IntentExecuteResponse.builder()
                        .intentRecognized(true)
                        .intentCode(intent != null ? intent.getIntentCode() : null)
                        .status("WRITE_CONFIRM_REQUIRED")
                        .message("该操作会写入/修改数据，执行前需要确认。")
                        .requiresApproval(true)
                        // P1 读写分块: 确认卡属写路径, 显式标记 aiMode
                        .aiMode("WRITE")
                        .executedAt(java.time.LocalDateTime.now())
                        .build();
            }

            // 1. 权限检查 (legacy 角色串判定)。
            // 2026-07-24 修: 声明了 getRequiredPermissions() 的工具跳过本检查, 交给
            // 下方 W9 enforcer 按权限矩阵判定 — 此前 8 个工具的 hasPermission()
            // 无条件 false (本意是"逼调用方走权限码"), 但本检查先于 enforcer 执行,
            // 导致 3 个原料只读查询对所有角色全拒 (超管也拒), 4 个写工具的
            // confirm 终点被拦死。仅保留给只有 legacy 角色逻辑的旧工具。
            if (tool.requiresPermission()
                    && tool.getRequiredPermissions().isEmpty()
                    && !tool.hasPermission(userRole)) {
                log.warn("Tool 权限不足: tool={}, userRole={}", tool.getToolName(), userRole);
                // Sprint 12: 4-element error UX (≥80-char) for permission-denied case.
                String permDeniedMsg = String.format(
                        "您当前的角色 (%s) 没有权限执行「%s」操作。可能原因: 1. 此操作需要更高的角色权限;"
                        + " 2. 您所在的部门未被授权该功能; 3. 该操作涉及敏感数据需额外审批。"
                        + "建议: 联系工厂管理员申请对应权限, 或改用您有权限的查询功能。",
                        userRole != null ? userRole : "未知", intent.getIntentName());
                return IntentExecuteResponse.builder()
                        .intentRecognized(true)
                        .intentCode(intent.getIntentCode())
                        .intentName(intent.getIntentName())
                        .intentCategory(intent.getIntentCategory())
                        .status("PERMISSION_DENIED")
                        .message(permDeniedMsg)
                        .formattedText(permDeniedMsg)
                        .executedAt(LocalDateTime.now())
                        .build();
            }

            // 1.4. W9 红线 (AI-RBAC) — SITE B: central enforce. Covers sensitive write tools that do NOT
            // override requiresPermission() (the legacy check above misses them). Uses the same
            // PermissionService matrix as controller @RequirePermission (fail-closed). previewOnly is
            // allowed (it previews, not executes).
            if (!Boolean.TRUE.equals(request.getPreviewOnly())) {
                ToolRbacEnforcer.Decision rbac = toolRbacEnforcer.check(tool, wgCtx);
                if (!rbac.isAllowed()) {
                    log.warn("W9 AI-RBAC (tool-dispatch): denied tool={}, requiredAny={}",
                            tool.getToolName(), rbac.getRequiredPermissions());
                    return IntentExecuteResponse.builder()
                            .intentRecognized(true)
                            .intentCode(intent.getIntentCode())
                            .intentName(intent.getIntentName())
                            .intentCategory(intent.getIntentCategory())
                            .status("PERMISSION_DENIED")
                            .message(rbac.getMessage())
                            .formattedText(rbac.getMessage())
                            .executedAt(LocalDateTime.now())
                            .build();
                }
            }

            // The process-local confirmation marker exists only long enough for W0 and RBAC to
            // inspect it. Never serialize or forward it to a Tool; keep the separate legacy
            // business parameter confirmed=true for tools that still require that second check.
            request.setContext(com.cretas.aims.ai.tool.WriteGuardService
                    .withoutServerConfirmationMarker(request.getContext()));

            // 1.5. 预览模式（不支持预览的请求已在上方 fail closed）
            if (Boolean.TRUE.equals(request.getPreviewOnly())) {
                log.info("Tool preview 模式: tool={}, intentCode={}", tool.getToolName(), intent.getIntentCode());
                return executeToolPreview(tool, factoryId, request, intent, userId, userRole);
            }

            // 2. 构建 ToolCall
            Map<String, Object> params = TrustedExecutionContext.merge(
                    request.getContext(), factoryId, userId, userRole);

            String userInputToUse = request.getUserInput();
            if (matchResult != null && matchResult.getPreprocessedQuery() != null) {
                PreprocessedQuery pq = matchResult.getPreprocessedQuery();
                if (pq.getFinalQuery() != null && !pq.getFinalQuery().isEmpty()) {
                    userInputToUse = pq.getFinalQuery();
                    log.info("使用预处理后的查询: '{}' -> '{}'", request.getUserInput(), userInputToUse);
                }
            }
            params.put("userInput", userInputToUse);
            params.put("intentCode", intent.getIntentCode());

            // 2.5. 从预处理结果中提取解析的引用
            if (matchResult != null && matchResult.getPreprocessedQuery() != null) {
                PreprocessedQuery pq = matchResult.getPreprocessedQuery();
                Map<String, PreprocessedQuery.ResolvedReference> refs = pq.getResolvedReferences();
                if (refs != null && !refs.isEmpty()) {
                    for (Map.Entry<String, PreprocessedQuery.ResolvedReference> entry : refs.entrySet()) {
                        PreprocessedQuery.ResolvedReference ref = entry.getValue();
                        if (ref != null && ref.getEntityType() != null) {
                            switch (ref.getEntityType().toUpperCase()) {
                                case "BATCH":
                                    params.put("batchId", ref.getEntityId());
                                    if (ref.getEntityName() != null) {
                                        params.put("batchNumber", ref.getEntityName());
                                    }
                                    log.info("从上下文解析批次: id={}, number={}", ref.getEntityId(), ref.getEntityName());
                                    break;
                                case "SUPPLIER":
                                    params.put("supplierId", ref.getEntityId());
                                    log.info("从上下文解析供应商: {}", ref.getEntityId());
                                    break;
                                case "PRODUCT":
                                    params.put("productId", ref.getEntityId());
                                    log.info("从上下文解析产品: {}", ref.getEntityId());
                                    break;
                                case "STORE":
                                    params.put("store_name", ref.getEntityName());
                                    if (ref.getEntityId() != null) {
                                        params.put("store_id", ref.getEntityId());
                                    }
                                    log.info("从上下文解析门店: id={}, name={}", ref.getEntityId(), ref.getEntityName());
                                    break;
                                case "DISH":
                                    params.put("dish_name", ref.getEntityName());
                                    if (ref.getEntityId() != null) {
                                        params.put("dish_id", ref.getEntityId());
                                    }
                                    log.info("从上下文解析菜品: id={}, name={}", ref.getEntityId(), ref.getEntityName());
                                    break;
                            }
                        }
                    }
                }

                // 2.6. 从预处理结果中提取时间范围
                if (pq.hasTimeRange()) {
                    TimeNormalizationRules.TimeRange timeRange = pq.getPrimaryTimeRange();
                    if (timeRange != null && timeRange.isValid()) {
                        params.put("startDate", timeRange.getStart().toLocalDate().toString());
                        params.put("endDate", timeRange.getEnd().toLocalDate().toString());
                        log.info("从预处理结果提取时间范围: {} ~ {}", timeRange.getStart(), timeRange.getEnd());
                    }
                }
            }

            // 2.7. 优先使用已学习的规则提取参数
            Map<String, Object> parametersSchema = tool.getParametersSchema();
            @SuppressWarnings("unchecked")
            List<String> requiredParams = parametersSchema != null ?
                    (List<String>) parametersSchema.get("required") : null;

            Map<String, Object> ruleExtractedParams = new HashMap<>();
            if (requiredParams != null && !requiredParams.isEmpty()) {
                List<String> missingParams = requiredParams.stream()
                        .filter(p -> !params.containsKey(p) ||
                                     params.get(p) == null ||
                                     (params.get(p) instanceof String && ((String) params.get(p)).trim().isEmpty()))
                        .collect(Collectors.toList());

                if (!missingParams.isEmpty()) {
                    ruleExtractedParams = parameterExtractionLearningService.extractWithLearnedRules(
                            factoryId, intent.getIntentCode(), userInputToUse, missingParams);

                    if (!ruleExtractedParams.isEmpty()) {
                        params.putAll(ruleExtractedParams);
                        log.info("使用学习规则提取参数: {} (无需调用 LLM)", ruleExtractedParams.keySet());
                    }
                }
            }

            // 2.8. LLM 提取剩余参数
            Map<String, Object> llmExtractedParams = extractParametersWithLLM(userInputToUse, tool, params);
            if (!llmExtractedParams.isEmpty()) {
                params.putAll(llmExtractedParams);
                log.info("合并 LLM 提取的参数: {}", llmExtractedParams.keySet());

                // 2.9. 从 LLM 提取结果中学习规则（异步）
                try {
                    parameterExtractionLearningService.learnFromLLMExtraction(
                            factoryId, intent.getIntentCode(), userInputToUse, llmExtractedParams);
                } catch (Exception e) {
                    log.warn("参数提取规则学习失败: {}", e.getMessage());
                }
            }

            // Learned rules, LLM extraction and resolved references are all untrusted inputs.
            // Re-bind the authenticated principal immediately before hashing and Tool execution.
            TrustedExecutionContext.enforcePrincipal(params, factoryId, userId, userRole);

            // D11B migration lane: once the default-off flag and the independent allowlist both
            // select a Tool, Gateway owns the attempt. Do not consult the legacy result cache,
            // retry/correction loop, or direct executor afterwards, including on deny/timeout/fail.
            if (intentDispatchGatewayMigrationEnabled
                    && legacyToolMigrationRegistry.contains(tool.getToolName())) {
                return executeThroughMigrationGateway(
                        tool, factoryId, params, intent, userId, userRole);
            }

            String argumentsJson = objectMapper.writeValueAsString(params);
            ToolCall toolCall = ToolCall.of(
                    java.util.UUID.randomUUID().toString(),
                    tool.getToolName(),
                    argumentsJson
            );

            // 3. 构建执行上下文
            Map<String, Object> context = TrustedExecutionContext.merge(
                    null, factoryId, userId, userRole);
            context.put("intentConfig", intent);
            context.put("request", request);

            // 4. 冗余检查 (ET-Agent 行为校准)
            String sessionId = request.getSessionId() != null ? request.getSessionId() : "default";
            if (redundancyService.isRedundant(sessionId, tool.getToolName(), params)) {
                log.info("检测到冗余调用，跳过执行: tool={}, session={}", tool.getToolName(), sessionId);
                Optional<String> cachedResult = redundancyService.getCachedResult(sessionId, tool.getToolName(), params);
                if (cachedResult.isPresent()) {
                    // Sprint 12: parse cached JSON to extract message + data, don't leak raw JSON as message text.
                    IntentExecuteResponse parsed = parseToolResultToResponse(cachedResult.get(), intent);
                    String parsedMsg = parsed.getMessage() != null ? parsed.getMessage() : "操作已完成。";
                    return IntentExecuteResponse.builder()
                            .intentRecognized(true)
                            .intentCode(intent.getIntentCode())
                            .intentName(intent.getIntentName())
                            .intentCategory(intent.getIntentCategory())
                            .status("SUCCESS")
                            .message(parsedMsg)
                            .resultData(parsed.getResultData())
                            .formattedText(parsed.getFormattedText())
                            .metadata(Map.of("cached", true))
                            .executedAt(LocalDateTime.now())
                            .build();
                }
            }

            // 5. 执行 Tool（带自动重试）
            final int MAX_RETRIES = 3;
            String resultJson = null;
            Exception lastException = null;
            int retryCount = 0;
            long totalExecutionTime = 0;

            for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                try {
                    log.debug("执行 Tool (尝试 {}/{}): name={}, arguments={}",
                            attempt, MAX_RETRIES, tool.getToolName(), argumentsJson);

                    // F5 fault-injection (dev-fault-injection profile only):
                    // throw RuntimeException for tools in MOCK_TOOL_THROW list.
                    // Throws inside the try-block so it follows existing retry +
                    // CRITIC correction loop, mirroring a real Tool failure.
                    if (toolExecutionFaultInjector != null) {
                        toolExecutionFaultInjector.maybeThrow(tool.getToolName());
                    }

                    long startTime = System.currentTimeMillis();
                    resultJson = tool.execute(toolCall, context);
                    long executionTime = System.currentTimeMillis() - startTime;
                    totalExecutionTime += executionTime;

                    // 执行成功，记录
                    try {
                        ToolCallRecord record = ToolCallRecord.builder()
                                .sessionId(sessionId)
                                .factoryId(factoryId)
                                .toolName(tool.getToolName())
                                .toolParameters(argumentsJson)
                                .parametersHash(redundancyService.computeParametersHash(params))
                                .executionStatus(ToolCallRecord.ExecutionStatus.SUCCESS)
                                .executionTimeMs((int) executionTime)
                                .retryCount(attempt - 1)
                                .recovered(attempt > 1)
                                .build();
                        ToolCallRecord savedRecord = redundancyService.recordToolCall(record);
                        if (savedRecord != null && resultJson != null) {
                            // cacheResult stores into tool_call_cache.cached_result (JSONB).
                            // Don't substring-truncate — that splits tokens and produces
                            // invalid JSON like `{..."previous` which PG rejects at INSERT.
                            // Cap at 16KB via a structured wrapper so the JSON stays valid.
                            String cacheValue;
                            if (resultJson.length() > 16384) {
                                cacheValue = "{\"_truncated\":true,\"_originalSize\":" + resultJson.length()
                                    + ",\"preview\":" + objectMapper.writeValueAsString(
                                        resultJson.substring(0, 16000) + "...(truncated)") + "}";
                            } else {
                                cacheValue = resultJson;
                            }
                            redundancyService.cacheResult(sessionId, tool.getToolName(), params, cacheValue, savedRecord.getId());
                        }
                        if (attempt > 1) {
                            log.info("工具调用在第 {} 次尝试后恢复成功: tool={}", attempt, tool.getToolName());
                        }
                    } catch (Exception recordEx) {
                        log.warn("记录工具调用失败: {}", recordEx.getMessage());
                    }

                    // D7: 快速规则验证
                    if (resultJson != null && attempt < MAX_RETRIES) {
                        if (isResultClearlyValid(resultJson)) {
                            log.info("[D7-FastValidation] Result clearly valid ({}B), skipping LLM validation for tool={}",
                                    resultJson.length(), tool.getToolName());
                            break;
                        }

                        try {
                            ToolResultValidatorService.ValidationResult validationResult =
                                    toolResultValidatorService.validate(
                                            request.getUserInput(),
                                            tool.getToolName(),
                                            params,
                                            resultJson
                                    );

                            if (!validationResult.isValid()) {
                                log.info("结果验证失败: issue={}, description={}, matchScore={}",
                                        validationResult.issue(),
                                        validationResult.issueDescription(),
                                        validationResult.matchScore());

                                String pseudoError = String.format("[%s] %s",
                                        validationResult.issue(), validationResult.issueDescription());

                                ExternalVerifierService.VerificationResult externalVerification = null;
                                try {
                                    externalVerification = externalVerifierService.verifyToolCall(
                                            factoryId, tool.getToolName(), params, pseudoError);
                                } catch (Exception verifyEx) {
                                    log.warn("外部验证失败: {}", verifyEx.getMessage());
                                }

                                CorrectionAgentService.CorrectionResult correctionResult =
                                        correctionAgentService.analyzeAndCorrect(
                                                request.getUserInput(),
                                                tool.getToolName(),
                                                params,
                                                pseudoError,
                                                externalVerification,
                                                attempt
                                        );

                                log.info("纠错 Agent 结果（结果验证触发）: shouldRetry={}, strategy={}, confidence={}",
                                        correctionResult.shouldRetry(),
                                        correctionResult.correctionStrategy(),
                                        correctionResult.confidence());

                                if (correctionResult.shouldRetry() && correctionResult.correctedParams() != null) {
                                    params.clear();
                                    params.putAll(correctionResult.correctedParams());
                                    params.put("_correctionStrategy", correctionResult.correctionStrategy());
                                    params.put("_retryAttempt", attempt);
                                    params.put("_validationIssue", validationResult.issue().name());

                                    argumentsJson = objectMapper.writeValueAsString(params);
                                    toolCall = ToolCall.of(
                                            java.util.UUID.randomUUID().toString(),
                                            tool.getToolName(),
                                            argumentsJson
                                    );

                                    log.info("结果验证纠错: 准备第 {} 次重试, strategy={}, hint={}",
                                            attempt + 1,
                                            correctionResult.correctionStrategy(),
                                            validationResult.correctionHint());

                                    resultJson = null;
                                    continue;
                                }
                            }
                        } catch (Exception validationEx) {
                            log.warn("结果验证过程出错: {}", validationEx.getMessage());
                        }
                    }

                    break;

                } catch (Exception e) {
                    lastException = e;
                    retryCount = attempt;

                    String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    log.warn("Tool 执行失败 (尝试 {}/{}): tool={}, error={}",
                            attempt, MAX_RETRIES, tool.getToolName(), errorMsg);

                    ExternalVerifierService.VerificationResult verificationResult = null;
                    try {
                        verificationResult = externalVerifierService.verifyToolCall(
                                factoryId, tool.getToolName(), params, errorMsg);
                        log.info("外部验证结果: hasData={}, status={}, suggestion={}",
                                verificationResult.hasData(), verificationResult.dataStatus(), verificationResult.suggestion());
                    } catch (Exception verifyEx) {
                        log.warn("外部验证失败: {}", verifyEx.getMessage());
                    }

                    CorrectionAgentService.CorrectionResult correctionResult = null;
                    try {
                        correctionResult = correctionAgentService.analyzeAndCorrect(
                                request.getUserInput(),
                                tool.getToolName(),
                                params,
                                errorMsg,
                                verificationResult,
                                attempt
                        );
                        log.info("纠错 Agent 结果: shouldRetry={}, strategy={}, confidence={}",
                                correctionResult.shouldRetry(), correctionResult.correctionStrategy(), correctionResult.confidence());
                    } catch (Exception agentEx) {
                        log.warn("纠错 Agent 调用失败: {}", agentEx.getMessage());
                    }

                    boolean shouldRetry = correctionResult != null && correctionResult.shouldRetry() && attempt < MAX_RETRIES;

                    if (shouldRetry && correctionResult.correctedParams() != null) {
                        params.clear();
                        params.putAll(correctionResult.correctedParams());
                        params.put("_correctionStrategy", correctionResult.correctionStrategy());
                        params.put("_retryAttempt", attempt);
                        params.put("_confidence", correctionResult.confidence());

                        try {
                            argumentsJson = objectMapper.writeValueAsString(params);
                            toolCall = ToolCall.of(
                                    java.util.UUID.randomUUID().toString(),
                                    tool.getToolName(),
                                    argumentsJson
                            );
                        } catch (JsonProcessingException je) {
                            log.error("重试时参数序列化失败: {}", je.getMessage());
                            break;
                        }

                        log.info("CRITIC 纠错: 准备第 {} 次重试, strategy={}, confidence={}",
                                attempt + 1, correctionResult.correctionStrategy(), correctionResult.confidence());

                        try {
                            CorrectionRecord.ErrorCategory errorCategory = selfCorrectionService.classifyError(errorMsg, null);
                            selfCorrectionService.createCorrectionRecord(
                                    null, factoryId, sessionId,
                                    errorCategory.name(), errorMsg, correctionResult.errorAnalysis());
                        } catch (Exception recordEx) {
                            log.warn("记录纠错尝试失败: {}", recordEx.getMessage());
                        }
                    } else {
                        String reason = correctionResult != null ? correctionResult.errorAnalysis() : "纠错 Agent 不可用";
                        log.info("纠错 Agent 判断不重试: {}", reason);
                        break;
                    }
                }
            }

            // 所有重试都失败
            if (resultJson == null && lastException != null) {
                try {
                    ToolCallRecord failedRecord = ToolCallRecord.builder()
                            .sessionId(sessionId)
                            .factoryId(factoryId)
                            .toolName(tool.getToolName())
                            .toolParameters(argumentsJson)
                            .executionStatus(ToolCallRecord.ExecutionStatus.FAILED)
                            .errorMessage(lastException.getMessage())
                            .retryCount(retryCount)
                            .build();
                    redundancyService.recordToolCall(failedRecord);
                } catch (Exception recordEx) {
                    log.warn("记录失败调用时出错: {}", recordEx.getMessage());
                }

                String errorMessage = lastException.getMessage() != null ? lastException.getMessage() : lastException.getClass().getSimpleName();
                CorrectionRecord.ErrorCategory errorCategory = selfCorrectionService.classifyError(errorMessage, null);
                CorrectionRecord.CorrectionStrategy strategy = selfCorrectionService.determineStrategy(errorCategory);

                return IntentExecuteResponse.builder()
                        .intentRecognized(true)
                        .intentCode(intent.getIntentCode())
                        .intentName(intent.getIntentName())
                        .intentCategory(intent.getIntentCategory())
                        .status("FAILED")
                        .message("执行失败 (已重试 " + retryCount + " 次): " + ErrorSanitizer.sanitize(lastException))
                        .metadata(Map.of(
                                "errorCategory", errorCategory.name(),
                                "correctionStrategy", strategy.name(),
                                "retryCount", retryCount,
                                "autoRetryExhausted", true
                        ))
                        .executedAt(LocalDateTime.now())
                        .build();
            }

            // 6. 解析 Tool 结果
            IntentExecuteResponse response = parseToolResultToResponse(resultJson, intent);

            // 统一填充 metadata.toolName (P5.6) - 下游 SmartBI chat 适配器读取此字段
            if (response != null) {
                Map<String, Object> metadata = response.getMetadata() != null ?
                        new HashMap<>(response.getMetadata()) : new HashMap<>();
                metadata.put("toolName", tool.getToolName());
                if (retryCount > 0 && "SUCCESS".equals(response.getStatus())) {
                    metadata.put("recoveredAfterRetries", retryCount);
                    metadata.put("totalExecutionTimeMs", totalExecutionTime);
                }
                response.setMetadata(metadata);
            }

            return response;

        } catch (JsonProcessingException e) {
            log.error("Tool 参数序列化失败: tool={}, error={}", tool.getToolName(), e.getMessage());
            return IntentExecuteResponse.builder()
                    .intentRecognized(true)
                    .intentCode(intent.getIntentCode())
                    .intentName(intent.getIntentName())
                    .intentCategory(intent.getIntentCategory())
                    .status("FAILED")
                    .message("参数处理失败: " + e.getMessage())
                    .executedAt(LocalDateTime.now())
                    .build();
        } catch (Exception e) {
            log.error("Tool 执行失败: tool={}, error={}", tool.getToolName(), e.getMessage(), e);

            String sessionId = request.getSessionId() != null ? request.getSessionId() : "default";
            try {
                ToolCallRecord failedRecord = ToolCallRecord.builder()
                        .sessionId(sessionId)
                        .factoryId(factoryId)
                        .toolName(tool.getToolName())
                        .executionStatus(ToolCallRecord.ExecutionStatus.FAILED)
                        .errorMessage(e.getMessage())
                        .build();
                redundancyService.recordToolCall(failedRecord);
            } catch (Exception recordEx) {
                log.warn("记录失败调用时出错: {}", recordEx.getMessage());
            }

            String errorMessage = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            CorrectionRecord.ErrorCategory errorCategory = selfCorrectionService.classifyError(errorMessage, null);
            CorrectionRecord.CorrectionStrategy strategy = selfCorrectionService.determineStrategy(errorCategory);

            log.info("错误分类: category={}, strategy={}", errorCategory, strategy);

            String correctionPrompt = selfCorrectionService.generateCorrectionPrompt(errorCategory, errorMessage);

            return IntentExecuteResponse.builder()
                    .intentRecognized(true)
                    .intentCode(intent.getIntentCode())
                    .intentName(intent.getIntentName())
                    .intentCategory(intent.getIntentCategory())
                    .status("FAILED")
                    .message("执行失败: " + ErrorSanitizer.sanitize(e))
                    .metadata(Map.of(
                            "errorCategory", errorCategory.name(),
                            "correctionStrategy", strategy.name(),
                            "correctionHint", correctionPrompt.substring(0, Math.min(200, correctionPrompt.length()))
                    ))
                    .executedAt(LocalDateTime.now())
                    .build();
        }
    }

    /**
     * Tool 预览执行
     */
    public IntentExecuteResponse executeToolPreview(ToolExecutor tool, String factoryId,
                                                     IntentExecuteRequest request,
                                                     AIIntentConfig intent,
                                                     Long userId, String userRole) {
        try {
            Map<String, Object> params = TrustedExecutionContext.merge(
                    request.getContext(), factoryId, userId, userRole);
            params.put("userInput", request.getUserInput());
            params.put("intentCode", intent.getIntentCode());

            // F2 修复: preview 路径也要抽参. 否则纯自然语言走 preview 时, 必填参数 (如 productTypeId /
            // workProcessNames) 为 null → tool.preview 内 resolve 抛 422, "NL→配工序"第一步就跑不通.
            // 与主执行路径 (executeWithTool 的 LLM 抽参) 一致: 只填缺失的必填项, 不覆盖 context 已有值.
            try {
                Map<String, Object> llmExtractedParams =
                        extractParametersWithLLM(request.getUserInput(), tool, params);
                if (!llmExtractedParams.isEmpty()) {
                    params.putAll(llmExtractedParams);
                    log.info("Preview 路径 LLM 抽取参数: {}", llmExtractedParams.keySet());
                }
            } catch (Exception ex) {
                log.warn("Preview 路径参数抽取失败 (继续用已有 params): {}", ex.getMessage());
            }

            // Preview must expose the same principal-bound arguments as real execution.
            TrustedExecutionContext.enforcePrincipal(params, factoryId, userId, userRole);

            String argumentsJson = objectMapper.writeValueAsString(params);
            ToolCall toolCall = ToolCall.of(
                    java.util.UUID.randomUUID().toString(),
                    tool.getToolName(),
                    argumentsJson
            );

            Map<String, Object> context = TrustedExecutionContext.merge(
                    null, factoryId, userId, userRole);
            context.put("intentConfig", intent);

            String resultJson = tool.preview(toolCall, context);

            IntentExecuteResponse previewResponse = IntentExecuteResponse.builder()
                    .intentRecognized(true)
                    .intentCode(intent.getIntentCode())
                    .intentName(intent.getIntentName())
                    .intentCategory(intent.getIntentCategory())
                    .status("PREVIEW")
                    .message(resultJson)
                    .executedAt(LocalDateTime.now())
                    .build();

            // P1.5 (2026-07-23 读写分块 spec §5.2): 预览成功即铸绑定 token 并回
            // confirmableAction — 补齐通用工具 TCC 的最后一环 (此前 token 只在
            // 餐饮 agent 工作流铸, 通用预览无 token → confirm() 无从驱动,
            // 确认卡在前端无米下锅)。只对写工具铸 (读工具预览无确认语义)。
            if (writeGuardService.isWriteTool(tool) || writeGuardService.isWriteIntent(intent)) {
                try {
                    com.cretas.aims.entity.intent.IntentPreviewToken boundToken =
                            previewTokenService.createBoundToken(
                                    new PreviewTokenService.BoundTokenRequest(
                                            factoryId, userId, null,
                                            intent.getIntentCode(), intent.getIntentName(),
                                            tool.getToolName(), tool.getVersion(),
                                            ToolExecutionMode.EXECUTE,
                                            null, null, null,
                                            params, java.util.Map.of(), java.util.Map.of(),
                                            300));
                    Map<String, Object> previewData = null;
                    try {
                        previewData = objectMapper.readValue(resultJson, Map.class);
                    } catch (Exception ignore) {
                        // 预览体非 JSON 对象时 previewData 留空, 前端渲染 message 原文
                    }
                    previewResponse.setAiMode("WRITE");
                    previewResponse.setConfirmableAction(
                            IntentExecuteResponse.ConfirmableAction.builder()
                                    .confirmToken(boundToken.getToken())
                                    .commandDigest(boundToken.getCommandDigest())
                                    .expiresAt(boundToken.getExpiresAt()
                                            .atZone(java.time.ZoneId.systemDefault()).toInstant())
                                    .expiresInSeconds(300)
                                    .description("确认后将实际执行「" + intent.getIntentName() + "」")
                                    .previewData(previewData)
                                    .build());
                } catch (Exception e) {
                    // fail-safe: 铸 token 失败不破坏预览本身 (无 confirmableAction →
                    // 前端按"暂不支持一键确认"降级提示), 但要留痕排查
                    log.warn("预览 token 铸造失败 (预览照常返回): tool={}, error={}",
                            tool.getToolName(), e.getMessage());
                }
            }

            return previewResponse;

        } catch (Exception e) {
            log.error("Tool preview 失败: tool={}, error={}", tool.getToolName(), e.getMessage(), e);
            String errorMsg = "预览失败: " + ErrorSanitizer.sanitize(e);
            return IntentExecuteResponse.builder()
                    .intentRecognized(true)
                    .intentCode(intent.getIntentCode())
                    .intentName(intent.getIntentName())
                    .status("FAILED")
                    .message(errorMsg)
                    .formattedText(errorMsg)
                    .executedAt(LocalDateTime.now())
                    .build();
        }
    }

    /**
     * 解析 Tool 执行结果为 IntentExecuteResponse
     */
    @SuppressWarnings("unchecked")
    public IntentExecuteResponse parseToolResultToResponse(String resultJson, AIIntentConfig intent) {
        try {
            Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

            Boolean success = (Boolean) result.getOrDefault("success", true);
            Object data = result.get("data");

            String message = (String) result.get("message");
            if (message == null && data instanceof Map) {
                Map<String, Object> dataMap = (Map<String, Object>) data;
                message = (String) dataMap.get("message");
            }
            if (message == null) {
                // Sprint 12: enriched fallback. Bare "执行失败" violates close-gate
                // "≥80-char structured Chinese with concrete 数字/名称/日期, no placeholder".
                if (success) {
                    message = "操作已完成。";
                } else {
                    message = "本次查询没有获得可展示的结果。请补充明确的业务对象、指标和时间范围后重试；"
                            + "如果这些条件已经明确，可能是当前没有匹配数据或服务暂时繁忙。";
                }
            }

            Boolean needMoreInfo = (Boolean) result.getOrDefault("needMoreInfo", false);
            String resultStatus = (String) result.get("status");

            String status;
            if (Boolean.TRUE.equals(needMoreInfo) || "NEED_MORE_INFO".equals(resultStatus)) {
                status = "NEED_MORE_INFO";
            } else if (Boolean.TRUE.equals(success)) {
                status = "SUCCESS";
            } else {
                status = "FAILED";
            }

            return IntentExecuteResponse.builder()
                    .intentRecognized(true)
                    .intentCode(intent.getIntentCode())
                    .intentName(intent.getIntentName())
                    .intentCategory(intent.getIntentCategory())
                    .status(status)
                    .message(message)
                    .resultData(data)
                    .executedAt(LocalDateTime.now())
                    .build();

        } catch (Exception e) {
            log.error("解析 Tool 结果失败: json={}, error={}", resultJson, e.getMessage());
            return IntentExecuteResponse.builder()
                    .intentRecognized(true)
                    .intentCode(intent.getIntentCode())
                    .intentName(intent.getIntentName())
                    .intentCategory(intent.getIntentCategory())
                    .status("SUCCESS")
                    .message("执行完成")
                    .resultData(resultJson)
                    .executedAt(LocalDateTime.now())
                    .build();
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 使用 LLM Tool Calling 从用户输入中提取参数
     */
    private IntentExecuteResponse executeThroughMigrationGateway(
            ToolExecutor selectedTool,
            String factoryId,
            Map<String, Object> parameters,
            AIIntentConfig intent,
            Long userId,
            String userRole) {
        String toolName = selectedTool.getToolName();
        try {
            String expectedVersion = legacyToolMigrationRegistry.expectedVersion(toolName)
                    .orElseThrow(() -> new SecurityException(
                            "Legacy migration manifest binding disappeared"));
            String callId = "intent-dispatch-" + java.util.UUID.randomUUID();
            ExecutionPrincipal principal = authenticatedToolPrincipalFactory.create(
                    factoryId, userId, userRole);
            ToolExecutionCommand command = new ToolExecutionCommand(
                    callId,
                    callId,
                    callId + "-trace",
                    toolName,
                    expectedVersion,
                    objectMapper.valueToTree(parameters),
                    principal,
                    ToolExecutionSource.AI_INTENT_DISPATCH,
                    ToolExecutionMode.EXECUTE,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Instant.now().plusSeconds(30));
            ToolExecutionResult gatewayResult = toolExecutionGateway.execute(command);

            IntentExecuteResponse response;
            if (gatewayResult.payload().isObject()
                    && gatewayResult.payload().path("success").isBoolean()) {
                response = parseToolResultToResponse(
                        objectMapper.writeValueAsString(gatewayResult.payload()), intent);
            } else {
                response = IntentExecuteResponse.builder()
                        .intentRecognized(true)
                        .intentCode(intent.getIntentCode())
                        .intentName(intent.getIntentName())
                        .intentCategory(intent.getIntentCategory())
                        .status(gatewayResult.status().name())
                        .message(gatewayResult.message())
                        .formattedText(gatewayResult.message())
                        .executedAt(LocalDateTime.now())
                        .build();
            }
            Map<String, Object> metadata = response.getMetadata() == null
                    ? new HashMap<>()
                    : new HashMap<>(response.getMetadata());
            metadata.put("toolName", toolName);
            metadata.put("executionBoundary", "TOOL_EXECUTION_GATEWAY");
            metadata.put("gatewayStatus", gatewayResult.status().name());
            metadata.put("gatewayAuditEventId", gatewayResult.auditEventId());
            response.setMetadata(metadata);
            return response;
        } catch (Exception gatewayFailure) {
            // A selected migration call never re-enters legacy cache/retry/correction paths.
            log.error("D11B Gateway migration execution failed: tool={}",
                    toolName, gatewayFailure);
            return IntentExecuteResponse.builder()
                    .intentRecognized(true)
                    .intentCode(intent.getIntentCode())
                    .intentName(intent.getIntentName())
                    .intentCategory(intent.getIntentCategory())
                    .status("FAILED")
                    .message("Tool gateway execution failed")
                    .formattedText("Tool gateway execution failed")
                    .metadata(Map.of(
                            "toolName", toolName,
                            "executionBoundary", "TOOL_EXECUTION_GATEWAY",
                            "gatewayStatus", "FAILED"))
                    .executedAt(LocalDateTime.now())
                    .build();
        }
    }

    Map<String, Object> extractParametersWithLLM(String userInput, ToolExecutor tool,
                                                          Map<String, Object> existingParams) {
        Map<String, Object> extractedParams = new HashMap<>();
        try {
            Map<String, Object> parametersSchema = tool.getParametersSchema();
            if (parametersSchema == null || parametersSchema.isEmpty()) {
                return extractedParams;
            }

            @SuppressWarnings("unchecked")
            List<String> requiredParams = (List<String>) parametersSchema.get("required");
            if (requiredParams == null || requiredParams.isEmpty()) {
                return extractedParams;
            }

            List<String> missingParams = requiredParams.stream()
                    .filter(p -> !existingParams.containsKey(p) ||
                                 existingParams.get(p) == null ||
                                 (existingParams.get(p) instanceof String &&
                                  ((String) existingParams.get(p)).trim().isEmpty()))
                    .collect(Collectors.toList());

            if (missingParams.isEmpty()) {
                return extractedParams;
            }

            log.info("工具 {} 缺少参数 {}，启动 LLM 参数提取", tool.getToolName(), missingParams);

            Tool extractionTool = Tool.of(
                    "extract_parameters",
                    "从用户输入中提取 " + tool.getToolName() + " 操作所需的参数",
                    parametersSchema
            );

            String systemPrompt = String.format("""
                你是一个参数提取助手。你的任务是从用户的自然语言输入中提取操作所需的参数。

                当前操作: %s
                操作描述: %s

                请仔细分析用户输入，提取其中包含的参数值。
                - 如果用户明确提供了某个参数的值，请提取它
                - 如果用户没有提供某个参数，不要猜测或编造，直接忽略该参数
                - 参数值应该是用户原文中的信息，不要修改或翻译

                请使用 extract_parameters 工具返回提取的参数。
                """, tool.getToolName(), tool.getDescription() != null ? tool.getDescription() : "执行业务操作");

            ChatCompletionResponse response = dashScopeClient.chatWithTools(
                    systemPrompt, userInput, List.of(extractionTool));

            if (dashScopeClient.hasToolCalls(response)) {
                ToolCall toolCall = dashScopeClient.getFirstToolCall(response);
                if (toolCall != null && toolCall.getFunction() != null) {
                    String argumentsJson = toolCall.getFunction().getArguments();
                    if (argumentsJson != null && !argumentsJson.isEmpty()) {
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> args = objectMapper.readValue(argumentsJson, Map.class);
                            for (Map.Entry<String, Object> entry : args.entrySet()) {
                                if (entry.getValue() != null &&
                                    !(entry.getValue() instanceof String && ((String) entry.getValue()).isEmpty())) {
                                    extractedParams.put(entry.getKey(), entry.getValue());
                                }
                            }
                            log.info("LLM 参数提取成功: tool={}, extracted={}", tool.getToolName(), extractedParams.keySet());
                        } catch (JsonProcessingException e) {
                            log.warn("解析 LLM 返回的参数失败: {}", e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("LLM 参数提取异常: tool={}, error={}", tool.getToolName(), e.getMessage(), e);
        }
        return extractedParams;
    }

    /**
     * D7: Quick rule-based check — skip LLM validation when result is clearly valid.
     */
    boolean isResultClearlyValid(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) return false;
        if (resultJson.length() < 10) return false;
        try {
            Object parsed = objectMapper.readValue(resultJson, Object.class);
            if (parsed instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) parsed;
                if (map.containsKey("data") && map.get("data") != null) {
                    Object data = map.get("data");
                    if (data instanceof List && !((List<?>) data).isEmpty()) return true;
                    if (data instanceof Map && !((Map<?, ?>) data).isEmpty()) return true;
                }
                if (map.containsKey("content") && map.get("content") != null) return true;
                if (map.containsKey("result") && map.get("result") != null) return true;
                if (Boolean.TRUE.equals(map.get("success"))) return true;
            }
            if (parsed instanceof List && !((List<?>) parsed).isEmpty()) return true;
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    /**
     * 构建 "无 Tool" 响应
     *
     * <p>Sprint 11 Round 4 P1 fix (Bug 2) — surface diagnostic info instead of
     * generic "暂不支持此类型的意图执行: RESTAURANT_OPS". Per Sprint 10.5 PR #182
     * pattern (Skill 失败 surface 真实错误) and .claude/rules/fool-proof-design.md
     * "跨规则铁律 4 位一体" — error message must include actionable next-step,
     * not generic category name.
     *
     * <p>Triggered when:
     * <ul>
     *   <li>intent.tool_name was bound but Tool not found in ToolRegistry</li>
     *   <li>intent.tool_name=NULL AND no Skill routed AND no dynamic Tool selection</li>
     * </ul>
     *
     * <p>The customer-facing message tells the user the intent was recognized
     * (so they know AI understood them) but execution is not configured yet,
     * with an actionable hint to contact admin / try alternative phrasing.
     */
    public IntentExecuteResponse buildNoToolResponse(AIIntentConfig intent) {
        String intentName = intent.getIntentName() != null ? intent.getIntentName()
                : intent.getIntentCode();
        String boundTool = intent.getToolName();
        String diagnostic;
        String customerMessage;
        if (boundTool != null && !boundTool.isBlank()) {
            // tool_name configured but Tool not registered — likely missing @Component
            // or naming mismatch.
            diagnostic = String.format(
                "意图\"%s\"(%s)已识别，但配置的 Tool [%s] 未注册。"
                + "请联系管理员检查 Tool 注册或意图配置。",
                intentName, intent.getIntentCode(), boundTool);
            customerMessage = "已理解您的问题，但这项分析能力暂时不可用。请稍后重试；"
                    + "如果持续出现，请联系管理员检查分析能力配置。";
        } else {
            // tool_name=NULL and all dispatch paths exhausted — intent recognized
            // but no executor wired (Tool/Skill/dynamic selection all failed).
            diagnostic = String.format(
                "意图\"%s\"(%s)已识别，但暂未配置执行器（类别: %s）。"
                + "请联系管理员配置 Tool 或 Skill，或尝试更具体的提问方式。",
                intentName, intent.getIntentCode(), intent.getIntentCategory());
            customerMessage = "已理解您的问题，但当前还不能完成这项分析。"
                    + "请补充具体的业务对象、指标和时间范围后重试；如果条件已经明确，请联系管理员。";
        }
        log.warn("[ToolDispatch] customer-safe no-tool response; diagnostic={}", diagnostic);
        return IntentExecuteResponse.builder()
                .intentRecognized(true)
                .intentCode(intent.getIntentCode())
                .intentName(intent.getIntentName())
                .intentCategory(intent.getIntentCategory())
                .status("FAILED")
                .message(customerMessage)
                .formattedText(customerMessage)
                .executedAt(LocalDateTime.now())
                .build();
    }
}

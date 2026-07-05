package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.dto.ChatCompletionRequest;
import com.cretas.aims.ai.dto.ChatCompletionResponse;
import com.cretas.aims.ai.dto.ChatMessage;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.config.IntentKnowledgeBase.QuestionType;
import com.cretas.aims.dto.ai.*;
import com.cretas.aims.dto.cache.SemanticCacheHit;
import com.cretas.aims.dto.conversation.ConversationMessage;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.dto.intent.IntentValidationFact;
import com.cretas.aims.dto.intent.ValidationResult;
import com.cretas.aims.entity.AIAnalysisResult;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.conversation.ConversationSession;
import com.cretas.aims.entity.intent.IntentMatchRecord;
import com.cretas.aims.exception.LlmSchemaValidationException;
import com.cretas.aims.repository.AIAnalysisResultRepository;
import com.cretas.aims.repository.IntentMatchRecordRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.*;
import com.cretas.aims.service.calibration.BehaviorCalibrationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 意图执行编排器
 *
 * 核心 execute() 路由方法 -- 编排缓存检查、意图识别、
 * 权限/审批/规则验证、Tool/Skill/动态选择分发、
 * 结果格式化和缓存回写。
 *
 * 此类是 "指挥官"，调用其余 4 个子服务完成实际工作。
 */
@Slf4j
@Service
public class IntentExecutionOrchestrator {

    private static final Pattern STORE_REFERENCE_PATTERN = Pattern.compile(
            "那家店|这家店|该店|那个店|这个店|该门店|那家|这家");

    private static final Pattern DISH_REFERENCE_PATTERN = Pattern.compile(
            "那道菜|这道菜|该菜品|这个菜|那个菜|这款菜|那款菜|它");

    private static final Pattern RESTAURANT_OWNER_ACTION_DIRECT_PATTERN = Pattern.compile(
            "老板|店长|区域经理|今天.*动作|今天.*应该|今天.*最应该|今天.*怎么|今天.*要不要|今天.*适合|今天.*查|今天.*调|今天.*改|今天.*提高|"
                    + "具体动作|提高营收|提升营收|提高营业额|提高客单价|怎么提高|怎么提升|应该怎么|应该先|怎么处理|怎么培训|先改|先做|"
                    + "今天.*做|今天.*推|今天.*放|今天.*讲|今天.*带着推|今晚.*排班|要怎么备货|怎么调|怎么排班|哪个时间段|最需要加人|加前厅|加后厨|帮我算.*套餐|能不能复制|应该复制|复制哪家店|复制.*做法|哪家店.*做法|连锁内部|排名不高|"
                    + "主推|值得主推|低价值|加购|拉动加购|首屏|短视频|最值得学习|"
                    + "怎么优化|怎么解决|优化.*营收|优化.*收入|解决方案|改进建议|怎么做生意|增加营收|增加营业额|提高收入|提升收入|先看哪|先不要|不要做|先训练|训练哪|"
                    + "哪些菜.*备|不适合.*备|少备|备太多|继续备|不能多备|不应该.*推|继续重点推|BOM.*查|理论用量|实际用量|成本毛利|毛利掉|月盘点|损耗高");

    private static final Pattern RESTAURANT_OWNER_ACTION_TOPIC_PATTERN = Pattern.compile(
            "二人桌|两人桌|四人桌|桌型|桌子|翻台|翻台率|排队|等位|小套餐|套餐|排班|加人|前厅|后厨|员工|厨房|出餐|上菜慢|服务差评|"
                    + "商圈|商场|客流|画像|进店|转化|曝光|核销|活动|天气|备货|少备|备太多|不能多备|推品|主推|单品|爆品|低价值|加购|毛利|成本|BOM|理论用量|实际用量|月盘点|损耗高|原料|采购|价格|门店|同商圈门店|连锁|排名|品牌|单店|复购|复杂菜|"
                    + "评论|顾客|差评|好评|库存|销量|风险|菜|菜品|服务问题|营收|收入|营业额|利润|评价|口碑|体验|平台|美团|大众点评|抖音|短视频|团购|菜单|桌数|哪家店|门口|路过|下单|订单|入口|页面|首图|首屏|服务员|话术|开班前|训练");

    private static final Pattern RESTAURANT_OWNER_ACTION_DECISION_PATTERN = Pattern.compile(
            "今天|今晚|这个星期|本周|怎么|如何|为什么|要不要|有没有|哪些问题|问题|最在意|最应该|影响|风险|建议|适合|带动|一起卖|比|补|推|调|排|改|提高|提升|优化|解决|安排|算|做什么|动作|先查|先看|先不要|处理|培训|复制|学习|表现|差在哪里");

    private static final Pattern RESTAURANT_OWNER_ACTION_FORCE_PATTERN = Pattern.compile(
            "厨师长|仓管|前台|门迎|员工工时|几段班|人效|午市|晚市|分别盯什么|具体补什么|"
                    + "配什么小菜|小菜饮品|别只看销量|厨房备菜|备菜怎么调|商场今天有活动|门口和套餐|"
                    + "天气热|品类更合适|厨房慢|服务慢");

    // ===== 依赖 =====
    private final AIIntentService aiIntentService;
    private final IntentSemanticsParser semanticsParser;
    private final SemanticCacheService semanticCacheService;
    private final RuleEngineService ruleEngineService;
    private final ConversationService conversationService;
    private final ConversationMemoryService conversationMemoryService;
    private final ObjectMapper objectMapper;
    private final DashScopeClient dashScopeClient;
    private final DashScopeConfig dashScopeConfig;
    private final IntentKnowledgeBase knowledgeBase;
    private final AIAnalysisResultRepository analysisResultRepository;
    private final ToolRegistry toolRegistry;
    private final AnalysisRouterService analysisRouterService;
    private final ComplexityRouter complexityRouter;
    private final AgentOrchestrator agentOrchestrator;
    private final AgenticRAGRouterService agenticRAGRouterService;
    private final ResultValidatorService resultValidatorService;

    // Sub-services
    private final ToolDispatchService toolDispatchService;
    private final DynamicToolSelectionService dynamicToolSelectionService;

    // W1b negation veto (intent-w1b): early VETO gate in the execution layer. Low-level service
    // (QueryPreprocessorServiceImpl has no dependency back on the orchestrator), so safe to inject
    // via the constructor — no circular dependency.
    private final QueryPreprocessorService queryPreprocessorService;

    // Optional dependencies
    @Autowired(required = false)
    private ResultFormatterService resultFormatterService;

    @Autowired(required = false)
    private WorkdeskOutputSummarizer workdeskOutputSummarizer;

    @Autowired(required = false)
    private PreviewTokenService previewTokenService;

    @Autowired(required = false)
    private ProductTypeRepository productTypeRepository;

    @Autowired(required = false)
    private IntentMatchRecordRepository intentMatchRecordRepository;

    @Autowired(required = false)
    private SlotFillingService slotFillingService;

    @Autowired(required = false)
    private com.cretas.aims.config.IntentSlotConfiguration intentSlotConfiguration;

    // Sprint 13 #305 业态门控: shared business-type gate (RESTAURANT vs FACTORY). Reused by the
    // explicit-intent flow + SseStreamingService so all paths gate identically.
    @Autowired
    private BusinessTypeGate businessTypeGate;

    // W0 write-guard (intent-w0): confidence-independent write detection. Inserted at the
    // explicit-intent convergence point so a misroute to a write intent cannot silently execute,
    // INCLUDING via forceExecute=true (multi-intent / conversation-continuation hard-set true).
    @Autowired
    private com.cretas.aims.ai.tool.WriteGuardService writeGuardService;

    // C1 clarification business-type filter (restaurant-chat-qa): resolve the factory's business
    // domain + each candidate intent's business_type so the NEED_CLARIFICATION choice list never
    // shows manufacturing options (查询原料库存 / 查询生产批次) to a RESTAURANT tenant.
    // @Lazy mirrors BusinessTypeGate's injection pattern (avoids eager init / circular wiring).
    @Autowired
    @Lazy
    private com.cretas.aims.service.intent.IntentConfigManagementService configService;

    // ===== Route counters =====
    private final AtomicLong branchToolDirect = new AtomicLong();
    private final AtomicLong branchSkill = new AtomicLong();
    private final AtomicLong branchDynamic = new AtomicLong();
    private final AtomicLong branchNoMatch = new AtomicLong();

    @Value("${cretas.ai.validation.enabled:true}")
    private boolean validationEnabled;

    /** 通用短回复集合 */
    private static final Set<String> GENERIC_SHORT_REPLIES = Set.of(
            "查询完成，暂无数据", "操作完成", "查询完成", "查询成功", "执行成功",
            "查询完成,暂无数据", "处理完成", "请求成功"
    );

    @Autowired
    public IntentExecutionOrchestrator(
            @Lazy AIIntentService aiIntentService,
            IntentSemanticsParser semanticsParser,
            SemanticCacheService semanticCacheService,
            RuleEngineService ruleEngineService,
            ConversationService conversationService,
            ConversationMemoryService conversationMemoryService,
            ObjectMapper objectMapper,
            DashScopeClient dashScopeClient,
            DashScopeConfig dashScopeConfig,
            IntentKnowledgeBase knowledgeBase,
            AIAnalysisResultRepository analysisResultRepository,
            ToolRegistry toolRegistry,
            AnalysisRouterService analysisRouterService,
            ComplexityRouter complexityRouter,
            AgentOrchestrator agentOrchestrator,
            AgenticRAGRouterService agenticRAGRouterService,
            ResultValidatorService resultValidatorService,
            ToolDispatchService toolDispatchService,
            DynamicToolSelectionService dynamicToolSelectionService,
            QueryPreprocessorService queryPreprocessorService) {
        this.aiIntentService = aiIntentService;
        this.semanticsParser = semanticsParser;
        this.semanticCacheService = semanticCacheService;
        this.ruleEngineService = ruleEngineService;
        this.conversationService = conversationService;
        this.conversationMemoryService = conversationMemoryService;
        this.objectMapper = objectMapper;
        this.dashScopeClient = dashScopeClient;
        this.dashScopeConfig = dashScopeConfig;
        this.knowledgeBase = knowledgeBase;
        this.analysisResultRepository = analysisResultRepository;
        this.toolRegistry = toolRegistry;
        this.analysisRouterService = analysisRouterService;
        this.complexityRouter = complexityRouter;
        this.agentOrchestrator = agentOrchestrator;
        this.agenticRAGRouterService = agenticRAGRouterService;
        this.resultValidatorService = resultValidatorService;
        this.toolDispatchService = toolDispatchService;
        this.dynamicToolSelectionService = dynamicToolSelectionService;
        this.queryPreprocessorService = queryPreprocessorService;
    }

    @PostConstruct
    public void init() {
        log.info("意图执行编排器初始化完成 (Facade-SubService 架构)");
    }

    // ==================== 核心执行方法 ====================

    /**
     * 主执行方法 -- 路由用户请求到 Tool / Skill / 动态选择 / LLM 对话
     */
    public IntentExecuteResponse execute(String factoryId, IntentExecuteRequest request,
                                          Long userId, String userRole) {

        log.info("执行意图: factoryId={}, userInput={}, intentCode={}, userId={}, role={}",
                factoryId,
                request.getUserInput() != null && request.getUserInput().length() > 50 ?
                        request.getUserInput().substring(0, 50) + "..." : request.getUserInput(),
                request.getIntentCode(), userId, userRole);

        // 0. 显式意图代码
        if (request.getIntentCode() != null && !request.getIntentCode().isEmpty()) {
            return executeWithExplicitIntent(factoryId, request, userId, userRole);
        }

        // 0.1. W1b: early VETO gate in the execution layer. The orchestrator's own phrase shortcut
        // (#0.2 below) is contains-based and would execute "看订单" inside "别给我看订单" — bypassing
        // the recognition-layer veto gate. Mirror it here. Skipped for explicit intentCode (handled
        // above), so a user explicitly confirming a write is never vetoed.
        //   - VETO_READ  → return clarification immediately (no phrase shortcut, no execution).
        //   - VETO_WRITE → guard the short-circuits below so the request falls through to
        //                  recognizeIntentWithConfidence, which already handles VETO_WRITE safely
        //                  (OUT_OF_DOMAIN / read-twin, never an executed write).
        boolean negationVetoWrite = false;
        String userInput = request.getUserInput();
        if (hasRestaurantOwnerActionSignal(factoryId, request.getContext()) && matchesOwnerActionKeywordHeuristic(userInput)) {
            log.info("[restaurant-owner-action] force-route before preprocessor: factoryId={}, input={}",
                    factoryId, userInput);
            return executeRestaurantOwnerActionChat(factoryId, request, userId);
        }
        String vInput = request.getUserInput();
        if (vInput != null && !vInput.isEmpty()) {
            QueryPreprocessorService.NegationKind vk;
            try {
                vk = queryPreprocessorService.detectNegationVeto(vInput, knowledgeBase);
            } catch (Exception e) {
                log.warn("[W1b] orchestrator detectNegationVeto failed, fail-open NONE for input='{}': {}", vInput, e.toString());
                vk = QueryPreprocessorService.NegationKind.NONE;
            }
            if (vk == QueryPreprocessorService.NegationKind.VETO_READ
                    && shouldRouteRestaurantOwnerAction(factoryId, userInput, request.getContext())) {
                log.info("[restaurant-owner-action] route before VETO_READ clarification: factoryId={}, input={}",
                        factoryId, userInput);
                return executeRestaurantOwnerActionChat(factoryId, request, userId);
            }
            if (vk == QueryPreprocessorService.NegationKind.VETO_READ) {
                log.info("[W1b] orchestrator VETO_READ → clarification (no phrase shortcut, no execution): '{}'", vInput);
                return buildNegationVetoClarificationResponse(vInput);
            }
            negationVetoWrite = (vk == QueryPreprocessorService.NegationKind.VETO_WRITE);
        }

        // 0.15. 餐饮老板动作建议统一入口:
        // Web Admin 只调用 Java /ai-intents/execute；需要 Python 深度分析时由 Java 内部路由。
        // 放在普通餐饮 phrase shortcut 前面，避免“提高营收/排班/套餐/桌型”被普通查询工具抢走。
        // Owner-action is advisory/read-only. Keep routing restaurant老板 follow-ups like
        // "不要套餐，今天排班和备货怎么调？" through this path even when negation
        // pre-processing marks the wording as VETO_WRITE; otherwise it falls into a
        // low-level report intent and returns "missing month_summary" instead of a decision.
        if (shouldRouteRestaurantOwnerAction(factoryId, userInput, request.getContext())) {
            log.info("[restaurant-owner-action] route before generic intent matching: factoryId={}, input={}",
                    factoryId, userInput);
            return executeRestaurantOwnerActionChat(factoryId, request, userId);
        }

        // 0.2. Sprint 12 cache-fix Phase C — Phrase shortcut moved AHEAD of conversation
        //      continuation to defeat auto-mount inheritance bug.
        //
        // PRIOR STATE (Sprint 11 Round 7 + Sprint 12 PR #246):
        //   #0.3 conversation continuation ran FIRST. When a Vue Workdesk auto-mounted with
        //   intentCode=DAILY_CUSTOMER_FOLLOWUP, the response set conversation status=COMPLETED
        //   bound to that intent. When the user then typed a phrase ("帮我看上月损溢异常" etc.),
        //   handleConversationContinuation (line 576-580) detected isCompleted + non-null
        //   intentCode → setIntentCode(DAILY_CUSTOMER_FOLLOWUP) + forceExecute → routed via
        //   executeWithExplicitIntent. Phrase shortcut at the old #0.25 position never ran.
        //   This is the 9/12 misroute mechanism per AI 工厂 5/28 audit gh issue #277 H2 hypothesis.
        //
        // CURRENT FIX (Sprint 12 cache-fix Phase C):
        //   Phrase shortcut at #0.2 runs FIRST when userInput is non-empty. If the user's input
        //   matches a known phrase AND resolves to a configured (or platform-level) intent, the
        //   shortcut takes over — user-intent always wins over inherited conversation state.
        //   Conversation continuation still runs at #0.3 for the cases shortcut declines to handle
        //   (one-word answers, clarification responses, anything not in the phrase map).
        //
        // Safety: tryOrchestratorPhraseShortcut returns null when no phrase matches, so non-phrase
        // inputs fall through unchanged to continuation → identical behavior for clarification flows.
        // executeWithExplicitIntent does NOT route through continuation (sessionId is passed through
        // but conversationService is only consulted for in-progress sessions — explicit intent
        // bypasses the inheritance path).
        //
        // Phrase confidence is 0.96 (matching v33.1 EarlyPhrase tier so /recognize and /execute
        // produce consistent routing).
        if (!negationVetoWrite && userInput != null && !userInput.isEmpty()) {
            IntentMatchResult restaurantOpsMatch = tryRestaurantOpsPhraseShortcut(userInput, factoryId);
            if (restaurantOpsMatch != null && restaurantOpsMatch.hasMatch()) {
                AIIntentConfig phraseIntent = restaurantOpsMatch.getBestMatch();
                log.info("[RestaurantOpsGoldRoute] Pre-bypass phrase shortcut: input='{}', intentCode={}",
                        userInput, phraseIntent.getIntentCode());
                IntentExecuteRequest phraseRequest = IntentExecuteRequest.builder()
                        .userInput(userInput)
                        .intentCode(phraseIntent.getIntentCode())
                        .sessionId(request.getSessionId())
                        .enableThinking(request.getEnableThinking())
                        .thinkingBudget(request.getThinkingBudget())
                        .context(request.getContext())
                        .build();
                return executeWithExplicitIntent(factoryId, phraseRequest, userId, userRole);
            }
        }
        if (!negationVetoWrite && userInput != null && !userInput.isEmpty()
                && !shouldBypassEarlyPhraseShortcutForStoreReference(userInput)) {
            IntentMatchResult earlyPhraseMatch = tryOrchestratorPhraseShortcut(userInput, factoryId);
            if (earlyPhraseMatch != null && earlyPhraseMatch.hasMatch()) {
                AIIntentConfig phraseIntent = earlyPhraseMatch.getBestMatch();
                log.info("[Sprint12-CacheFix-EarlyPhrase] Pre-continuation phrase shortcut: input='{}', intentCode={}",
                        userInput, phraseIntent.getIntentCode());
                IntentExecuteRequest phraseRequest = IntentExecuteRequest.builder()
                        .userInput(userInput)
                        .intentCode(phraseIntent.getIntentCode())
                        .sessionId(request.getSessionId())
                        .enableThinking(request.getEnableThinking())
                        .thinkingBudget(request.getThinkingBudget())
                        .context(request.getContext())
                        .build();
                return executeWithExplicitIntent(factoryId, phraseRequest, userId, userRole);
            }
        }

        // 0.3. 多轮对话延续 (runs AFTER phrase shortcut per Sprint 12 cache-fix Phase C)
        if (request.getSessionId() != null && !request.getSessionId().isEmpty()) {
            IntentExecuteResponse conversationResponse = handleConversationContinuation(
                    factoryId, request, userId, userRole);
            if (conversationResponse != null) {
                return conversationResponse;
            }
        }

        // 0.3. 早期问题类型检测
        if (!negationVetoWrite && userInput != null && !userInput.isEmpty()) {
            IntentExecuteResponse earlyRouteResponse = handleEarlyQuestionTypeDetection(
                    factoryId, userInput, request, userId, userRole);
            if (earlyRouteResponse != null) {
                return earlyRouteResponse;
            }
        }

        // 0.5. 语义缓存
        if (userInput != null && !userInput.isEmpty()) {
            handleSemanticCache(factoryId, userInput, request);
        }

        // 1. 识别意图 (former phrase shortcut moved up to position #0.25; this branch is now
        //    only the normal recognition pipeline)
        IntentMatchResult matchResult = null;

        if (matchResult == null) {
            try {
                matchResult = aiIntentService.recognizeIntentWithConfidence(
                        request.getUserInput(), factoryId, 3, userId, userRole, request.getSessionId());
            } catch (LlmSchemaValidationException e) {
                log.warn("LLM Schema 验证失败: type={}, message={}", e.getFailureType(), e.getMessage());
                return buildValidationFailureResponse(factoryId, request.getUserInput(), e);
            }
        }

        // 2. 二次确认
        if (matchResult.hasMatch() && Boolean.TRUE.equals(matchResult.getRequiresConfirmation())
                && !Boolean.TRUE.equals(request.getForceExecute())) {
            return buildClarificationResponse(matchResult, factoryId);
        }

        // 2b. 通用咨询/闲聊
        if (matchResult.getQuestionType() == QuestionType.GENERAL_QUESTION ||
            matchResult.getQuestionType() == QuestionType.CONVERSATIONAL) {
            String llmResponse = generateConversationalResponse(factoryId, request.getUserInput(),
                    matchResult.getQuestionType(), request.getEnableThinking(), request.getThinkingBudget());
            return IntentExecuteResponse.builder()
                    .intentRecognized(false)
                    .status("COMPLETED")
                    .message(llmResponse)
                    .formattedText(llmResponse)
                    .executedAt(LocalDateTime.now())
                    .build();
        }

        // 3. 无匹配
        if (!matchResult.hasMatch()) {
            return buildNoMatchResponse(matchResult, factoryId, request, userId);
        }

        AIIntentConfig intent = matchResult.getBestMatch();
        log.info("识别到意图: code={}, category={}, sensitivity={}, matchMethod={}, confidence={}",
                intent.getIntentCode(), intent.getIntentCategory(), intent.getSensitivityLevel(),
                matchResult.getMatchMethod(), matchResult.getConfidence());

        if (requiresStoreReferenceClarification(request, matchResult, intent)) {
            return buildStoreReferenceClarificationResponse(request);
        }

        if (requiresDishReferenceClarification(request, matchResult, intent)) {
            return buildDishReferenceClarificationResponse(request);
        }

        // 权限检查
        if (!aiIntentService.hasPermission(intent.getIntentCode(), userRole)) {
            return buildNoPermissionResponse(intent);
        }

        // 审批检查
        if (intent.needsApproval() && !Boolean.TRUE.equals(request.getForceExecute())) {
            return buildApprovalResponse(intent);
        }

        // 业态门控 (Sprint 13 #305): a domain-exclusive intent (business_type RESTAURANT/FACTORY)
        // matched on a mismatched factory.type → honest "本厂非该业态" empty-state + appropriate
        // next-action, instead of executing a tool that returns a misleading half-broken "数据不可用".
        IntentExecuteResponse businessTypeGate = checkBusinessTypeGate(factoryId, intent);
        if (businessTypeGate != null) {
            return businessTypeGate;
        }

        // Drools 验证
        // Read-only query intents should not enter the Drools validation engine:
        // the engine is meant to guard writes/critical actions, and repeated
        // compilation on high-volume chat queries can exhaust JVM Metaspace.
        if (shouldRunDroolsValidation(intent)) {
            ValidationResult validationResult = validateWithDrools(factoryId, intent, request, userId, userRole);
            if (!validationResult.isValid()) {
                return buildDroolsFailureResponse(intent, validationResult);
            }
        }

        // 3.5. Skill 优先检查
        String boundToolName = intent.getToolName();
        if (dynamicToolSelectionService.isSkillsEnabled()
                && (boundToolName == null || boundToolName.isBlank())) {
            IntentExecuteResponse skillResponse = dynamicToolSelectionService.trySkillRoute(
                    request.getUserInput(), factoryId, userId);
            if (skillResponse != null) {
                long count = branchSkill.incrementAndGet();
                log.info("[Branch:Skill] Skill 优先匹配成功: total={}", count);
                return skillResponse;
            }

            // Sprint 9 P0.1 fix (2026-05-21): Sprint 8 WORKDESK intents (DAILY_CUSTOMER_FOLLOWUP /
            // MONTHLY_FINANCIAL_CLOSE / FOOD_SAFETY_RECALL 等) bind tool_name=NULL + 依赖 Skill 路由.
            // 但 trySkillRoute 用 Skill 自己 keywords 匹用户原文 — 若 LLM 已正确识别 intent
            // 但用户原文不含 Skill triggers (例如 "客户跟进" 在 intent keywords 但 Skill triggers
            // 是 "今天跟谁" / "今日跟进"), trySkillRoute 返 null → fall through 到
            // buildNoToolResponse → 报 "暂不支持此类型的意图执行: WORKDESK". P0 阻塞性 bug.
            //
            // Fix: 当 intent 已识别且 tool_name=NULL 时, 用 intent_code (UPPER_SNAKE) →
            // skill_name (lower-kebab) 直接 lookup Skill, 绕过 keyword 匹配.
            IntentExecuteResponse explicitSkillResponse = dynamicToolSelectionService
                    .tryExplicitSkillRouteForIntent(intent, request.getUserInput(), factoryId, userId);
            if (explicitSkillResponse != null) {
                long count = branchSkill.incrementAndGet();
                log.info("[Branch:Skill] 显式 Skill 路由 (by intent_code) 成功: intentCode={}, total={}",
                        intent.getIntentCode(), count);
                return explicitSkillResponse;
            }
        }

        // 3.6. Slot Filling
        if (userId != null && !Boolean.TRUE.equals(request.getSkipSlotFilling()) && slotFillingService != null) {
            IntentExecuteResponse slotFillingResponse = slotFillingService.checkAndStartSlotFilling(
                    factoryId, userId, intent, request, matchResult);
            if (slotFillingResponse != null) {
                if (slotFillingResponse.getFormattedText() == null
                        && slotFillingResponse.getMessage() != null
                        && slotFillingResponse.getMessage().length() >= 5) {
                    slotFillingResponse.setFormattedText(slotFillingResponse.getMessage());
                }
                return slotFillingResponse;
            }
        }

        // 4. 路由到执行器
        String toolName = intent.getToolName();
        IntentExecuteResponse response;

        if (toolName != null && !toolName.isEmpty()) {
            Optional<ToolExecutor> toolOpt = toolRegistry.getExecutor(toolName);
            if (toolOpt.isPresent()) {
                long count = branchToolDirect.incrementAndGet();
                log.info("[Branch:ToolDirect] 使用 Tool 执行: intentCode={}, toolName={}, total={}",
                        intent.getIntentCode(), toolName, count);
                response = toolDispatchService.executeWithTool(toolOpt.get(), factoryId, request, intent, userId, userRole, matchResult);
            } else {
                log.warn("Tool 未找到: toolName={}, intentCode={}", toolName, intent.getIntentCode());
                response = toolDispatchService.buildNoToolResponse(intent);
            }
        } else if (dynamicToolSelectionService.isSkillsEnabled()
                && dynamicToolSelectionService.requiresDynamicSelection(matchResult)) {
            long count = branchDynamic.incrementAndGet();
            log.info("[Branch:Dynamic] 触发动态工具选择: total={}", count);
            response = dynamicToolSelectionService.executeWithDynamicToolSelection(
                    factoryId, request, intent, matchResult, userId, userRole);
        } else if (dynamicToolSelectionService.requiresDynamicSelection(matchResult)) {
            long count = branchDynamic.incrementAndGet();
            log.info("[Branch:Dynamic] 触发动态工具选择(无Skill): total={}", count);
            response = dynamicToolSelectionService.executeWithDynamicToolSelection(
                    factoryId, request, intent, matchResult, userId, userRole);
        } else {
            // Sprint 9 P0.1 fix (2026-05-21): 最终 fallback 前再尝试一次显式 Skill 路由
            // (覆盖 dynamicToolSelectionService.isSkillsEnabled() = false 但 SkillRouter
            // 仍可用 / Skill 已注册但 keyword 没匹的 corner case).
            IntentExecuteResponse explicitSkillFallback = dynamicToolSelectionService
                    .tryExplicitSkillRouteForIntent(intent, request.getUserInput(), factoryId, userId);
            if (explicitSkillFallback != null) {
                long count = branchSkill.incrementAndGet();
                log.info("[Branch:Skill] 显式 Skill 路由 (NoMatch fallback) 成功: intentCode={}, total={}",
                        intent.getIntentCode(), count);
                response = explicitSkillFallback;
            } else {
                long count = branchNoMatch.incrementAndGet();
                log.warn("[Branch:NoMatch] 无路由匹配: intentCode={}, total={}", intent.getIntentCode(), count);
                response = toolDispatchService.buildNoToolResponse(intent);
            }
        }

        // 路由分支统计
        logBranchStats();

        // 6.5. NEED_MORE_INFO enrichment
        if ("NEED_MORE_INFO".equals(response.getStatus())) {
            response = enrichWithClarificationQuestions(response, request, intent, factoryId, userId);
        }

        // 6.8. 结果格式化
        applyResultFormatting(response);

        // 6.9. formattedText 兜底
        applyFormattedTextFallback(response);

        // 7. 缓存
        processResponseCaching(factoryId, request, matchResult, response);

        // 8. 对话记忆
        // D1-fix: Ensure ConversationMemory row exists before writing slots/messages.
        // ConversationMemoryServiceImpl.updateEntitySlot / addMessage silently bail out
        // when the session row does not yet exist (no auto-create). The explicit-intent
        // path (executeWithExplicitIntent → persistConversationMemoryForExplicitIntent)
        // already calls getOrCreateContext first; mirror that guarantee here so DISH /
        // STORE slots written by extractAndUpdateEntitySlots are actually persisted.
        if (request.getSessionId() != null && !request.getSessionId().isEmpty()) {
            try {
                conversationMemoryService.getOrCreateContext(factoryId, userId, request.getSessionId());
            } catch (Exception e) {
                log.warn("对话记忆 getOrCreateContext 失败 (非阻断): sessionId={}, error={}",
                        request.getSessionId(), e.getMessage());
            }
            updateConversationMemory(request.getSessionId(), request, response, matchResult, factoryId, userId);
        }

        return response;
    }

    /**
     * 使用显式意图代码执行 (跳过意图识别)
     */
    public IntentExecuteResponse executeWithExplicitIntent(String factoryId, IntentExecuteRequest request,
                                                            Long userId, String userRole) {
        String intentCode = request.getIntentCode();
        log.info("使用显式意图代码执行: intentCode={}, factoryId={}", intentCode, factoryId);

        Optional<AIIntentConfig> intentOpt = aiIntentService.getIntentByCode(factoryId, intentCode);
        if (intentOpt.isEmpty()) {
            return IntentExecuteResponse.builder()
                    .intentRecognized(false)
                    .status("FAILED")
                    .message("未找到意图配置: " + intentCode)
                    .executedAt(LocalDateTime.now())
                    .build();
        }

        AIIntentConfig intent = intentOpt.get();

        if (!aiIntentService.hasPermission(intent.getIntentCode(), userRole)) {
            return buildNoPermissionResponse(intent);
        }

        // W0 write-guard (intent-w0) — SITE A: the convergence point all explicit-code / forced /
        // multi-intent / phrase-shortcut / conversation-continuation paths funnel through. The guard
        // is deliberately NOT conditioned on request.getForceExecute() (which is hard-set true by the
        // multi-intent and conversation-continuation paths and is exactly what must NOT skip the
        // guard). previewOnly requests are allowed (they preview, not execute); a confirmed=true
        // signal in the request context means the user already confirmed → allow.
        java.util.Map<String, Object> ctx = request.getContext() != null ? request.getContext() : java.util.Map.of();
        if (writeGuardService.isWriteIntent(intent)
                && !Boolean.TRUE.equals(request.getPreviewOnly())
                && !writeGuardService.isConfirmed(ctx)) {
            log.info("W0 write-guard: blocked write intent {} (forceExecute={}, confirmed=false)",
                    intent.getIntentCode(), request.getForceExecute());
            return IntentExecuteResponse.builder()
                    .intentRecognized(true)
                    .intentCode(intent.getIntentCode())
                    .intentName(intent.getIntentName())
                    .status("WRITE_CONFIRM_REQUIRED")
                    .message("「" + intent.getIntentName() + "」是写入/修改操作，执行前需要确认。")
                    .requiresApproval(true)
                    .executedAt(java.time.LocalDateTime.now())
                    .build();
        }

        if (intent.needsApproval() && !Boolean.TRUE.equals(request.getForceExecute())) {
            return buildApprovalResponse(intent);
        }

        // 业态门控 (Sprint 13 #305): this explicit-intent path is reached for an explicit
        // intentCode AND for phrase-shortcut matches (execute() #0 / #0.2 delegate here),
        // bypassing the gate in the main matching flow. Gate here too so a domain-exclusive
        // intent (e.g. RESTAURANT_ECONOMICS_ANALYSIS) on a mismatched factory.type returns the
        // honest "本厂非该业态" empty-state instead of executing a tool with no data.
        IntentExecuteResponse businessTypeGate = checkBusinessTypeGate(factoryId, intent);
        if (businessTypeGate != null) {
            return businessTypeGate;
        }

        // Preview mode
        if (Boolean.TRUE.equals(request.getPreviewOnly()) && intent.getToolName() != null && !intent.getToolName().isEmpty()) {
            Optional<ToolExecutor> previewToolOpt = toolRegistry.getExecutor(intent.getToolName());
            if (previewToolOpt.isPresent() && previewToolOpt.get().supportsPreview()) {
                return toolDispatchService.executeToolPreview(previewToolOpt.get(), factoryId, request, intent, userId, userRole);
            }
            return IntentExecuteResponse.builder()
                    .intentRecognized(true)
                    .intentCode(intent.getIntentCode())
                    .intentName(intent.getIntentName())
                    .status("FAILED")
                    .message("预览模式暂不支持此意图类型")
                    .executedAt(LocalDateTime.now())
                    .build();
        }

        // Tool 执行
        String toolName = intent.getToolName();
        IntentExecuteResponse response;

        if (toolName != null && !toolName.isEmpty()) {
            Optional<ToolExecutor> toolOpt = toolRegistry.getExecutor(toolName);
            if (toolOpt.isPresent()) {
                response = toolDispatchService.executeWithTool(toolOpt.get(), factoryId, request, intent, userId, userRole, null);
            } else {
                response = toolDispatchService.buildNoToolResponse(intent);
            }
        } else {
            // Sprint 9 P0.1 fix (2026-05-21): explicit intent path 同 execute() 一样需 Skill fallback.
            // 显式传 intent_code 走这分支 (e.g. Workdesk 前端按钮直接 POST intent_code).
            IntentExecuteResponse explicitSkillFallback = dynamicToolSelectionService
                    .tryExplicitSkillRouteForIntent(intent, request.getUserInput(), factoryId, userId);
            if (explicitSkillFallback != null) {
                log.info("[Explicit-Intent] 显式 Skill 路由成功: intentCode={}", intent.getIntentCode());
                response = explicitSkillFallback;
            } else {
                response = toolDispatchService.buildNoToolResponse(intent);
            }
        }

        if ("NEED_MORE_INFO".equals(response.getStatus())) {
            response = enrichWithClarificationQuestions(response, request, intent, factoryId, userId);
        }

        applyResultFormatting(response);
        applyFormattedTextFallback(response);

        // X1 Part B 修复:短路 / 显式意图路径也持久化对话记忆,供下一轮续接继承。
        persistConversationMemoryForExplicitIntent(factoryId, request, response, intent, userId);

        return response;
    }

    /**
     * 确认执行预览的操作
     */
    public IntentExecuteResponse confirm(String factoryId, String confirmToken,
                                          Long userId, String userRole) {
        log.info("确认执行: factoryId={}, confirmToken={}", factoryId, confirmToken);

        if (previewTokenService == null) {
            return IntentExecuteResponse.builder()
                    .status("FAILED")
                    .message("确认服务不可用")
                    .executedAt(LocalDateTime.now())
                    .build();
        }

        PreviewTokenService.ConfirmResult confirmResult = previewTokenService.confirmToken(confirmToken, userId);
        if (!confirmResult.isSuccess()) {
            return IntentExecuteResponse.builder()
                    .status("FAILED")
                    .message(confirmResult.getMessage())
                    .executedAt(LocalDateTime.now())
                    .build();
        }

        var token = confirmResult.getToken();
        String intentCode = token.getIntentCode();
        String entityId = token.getEntityId();
        String entityType = token.getEntityType();

        try {
            Map<String, Object> context = confirmResult.getExecutionResult() != null
                    ? confirmResult.getExecutionResult() : new HashMap<>();
            context.put("confirmed", true);
            context.put("entityId", entityId);
            context.put("entityType", entityType);

            IntentExecuteRequest execRequest = IntentExecuteRequest.builder()
                    .userInput("确认执行: " + intentCode)
                    .context(context)
                    .build();

            Optional<AIIntentConfig> intentConfigOpt = aiIntentService.getIntentByCode(factoryId, intentCode);
            if (intentConfigOpt.isEmpty()) {
                return IntentExecuteResponse.builder()
                        .status("FAILED")
                        .message("意图配置不存在: " + intentCode)
                        .executedAt(LocalDateTime.now())
                        .build();
            }

            AIIntentConfig intentConfig = intentConfigOpt.get();
            String toolName = intentConfig.getToolName();
            if (toolName != null && !toolName.isEmpty()) {
                Optional<ToolExecutor> toolOpt = toolRegistry.getExecutor(toolName);
                if (toolOpt.isPresent()) {
                    return toolDispatchService.executeWithTool(
                            toolOpt.get(), factoryId, execRequest, intentConfig, userId, userRole, null);
                }
            }

            return IntentExecuteResponse.builder()
                    .status("FAILED")
                    .message("未找到工具: " + intentCode)
                    .executedAt(LocalDateTime.now())
                    .build();

        } catch (Exception e) {
            log.error("确认执行失败: confirmToken={}, error={}", confirmToken, e.getMessage(), e);
            return IntentExecuteResponse.builder()
                    .status("FAILED")
                    .message("执行失败: " + com.cretas.aims.util.ErrorSanitizer.sanitize(e))
                    .executedAt(LocalDateTime.now())
                    .build();
        }
    }

    // ==================== 内部路由方法 ====================

    private IntentExecuteResponse handleConversationContinuation(String factoryId, IntentExecuteRequest request,
                                                                   Long userId, String userRole) {
        log.info("检测到会话延续: sessionId={}", request.getSessionId());
        try {
            ConversationService.ConversationResponse conversationResp =
                    conversationService.continueConversation(request.getSessionId(), request.getUserInput());

            if (conversationResp == null ||
                conversationResp.getStatus() == ConversationSession.SessionStatus.CANCELLED) {
                return null; // Continue normal flow
            }

            if (conversationResp.isCompleted() && conversationResp.getIntentCode() != null) {
                conversationService.endConversation(request.getSessionId(), conversationResp.getIntentCode());
                request.setIntentCode(conversationResp.getIntentCode());
                request.setForceExecute(true);
                return executeWithExplicitIntent(factoryId, request, userId, userRole);
            }

            // Conversation continues
            IntentExecuteResponse.IntentExecuteResponseBuilder responseBuilder = IntentExecuteResponse.builder()
                    .intentRecognized(false)
                    .status("CONVERSATION_CONTINUE")
                    .message(conversationResp.getMessage())
                    .formattedText(conversationResp.getMessage())
                    .executedAt(LocalDateTime.now());

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("sessionId", conversationResp.getSessionId());
            metadata.put("currentRound", conversationResp.getCurrentRound());
            metadata.put("maxRounds", conversationResp.getMaxRounds());
            metadata.put("status", conversationResp.getStatus() != null ? conversationResp.getStatus().name() : null);

            if (conversationResp.getCandidates() != null && !conversationResp.getCandidates().isEmpty()) {
                List<IntentExecuteResponse.SuggestedAction> candidateActions = new ArrayList<>();
                for (ConversationService.CandidateInfo candidate : conversationResp.getCandidates()) {
                    Map<String, Object> params = new HashMap<>();
                    params.put("intentCode", candidate.getIntentCode());
                    params.put("sessionId", conversationResp.getSessionId());
                    params.put("forceExecute", true);
                    candidateActions.add(IntentExecuteResponse.SuggestedAction.builder()
                            .actionCode("SELECT_INTENT")
                            .actionName(candidate.getIntentName())
                            .description(candidate.getDescription() != null ? candidate.getDescription() :
                                    String.format("置信度: %.0f%%", candidate.getConfidence() * 100))
                            .endpoint("/api/mobile/" + factoryId + "/ai-intents/execute")
                            .parameters(params)
                            .build());
                }
                metadata.put("candidates", conversationResp.getCandidates());
                responseBuilder.suggestedActions(candidateActions);
            }
            responseBuilder.metadata(metadata);
            return responseBuilder.build();
        } catch (Exception e) {
            log.error("会话延续失败: sessionId={}, error={}", request.getSessionId(), e.getMessage(), e);
            return null;
        }
    }

    private IntentExecuteResponse handleEarlyQuestionTypeDetection(String factoryId, String userInput,
                                                                     IntentExecuteRequest request,
                                                                     Long userId, String userRole) {
        QuestionType earlyQuestionType = knowledgeBase.detectQuestionType(userInput);

        if (earlyQuestionType != QuestionType.GENERAL_QUESTION &&
            earlyQuestionType != QuestionType.CONVERSATIONAL) {
            return null;
        }

        // Analysis request check
        if (earlyQuestionType == QuestionType.GENERAL_QUESTION &&
            analysisRouterService.isAnalysisRequest(userInput, earlyQuestionType)) {
            return executeAnalysisFlow(factoryId, userInput, request, userId, userRole);
        }

        // Food knowledge intercept
        if (earlyQuestionType == QuestionType.GENERAL_QUESTION) {
            Optional<String> foodPhraseMatch = knowledgeBase.matchPhrase(userInput);
            if (foodPhraseMatch.isPresent() && "FOOD_KNOWLEDGE_QUERY".equals(foodPhraseMatch.get())) {
                IntentExecuteRequest foodRequest = IntentExecuteRequest.builder()
                        .userInput(userInput)
                        .intentCode("FOOD_KNOWLEDGE_QUERY")
                        .build();
                return execute(factoryId, foodRequest, userId, userRole);
            }
            if (knowledgeBase.hasEntityIntentConflict(userInput, "PROCESSING_GENERIC")) {
                IntentExecuteRequest foodRequest = IntentExecuteRequest.builder()
                        .userInput(userInput)
                        .intentCode("FOOD_KNOWLEDGE_QUERY")
                        .build();
                return execute(factoryId, foodRequest, userId, userRole);
            }
        }

        // Agentic RAG routing
        if (earlyQuestionType == QuestionType.GENERAL_QUESTION) {
            RAGRouteResult ragRouteResult = agenticRAGRouterService.route(userInput);

            switch (ragRouteResult.getConsultationType()) {
                case KNOWLEDGE_SEARCH:
                    String knowledgeResponse = agenticRAGRouterService.executeKnowledgeSearch(userInput, ragRouteResult);
                    return buildRAGResponse(knowledgeResponse, ragRouteResult, "KNOWLEDGE_SEARCH");
                case WEB_SEARCH:
                    String webSearchResponse = agenticRAGRouterService.executeWebSearch(userInput, ragRouteResult);
                    return buildRAGResponse(webSearchResponse, ragRouteResult, "WEB_SEARCH");
                case TRACEABILITY:
                    if (ragRouteResult.shouldConvertToIntent() && ragRouteResult.isHighConfidence()) {
                        if (ragRouteResult.isNeedsClarification()) {
                            return IntentExecuteResponse.builder()
                                    .intentRecognized(false)
                                    .status("NEED_CLARIFICATION")
                                    .message(ragRouteResult.getClarificationQuestion())
                                    .formattedText(ragRouteResult.getClarificationQuestion())
                                    .executedAt(LocalDateTime.now())
                                    .build();
                        }
                        Map<String, Object> traceabilityContext = new HashMap<>(ragRouteResult.getExtractedParams());
                        IntentExecuteRequest traceabilityRequest = IntentExecuteRequest.builder()
                                .userInput(userInput)
                                .intentCode(ragRouteResult.getSuggestedIntent())
                                .context(traceabilityContext)
                                .build();
                        return execute(factoryId, traceabilityRequest, userId, userRole);
                    }
                    break;
                case GENERAL:
                default:
                    break;
            }
        }

        // Sprint 12 P0 defense-in-depth — any phrase match WITH configured intent should win
        // over LLM conversational fallback. Without this, the LLM at line ~700 hijacks all
        // "帮我X / 怎么X / 哪个X" inputs that lack OUT_OF_DOMAIN/CONTEXT_CONTINUE wiring.
        // (Fix 1 at execute() #0.25 catches most cases; this is the safety net if a request
        // somehow bypasses the earlier check — e.g. via explicit GENERAL_QUESTION classification
        // path while phrase shortcut returned null due to factory-scoped intent absence.)
        String businessDomain = (factoryId != null && factoryId.startsWith("RES_")) ? "RESTAURANT" : "FACTORY";
        Optional<String> conversationalPhraseMatch = knowledgeBase.matchPhrase(userInput, businessDomain);
        if (conversationalPhraseMatch.isPresent()) {
            String matchedIntent = conversationalPhraseMatch.get();
            if ("OUT_OF_DOMAIN".equals(matchedIntent) || "CONTEXT_CONTINUE".equals(matchedIntent)) {
                IntentExecuteRequest interceptRequest = IntentExecuteRequest.builder()
                        .userInput(userInput)
                        .intentCode(matchedIntent)
                        .sessionId(request.getSessionId())
                        .build();
                return execute(factoryId, interceptRequest, userId, userRole);
            }
            // Sprint 12 P0 — any other phrase-matched intent that exists in this factory
            // should route to executeWithExplicitIntent (NOT fall through to LLM conversational).
            Optional<AIIntentConfig> existingIntent = aiIntentService.getIntentByCode(factoryId, matchedIntent);
            if (existingIntent.isPresent()) {
                log.info("[Sprint12-DefenseInDepth] handleEarlyQuestionTypeDetection phrase route: input='{}', intentCode={}",
                        userInput, matchedIntent);
                IntentExecuteRequest phraseRequest = IntentExecuteRequest.builder()
                        .userInput(userInput)
                        .intentCode(matchedIntent)
                        .sessionId(request.getSessionId())
                        .build();
                return executeWithExplicitIntent(factoryId, phraseRequest, userId, userRole);
            }
        }

        // Default: LLM conversational response
        String llmResponse = generateConversationalResponse(factoryId, userInput, earlyQuestionType,
                request.getEnableThinking(), request.getThinkingBudget());
        return IntentExecuteResponse.builder()
                .intentRecognized(false)
                .status("COMPLETED")
                .message(llmResponse)
                .formattedText(llmResponse)
                .executedAt(LocalDateTime.now())
                .build();
    }

    private void handleSemanticCache(String factoryId, String userInput, IntentExecuteRequest request) {
        try {
            SemanticCacheHit cacheHit = semanticCacheService.queryCache(factoryId, userInput);
            if (cacheHit.isHit()) {
                if (cacheHit.hasExecutionResult()) {
                    // Note: full cache hit with execution result is returned directly
                    // from the original execute() -- this method just primes the request context
                }
                IntentMatchResult cachedMatch = deserializeIntentResult(cacheHit.getIntentResult());
                if (cachedMatch != null && cachedMatch.hasMatch()) {
                    if (request.getContext() == null) {
                        request.setContext(new HashMap<>());
                    }
                    request.getContext().put("__cacheHit", true);
                    request.getContext().put("__cacheHitType",
                            cacheHit.getHitType() != null ? cacheHit.getHitType().name() : "SEMANTIC");
                }
            }
        } catch (Exception e) {
            log.warn("语义缓存查询失败: {}", e.getMessage());
        }
    }

    /**
     * Sprint 11 Round 7 (2026-05-23) — Pre-recognition phrase shortcut.
     *
     * <p>Returns a PHRASE_MATCH IntentMatchResult if userInput exactly matches a phrase in
     * {@link IntentKnowledgeBase}'s phrase mapping (business-domain aware). Returns null
     * if no phrase match — caller falls through to normal recognition pipeline.
     *
     * <p>This is a defense-in-depth safety net at the orchestrator layer. The inner pipeline
     * also has v33.1-EarlyPhrase (line 443 of IntentRecognitionPipelineServiceImpl) but prod
     * 2026-05-23 confirmed /execute can bypass it (root cause unclear). This orchestrator-
     * level check guarantees phrase-mapped intents always win.
     *
     * <p>Confidence 0.96 matches the inner EarlyPhrase confidence to avoid behavioral
     * divergence between /recognize and /execute endpoints.
     *
     * @param userInput user's raw input (will be normalized to lowercase + trimmed)
     * @param factoryId factory ID, used to resolve business domain (RESTAURANT vs FACTORY)
     * @return phrase-matched IntentMatchResult, or null if no phrase match
     */
    private IntentMatchResult tryOrchestratorPhraseShortcut(String userInput, String factoryId) {
        try {
            String normalized = userInput.toLowerCase().trim();
            String businessDomain = resolvePhraseBusinessDomain(factoryId);

            Optional<String> restaurantOpsMatch = matchRestaurantOpsIntent(normalized, businessDomain);
            if (restaurantOpsMatch.isPresent()) {
                String matchedCode = restaurantOpsMatch.get();
                Optional<AIIntentConfig> intentOpt = getIntentByCodeWithPlatformFallback(factoryId, matchedCode);
                if (intentOpt.isPresent()) {
                    AIIntentConfig phraseIntent = intentOpt.get();
                    log.info("[RestaurantOpsGoldRoute] Orchestrator deterministic route: input='{}', intentCode={}, domain={}",
                            userInput, matchedCode, businessDomain);
                    return IntentMatchResult.builder()
                            .userInput(userInput)
                            .bestMatch(phraseIntent)
                            .confidence(0.98)
                            .matchMethod(IntentMatchResult.MatchMethod.PHRASE_MATCH)
                            .isStrongSignal(true)
                            .requiresConfirmation(false)
                            .build();
                }
                log.debug("[RestaurantOpsGoldRoute] matched {} but intent not configured for factory {} — falling through",
                        matchedCode, factoryId);
            }

            Optional<String> phraseMatch = knowledgeBase.matchPhrase(normalized, businessDomain);
            if (phraseMatch.isEmpty()) {
                return null;
            }

            String matchedCode = phraseMatch.get();
            Optional<AIIntentConfig> intentOpt = getIntentByCodeWithPlatformFallback(factoryId, matchedCode);
            if (intentOpt.isEmpty()) {
                // Phrase mapped to a code that doesn't exist in this factory's intent config —
                // skip the shortcut and let the normal pipeline decide.
                log.debug("[Round7-EarlyPhrase] phrase matched {} but intent not configured for factory {} — falling through",
                        matchedCode, factoryId);
                return null;
            }

            AIIntentConfig phraseIntent = intentOpt.get();
            log.info("[Round7-EarlyPhrase] Orchestrator phrase shortcut: input='{}', intentCode={}, domain={}",
                    userInput, matchedCode, businessDomain);
            return IntentMatchResult.builder()
                    .userInput(userInput)
                    .bestMatch(phraseIntent)
                    .confidence(0.96)
                    .matchMethod(IntentMatchResult.MatchMethod.PHRASE_MATCH)
                    .build();
        } catch (Exception e) {
            log.debug("[Round7-EarlyPhrase] shortcut failed (fall through): {}", e.getMessage());
            return null;
        }
    }

    private IntentMatchResult tryRestaurantOpsPhraseShortcut(String userInput, String factoryId) {
        try {
            String normalized = userInput.toLowerCase().trim();
            String businessDomain = resolvePhraseBusinessDomain(factoryId);
            Optional<String> restaurantOpsMatch = matchRestaurantOpsIntent(normalized, businessDomain);
            if (restaurantOpsMatch.isEmpty()) {
                return null;
            }
            String matchedCode = restaurantOpsMatch.get();
            Optional<AIIntentConfig> intentOpt = getIntentByCodeWithPlatformFallback(factoryId, matchedCode);
            if (intentOpt.isEmpty()) {
                log.debug("[RestaurantOpsGoldRoute] matched {} but intent not configured for factory {} — falling through",
                        matchedCode, factoryId);
                return null;
            }
            return IntentMatchResult.builder()
                    .userInput(userInput)
                    .bestMatch(intentOpt.get())
                    .confidence(0.98)
                    .matchMethod(IntentMatchResult.MatchMethod.PHRASE_MATCH)
                    .isStrongSignal(true)
                    .requiresConfirmation(false)
                    .build();
        } catch (Exception e) {
            log.debug("[RestaurantOpsGoldRoute] shortcut failed (fall through): {}", e.getMessage());
            return null;
        }
    }

    private String resolvePhraseBusinessDomain(String factoryId) {
        // Prefer explicit restaurant IDs over metadata because DEMO_REST is the dedicated
        // passwordless restaurant demo and must not be downgraded by stale factory metadata.
        if (isRestaurantFactoryId(factoryId)) {
            return "RESTAURANT";
        }
        String businessDomain = resolveFactoryDomainSafe(factoryId);
        return businessDomain == null || businessDomain.isBlank() ? "FACTORY" : businessDomain;
    }

    private Optional<AIIntentConfig> getIntentByCodeWithPlatformFallback(String factoryId, String intentCode) {
        Optional<AIIntentConfig> intentOpt = aiIntentService.getIntentByCode(factoryId, intentCode);
        if (intentOpt.isPresent() || factoryId == null || factoryId.isBlank()) {
            return intentOpt;
        }
        return aiIntentService.getIntentByCode(intentCode);
    }

    Optional<String> matchRestaurantOpsIntent(String normalizedInput, String businessDomain) {
        if (!"RESTAURANT".equalsIgnoreCase(businessDomain) || normalizedInput == null || normalizedInput.isBlank()) {
            return Optional.empty();
        }
        String q = normalizedInput
                .replaceAll("\\s+", "")
                .replace('，', ',')
                .replace('？', '?');

        if (containsAny(q, "周末", "周中", "工作日")
                && containsAny(q, "对比", "比较", "差异", "营业额", "营收", "销售额", "销售")) {
            return Optional.of("RESTAURANT_WEEKDAY_WEEKEND");
        }

        if (containsAny(q, "月", "月份")
                && containsAny(q, "营收", "营业额", "销售额", "销售")
                && containsAny(q, "最高", "最多", "峰值", "为什么", "原因")) {
            return Optional.of("RESTAURANT_PEAK_MONTH");
        }

        if (containsAny(q, "慢销", "滞销", "卖得慢", "不好卖")
                && containsAny(q, "菜", "菜品", "产品", "处理", "优化", "哪些")) {
            return Optional.of("RESTAURANT_DISH_SLOW");
        }

        if (containsAny(q, "盘亏", "库存差异", "库存异常", "缺货", "短缺")
                || (containsAny(q, "库存", "食材") && containsAny(q, "亏", "异常", "不足", "短缺"))) {
            return Optional.of("RESTAURANT_OPS_STOCK_SHORTAGE");
        }

        if (containsAny(q, "损耗", "报损", "浪费", "损耗率")
                || (containsAny(q, "原因占比", "原因分布") && containsAny(q, "食材", "菜品", "门店"))) {
            return Optional.of("RESTAURANT_OPS_WASTAGE_TOP");
        }

        if (containsAny(q, "领料", "用料", "食材用得多", "原料用量", "领用")
                || (containsAny(q, "食材", "原料") && containsAny(q, "趋势", "用得多", "用量", "成本趋势"))) {
            return Optional.of("RESTAURANT_OPS_REQUISITION_TREND");
        }

        if (containsAny(q, "门店毛利", "门店利润", "各店毛利")
                || (containsAny(q, "门店", "店", "分店", "店铺", "哪家")
                && containsAny(q, "毛利", "毛利率", "利润", "赚钱", "最赚", "净赚"))) {
            return Optional.of("RESTAURANT_OPS_STORE_MARGIN");
        }

        if (containsAny(q, "毛利", "利润率", "毛利率")
                && containsAny(q, "菜", "菜品", "产品", "哪些", "排名", "最高", "最低")) {
            return Optional.of("RESTAURANT_OPS_GROSS_MARGIN");
        }

        if (containsAny(q, "配方成本", "菜品成本", "单品成本", "成本结构")
                || (containsAny(q, "菜", "菜品") && containsAny(q, "成本", "核算"))) {
            return Optional.of("RESTAURANT_OPS_RECIPE_COST");
        }

        if (containsAny(q, "营收趋势", "销售趋势", "营业额趋势", "订单趋势", "客单价趋势")) {
            return Optional.of("RESTAURANT_OPS_TREND_ANALYSIS");
        }

        if (containsAny(q, "营收", "营业额", "销售额", "销售", "客单价", "订单")
                && containsAny(q, "查询", "查一下", "看一下", "看看", "本周", "这周", "今天", "本月",
                        "表现", "怎么样", "情况", "整体", "总", "汇总", "多少", "分析")) {
            return Optional.of("RESTAURANT_OPS_SALES_SUMMARY");
        }

        return Optional.empty();
    }

    private boolean isRestaurantFactoryId(String factoryId) {
        return factoryId != null && (factoryId.startsWith("RES_") || "DEMO_REST".equalsIgnoreCase(factoryId));
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

    // ==================== 分析流程 ====================

    private IntentExecuteResponse executeAnalysisFlow(String factoryId, String userInput,
                                                       IntentExecuteRequest request,
                                                       Long userId, String userRole) {
        try {
            AnalysisTopic topic = analysisRouterService.detectAnalysisTopic(userInput);
            AnalysisContext analysisContext = AnalysisContext.builder()
                    .userInput(userInput).topic(topic).factoryId(factoryId)
                    .userId(userId).userRole(userRole).sessionId(request.getSessionId())
                    .enableThinking(request.getEnableThinking()).thinkingBudget(request.getThinkingBudget())
                    .build();

            ProcessingMode processingMode = complexityRouter.route(userInput, analysisContext);
            AnalysisResult analysisResult;

            if (processingMode == ProcessingMode.MULTI_AGENT || processingMode == ProcessingMode.DEEP_REASONING) {
                analysisResult = agentOrchestrator.executeCollaborativeAnalysis(analysisContext);
            } else {
                analysisResult = analysisRouterService.executeAnalysis(analysisContext);
            }

            if (analysisResult.isSuccess()) {
                String status = analysisResult.isRequiresHumanReview() ? "ANALYSIS_PENDING_REVIEW" : "ANALYSIS_COMPLETED";
                return IntentExecuteResponse.builder()
                        .intentRecognized(false)
                        .status(status)
                        .message(analysisResult.getFormattedAnalysis())
                        .formattedText(analysisResult.getFormattedAnalysis())
                        .metadata(Map.of("analysisTopic", topic.name(), "processingMode", processingMode.name()))
                        .executedAt(LocalDateTime.now())
                        .build();
            }

            // Fallback
            String fallback = generateConversationalResponse(factoryId, userInput,
                    QuestionType.GENERAL_QUESTION, request.getEnableThinking(), request.getThinkingBudget());
            return IntentExecuteResponse.builder()
                    .intentRecognized(false).status("COMPLETED")
                    .message(fallback).formattedText(fallback).executedAt(LocalDateTime.now()).build();

        } catch (Exception e) {
            log.error("分析流程异常: {}", e.getMessage(), e);
            String fallback = generateConversationalResponse(factoryId, userInput,
                    QuestionType.GENERAL_QUESTION, request.getEnableThinking(), request.getThinkingBudget());
            return IntentExecuteResponse.builder()
                    .intentRecognized(false).status("COMPLETED")
                    .message(fallback).formattedText(fallback).executedAt(LocalDateTime.now()).build();
        }
    }

    // ==================== LLM 对话 ====================

    String generateConversationalResponse(String factoryId, String userInput, QuestionType questionType,
                                                   Boolean enableThinking, Integer thinkingBudget) {
        String systemPrompt;
        if (questionType == QuestionType.GENERAL_QUESTION) {
            String factoryAnalysisContext = getPrecomputedAnalysisContext(factoryId);
            if (factoryAnalysisContext != null && !factoryAnalysisContext.isEmpty()) {
                systemPrompt = """
                    你是白垩纪AI Agent的智能助手。用户正在询问一个关于生产管理、质量控制或成本优化的咨询问题。
                    **重要**: 下面是该工厂的最新运营分析报告：
                    ---
                    %s
                    ---
                    请基于数据提供针对性建议，不超过500字，使用中文。
                    """.formatted(factoryAnalysisContext);
            } else {
                systemPrompt = "你是白垩纪AI Agent的智能助手。请提供专业、实用的生产管理建议，不超过300字，使用中文。";
            }
        } else {
            systemPrompt = "你是白垩纪AI Agent的智能助手。友好回应，不超过100字，使用中文。";
        }

        try {
            boolean useThinkingMode = Boolean.TRUE.equals(enableThinking)
                    && questionType == QuestionType.GENERAL_QUESTION
                    && dashScopeConfig.isThinkingEnabled();
            int budget = (thinkingBudget != null && thinkingBudget >= 10 && thinkingBudget <= 100) ? thinkingBudget : 30;

            String response;
            if (useThinkingMode) {
                ChatCompletionResponse thinkingResponse = dashScopeClient.chatWithThinking(systemPrompt, userInput, budget);
                response = thinkingResponse.getContent();
                if (thinkingResponse.hasError()) {
                    response = dashScopeClient.chat(systemPrompt, userInput);
                }
            } else {
                response = dashScopeClient.chat(systemPrompt, userInput);
            }
            return response;
        } catch (Exception e) {
            log.error("LLM 对话回复生成失败: {}", e.getMessage(), e);
            return questionType == QuestionType.GENERAL_QUESTION
                    ? "抱歉，我暂时无法回答您的问题。您可以尝试询问具体的系统操作。"
                    : "您好！有什么可以帮您的吗？";
        }
    }

    // ==================== 响应构建 ====================

    private IntentExecuteResponse buildNoPermissionResponse(AIIntentConfig intent) {
        String msg = "您没有权限执行此操作。需要角色: " + intent.getRequiredRoles();
        return IntentExecuteResponse.builder()
                .intentRecognized(true).intentCode(intent.getIntentCode())
                .intentName(intent.getIntentName()).intentCategory(intent.getIntentCategory())
                .sensitivityLevel(intent.getSensitivityLevel())
                .status("NO_PERMISSION").message(msg).formattedText(msg)
                .executedAt(LocalDateTime.now()).build();
    }

    private IntentExecuteResponse buildApprovalResponse(AIIntentConfig intent) {
        String msg = "此操作需要审批确认。审批请求已提交。";
        return IntentExecuteResponse.builder()
                .intentRecognized(true).intentCode(intent.getIntentCode())
                .intentName(intent.getIntentName()).intentCategory(intent.getIntentCategory())
                .sensitivityLevel(intent.getSensitivityLevel())
                .status("PENDING_APPROVAL").message(msg).formattedText(msg)
                .requiresApproval(true).approvalChainId(intent.getApprovalChainId())
                .executedAt(LocalDateTime.now()).build();
    }

    private IntentExecuteResponse buildDroolsFailureResponse(AIIntentConfig intent, ValidationResult validationResult) {
        String msg = "业务规则验证未通过: " + validationResult.getViolationsSummary();
        return IntentExecuteResponse.builder()
                .intentRecognized(true).intentCode(intent.getIntentCode())
                .intentName(intent.getIntentName()).intentCategory(intent.getIntentCategory())
                .status("VALIDATION_FAILED").message(msg).formattedText(msg)
                .validationViolations(validationResult.getViolations())
                .recommendations(validationResult.getRecommendations())
                .executedAt(LocalDateTime.now()).build();
    }

    /**
     * Sprint 13 #305 业态门控 — gate domain-exclusive intents to the matching factory.type.
     *
     * <p>Cretas serves 2 customer types: RESTAURANT (餐厅, e.g. RES_3101_009) and FACTORY
     * (制造厂, e.g. F006 六膳门卤味). A RESTAURANT-exclusive intent (business_type=RESTAURANT,
     * e.g. {@code RESTAURANT_ECONOMICS_ANALYSIS}) triggered on a FACTORY-type factory has no
     * data to operate on → previously returned a misleading half-broken "数据不可用". This gate
     * returns an honest "本厂非餐厅业态" message + a domain-appropriate next-action instead
     * (per {@code .claude/rules/fool-proof-design.md} Rule 5: dead-end → next action).
     *
     * <p>Only DOMAIN-EXCLUSIVE intents gate; {@code COMMON}/null business_type are universal
     * and always pass (anti-goal: 不挡通用 intent). Fail-soft: any domain-lookup error → pass.
     *
     * @return honest empty-state response on业态 mismatch; {@code null} to proceed with execution.
     */
    private IntentExecuteResponse checkBusinessTypeGate(String factoryId, AIIntentConfig intent) {
        // Delegates to the shared BusinessTypeGate so the main execute() flow, the explicit-intent
        // flow, and SseStreamingService all gate identically (Sprint 13 #305).
        return businessTypeGate.check(factoryId, intent).orElse(null);
    }

    /**
     * W1b: clarification response for a negation VETO_READ in the execution layer.
     * Nothing is recognized or executed (intentRecognized=false) — the orchestrator's
     * contains-based phrase shortcut is NOT consulted, so a negated read like "别给我看订单"
     * can no longer execute the embedded "看订单" intent.
     */
    IntentExecuteResponse buildNegationVetoClarificationResponse(String userInput) {
        final String msg = "您是要取消这次操作吗?需要我帮您查询或处理什么?";
        log.info("[W1b] orchestrator negation veto clarification for input='{}'", userInput);
        return IntentExecuteResponse.builder()
                .intentRecognized(false)
                .status("NEED_CLARIFICATION")
                .message(msg)
                .formattedText(msg)
                .executedAt(LocalDateTime.now())
                .build();
    }

    boolean requiresStoreReferenceClarification(
            IntentExecuteRequest request, IntentMatchResult matchResult, AIIntentConfig intent) {
        if (intent == null || !"RESTAURANT_STORE_REVENUE_RANK".equals(intent.getIntentCode())) {
            return false;
        }
        String input = request != null ? request.getUserInput() : null;
        if (input == null || !STORE_REFERENCE_PATTERN.matcher(input).find()) {
            return false;
        }
        if (matchResult == null || matchResult.getPreprocessedQuery() == null
                || matchResult.getPreprocessedQuery().getResolvedReferences() == null) {
            return true;
        }
        return matchResult.getPreprocessedQuery().getResolvedReferences().values().stream()
                .noneMatch(ref -> ref != null
                        && ref.getEntityType() != null
                        && "STORE".equalsIgnoreCase(ref.getEntityType()));
    }

    boolean shouldBypassEarlyPhraseShortcutForStoreReference(String userInput) {
        return userInput != null && STORE_REFERENCE_PATTERN.matcher(userInput).find();
    }

    IntentExecuteResponse buildStoreReferenceClarificationResponse(IntentExecuteRequest request) {
        final String msg = "请问您指的是哪家店？";
        return IntentExecuteResponse.builder()
                .intentRecognized(false)
                .status("NEED_CLARIFICATION")
                .message(msg)
                .formattedText(msg)
                .sessionId(request != null ? request.getSessionId() : null)
                .executedAt(LocalDateTime.now())
                .build();
    }

    boolean requiresDishReferenceClarification(
            IntentExecuteRequest request, IntentMatchResult matchResult, AIIntentConfig intent) {
        if (intent == null || !"RESTAURANT_BESTSELLER_QUERY".equals(intent.getIntentCode())) {
            return false;
        }
        String input = request != null ? request.getUserInput() : null;
        if (input == null || !DISH_REFERENCE_PATTERN.matcher(input).find()) {
            return false;
        }
        if (matchResult == null || matchResult.getPreprocessedQuery() == null
                || matchResult.getPreprocessedQuery().getResolvedReferences() == null) {
            return true;
        }
        return matchResult.getPreprocessedQuery().getResolvedReferences().values().stream()
                .noneMatch(ref -> ref != null
                        && ref.getEntityType() != null
                        && "DISH".equalsIgnoreCase(ref.getEntityType()));
    }

    boolean shouldBypassEarlyPhraseShortcutForDishReference(String userInput) {
        return userInput != null && DISH_REFERENCE_PATTERN.matcher(userInput).find();
    }

    IntentExecuteResponse buildDishReferenceClarificationResponse(IntentExecuteRequest request) {
        final String msg = "请问您指的是哪道菜？";
        return IntentExecuteResponse.builder()
                .intentRecognized(false)
                .status("NEED_CLARIFICATION")
                .message(msg)
                .formattedText(msg)
                .sessionId(request != null ? request.getSessionId() : null)
                .executedAt(LocalDateTime.now())
                .build();
    }

    IntentExecuteResponse buildClarificationResponse(IntentMatchResult matchResult, String factoryId) {
        AIIntentConfig matchedIntent = matchResult.getBestMatch();
        List<IntentExecuteResponse.SuggestedAction> candidateActions = buildCandidateActions(matchResult, factoryId);

        // Sprint 12: enrich clarification message with inline candidate intent names so the
        // formattedText itself carries the choices (not just suggestedActions UI buttons).
        // Audit gate requires NEED_CLARIFICATION to include specific 2+ choice question
        // visible in formattedText / message — bare "请确认您想要执行的操作" fails.
        String clarificationMessage = matchResult.getClarificationQuestion();
        if (clarificationMessage == null || clarificationMessage.isEmpty()) {
            clarificationMessage = buildClarificationWithChoices(candidateActions, factoryId);
        }

        Map<String, Object> metadata = new HashMap<>();
        if (matchResult.getSessionId() != null && !matchResult.getSessionId().isEmpty()) {
            metadata.put("sessionId", matchResult.getSessionId());
            metadata.put("needMoreInfo", true);
            if (matchResult.getConversationMessage() != null) {
                metadata.put("conversationMessage", matchResult.getConversationMessage());
            }
        }

        IntentExecuteResponse.IntentExecuteResponseBuilder builder = IntentExecuteResponse.builder()
                .intentRecognized(true)
                .intentCode(matchedIntent.getIntentCode())
                .intentName(matchedIntent.getIntentName())
                .intentCategory(matchedIntent.getIntentCategory())
                .status("NEED_CLARIFICATION")
                .message(clarificationMessage)
                .formattedText(clarificationMessage)
                .confidence(matchResult.getConfidence())
                .matchMethod(matchResult.getMatchMethod() != null ? matchResult.getMatchMethod().name() : null)
                .suggestedActions(candidateActions)
                .executedAt(LocalDateTime.now());

        if (!metadata.isEmpty()) builder.metadata(metadata);
        return builder.build();
    }

    private IntentExecuteResponse buildNoMatchResponse(IntentMatchResult matchResult,
                                                       String factoryId,
                                                       IntentExecuteRequest request,
                                                       Long userId) {
        if (request != null && shouldRouteRestaurantOwnerAction(factoryId, request.getUserInput(), request.getContext())) {
            log.info("[restaurant-owner-action] route from no-match fallback: factoryId={}, input={}",
                    factoryId, request.getUserInput());
            return executeRestaurantOwnerActionChat(factoryId, request, userId);
        }

        Map<String, Object> metadata = new HashMap<>();
        if (matchResult.getSessionId() != null && !matchResult.getSessionId().isEmpty()) {
            metadata.put("sessionId", matchResult.getSessionId());
            metadata.put("needMoreInfo", true);
            if (matchResult.getConversationMessage() != null) {
                metadata.put("conversationMessage", matchResult.getConversationMessage());
            }
        }

        if (matchResult.getTopCandidates() != null && !matchResult.getTopCandidates().isEmpty()) {
            List<IntentExecuteResponse.SuggestedAction> candidateActions = buildCandidateActions(matchResult, factoryId);
            // Sprint 12: enrich weak-signal message with inline candidate intent names.
            String weakSignalMsg = "我不太确定您想执行什么操作。" + buildChoicesLine(candidateActions)
                    + "请回复对应序号, 或更详细地描述您的需求 (例如指定时段 / 物料 / 批次)。";
            IntentExecuteResponse.IntentExecuteResponseBuilder builder = IntentExecuteResponse.builder()
                    .intentRecognized(false).status("NEED_CLARIFICATION")
                    .message(weakSignalMsg).formattedText(weakSignalMsg)
                    .suggestedActions(candidateActions).executedAt(LocalDateTime.now());
            if (!metadata.isEmpty()) builder.metadata(metadata);
            return builder.build();
        }

        List<IntentExecuteResponse.SuggestedAction> defaultSuggestions = buildDefaultSuggestions(factoryId);
        // Sprint 12 Task 5: enrich default no-match message with inline choices + domain hint
        // so audit content_len ≥80 + chinese_run ≥20 + actionable for user.
        String message = matchResult.getConversationMessage() != null && !matchResult.getConversationMessage().isEmpty()
                ? matchResult.getConversationMessage()
                : "我没有理解您的意图。" + buildChoicesLine(defaultSuggestions)
                        + "请回复对应序号, 或更详细描述需求 (例如指定客户 / 物料 / 批次 / 月份 / 工序)。";

        IntentExecuteResponse.IntentExecuteResponseBuilder builder = IntentExecuteResponse.builder()
                .intentRecognized(false).status("NEED_CLARIFICATION")
                .message(message).formattedText(message)
                .executedAt(LocalDateTime.now()).suggestedActions(defaultSuggestions);
        if (!metadata.isEmpty()) builder.metadata(metadata);
        return builder.build();
    }

    private IntentExecuteResponse buildValidationFailureResponse(String factoryId, String userInput,
                                                                   LlmSchemaValidationException e) {
        String truncatedInput = userInput != null && userInput.length() > 30 ? userInput.substring(0, 30) + "..." : userInput;
        String clarificationMessage = switch (e.getFailureType()) {
            case PARSE_ERROR -> String.format("AI 无法正确理解您的请求「%s」，请重新描述您的需求。", truncatedInput);
            case UNKNOWN_INTENT_CODE -> "AI 识别的操作类型无法执行，请从常用操作中选择。";
            case INVALID_CONFIDENCE -> String.format("AI 对您的请求「%s」理解不够确定，请更详细地描述。", truncatedInput);
            default -> String.format("AI 无法准确理解您的意图「%s」，请重新描述。", truncatedInput);
        };

        List<IntentExecuteResponse.SuggestedAction> suggestedActions = new ArrayList<>();
        suggestedActions.add(IntentExecuteResponse.SuggestedAction.builder()
                .actionCode("REPHRASE").actionName("重新描述您的需求")
                .description("请尝试用不同的方式描述").build());
        suggestedActions.add(IntentExecuteResponse.SuggestedAction.builder()
                .actionCode("SHOW_INTENTS").actionName("从常用操作中选择")
                .endpoint("/api/mobile/" + factoryId + "/ai-intents").build());

        return IntentExecuteResponse.builder()
                .intentRecognized(false).status("VALIDATION_FAILED")
                .message(clarificationMessage).suggestedActions(suggestedActions)
                .metadata(Map.of("validationFailureType", e.getFailureType().name(),
                        "requiresDoubleCheck", true))
                .executedAt(LocalDateTime.now()).build();
    }

    private IntentExecuteResponse buildRAGResponse(String responseContent, RAGRouteResult ragRouteResult, String routeType) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("routeType", routeType);
        metadata.put("consultationType", ragRouteResult.getConsultationType().name());
        metadata.put("confidence", ragRouteResult.getConfidence());
        String status = ragRouteResult.isHighConfidence() ? "RAG_COMPLETED" : "RAG_COMPLETED_LOW_CONFIDENCE";
        return IntentExecuteResponse.builder()
                .intentRecognized(false).status(status).message(responseContent)
                .metadata(metadata).executedAt(LocalDateTime.now()).build();
    }

    // ==================== 工具方法 ====================

    /**
     * Sprint 12 Task 2 — build clarification message with inline choices so
     * formattedText carries the 2+ choice question (not just suggestedActions UI metadata).
     *
     * <p>Audit close-gate row "NEED_CLARIFICATION contains specific 2+ choices" requires
     * the user-facing text to enumerate the candidate intents inline.
     */
    private String buildClarificationWithChoices(List<IntentExecuteResponse.SuggestedAction> candidateActions,
                                                  String factoryId) {
        StringBuilder sb = new StringBuilder();
        sb.append("您的请求可能匹配多个操作, 请选择您实际想做的:");
        sb.append(buildChoicesLine(ensureMinChoices(candidateActions, 2, factoryId)));
        sb.append("回复对应序号, 或更详细描述需求 (例如指定时段 / 物料 / 批次 / 客户)。");
        return sb.toString();
    }

    /**
     * Sprint 12 close-gate row "NEED_CLARIFICATION returns specific 2-or-3-choice question"
     * requires ≥2 data choices in the user-visible text. When the candidate set has <2
     * data actions (filtering REPHRASE / SHOW_INTENTS), pad with default common queries.
     */
    List<IntentExecuteResponse.SuggestedAction> ensureMinChoices(
            List<IntentExecuteResponse.SuggestedAction> actions, int minCount, String factoryId) {
        List<IntentExecuteResponse.SuggestedAction> dataActions = new ArrayList<>();
        if (actions != null) {
            for (IntentExecuteResponse.SuggestedAction a : actions) {
                if (a == null) continue;
                String code = a.getActionCode();
                if ("REPHRASE".equals(code) || "SHOW_INTENTS".equals(code)) continue;
                if (a.getActionName() == null || a.getActionName().isBlank()) continue;
                dataActions.add(a);
            }
        }
        if (dataActions.size() >= minCount) return actions;
        // C1: pad with a FACTORY-AWARE pool of guaranteed-bound intents — never pad a restaurant
        // clarification with manufacturing intents (查询原料库存 / 查询生产批次).
        String factoryDomain = resolveFactoryDomainSafe(factoryId);
        IntentExecuteResponse.SuggestedAction[] padCandidates =
                "RESTAURANT".equalsIgnoreCase(factoryDomain)
                ? new IntentExecuteResponse.SuggestedAction[] {
                        IntentExecuteResponse.SuggestedAction.builder()
                                .actionCode("RESTAURANT_BESTSELLER_QUERY").actionName("查询畅销菜品").build(),
                        IntentExecuteResponse.SuggestedAction.builder()
                                .actionCode("RESTAURANT_STORE_REVENUE_RANK").actionName("查询门店营收排行").build(),
                        IntentExecuteResponse.SuggestedAction.builder()
                                .actionCode("RESTAURANT_ORDER_STATISTICS").actionName("查询订单统计").build(),
                }
                : new IntentExecuteResponse.SuggestedAction[] {
                        IntentExecuteResponse.SuggestedAction.builder()
                                .actionCode("MATERIAL_BATCH_QUERY").actionName("查询原料库存").build(),
                        IntentExecuteResponse.SuggestedAction.builder()
                                .actionCode("PROCESSING_BATCH_LIST").actionName("查询生产批次").build(),
                        IntentExecuteResponse.SuggestedAction.builder()
                                .actionCode("DAILY_CUSTOMER_FOLLOWUP").actionName("查询今日待跟进客户").build(),
                };
        java.util.Set<String> existingNames = new java.util.HashSet<>();
        for (IntentExecuteResponse.SuggestedAction a : dataActions) existingNames.add(a.getActionName());
        List<IntentExecuteResponse.SuggestedAction> padded = new ArrayList<>(actions != null ? actions : new ArrayList<>());
        for (IntentExecuteResponse.SuggestedAction p : padCandidates) {
            if (dataActions.size() >= minCount) break;
            if (existingNames.contains(p.getActionName())) continue;
            padded.add(0, p);  // prepend padding before REPHRASE/SHOW_INTENTS sentinels
            dataActions.add(p);
        }
        return padded;
    }

    /**
     * Render top candidate intents as a "1. X 2. Y 3. Z" choice line.
     * Skips REPHRASE / SHOW_INTENTS placeholders (they're UI affordances, not data choices).
     */
    private String buildChoicesLine(List<IntentExecuteResponse.SuggestedAction> candidateActions) {
        if (candidateActions == null || candidateActions.isEmpty()) {
            return "\n暂无候选操作可建议。\n";
        }
        StringBuilder sb = new StringBuilder("\n");
        int idx = 1;
        for (IntentExecuteResponse.SuggestedAction action : candidateActions) {
            if (action == null) continue;
            String code = action.getActionCode();
            if ("REPHRASE".equals(code) || "SHOW_INTENTS".equals(code)) continue;
            String name = action.getActionName();
            if (name == null || name.isBlank()) continue;
            sb.append(idx++).append(". ").append(name);
            String desc = action.getDescription();
            if (desc != null && !desc.isBlank() && !desc.startsWith("置信度")) {
                sb.append(" — ").append(desc);
            }
            sb.append("\n");
            if (idx > 3) break;  // cap at top 3 data choices
        }
        if (idx == 1) {
            // No data choices found, only UI affordances
            sb.append("暂无候选操作可建议。\n");
        }
        return sb.toString();
    }

    // ==================== C1 业态过滤 helpers (restaurant-chat-qa) ====================

    /**
     * Resolve a factory's business domain ("RESTAURANT" / "FACTORY"), fail-soft.
     * Returns {@code null} if resolution throws — callers treat null as "unknown" and
     * keep the original (unfiltered) behavior so the clarification path never crashes.
     */
    String resolveFactoryDomainSafe(String factoryId) {
        try {
            return configService.resolveBusinessDomain(factoryId);
        } catch (Exception e) {
            log.warn("[C1业态过滤] resolveBusinessDomain failed for factoryId={} — skip filter: {}",
                    factoryId, e.getMessage());
            return null;
        }
    }

    /**
     * Is a candidate intent (by code) compatible with the factory's business domain?
     * Looks up the intent's business_type via configService and delegates to the single
     * source of truth {@link com.cretas.aims.ai.tool.BusinessTypeScope#isCompatible}.
     * Fail-soft: any lookup error → {@code true} (keep the candidate, don't crash).
     *
     * @param factoryDomain resolved factory domain ("RESTAURANT"/"FACTORY"); when null the
     *                      method returns true (no filtering when domain is unknown).
     */
    boolean isCandidateCompatible(String factoryId, String factoryDomain, String intentCode) {
        if (factoryDomain == null || intentCode == null) {
            return true; // unknown domain or missing code → don't filter
        }
        try {
            String intentBiz = configService.getIntentByCode(factoryId, intentCode)
                    .map(AIIntentConfig::getBusinessType)
                    .orElse(null);
            return com.cretas.aims.ai.tool.BusinessTypeScope.isCompatible(intentBiz, factoryDomain);
        } catch (Exception e) {
            log.warn("[C1业态过滤] business_type lookup failed for intent={} factory={} — keep candidate: {}",
                    intentCode, factoryId, e.getMessage());
            return true; // fail-soft
        }
    }

    boolean shouldRouteRestaurantOwnerAction(String factoryId, String userInput, Map<String, Object> context) {
        if (userInput == null || userInput.isBlank()) {
            return false;
        }
        String factoryDomain = resolveFactoryDomainSafe(factoryId);
        if (!isRestaurantOwnerActionFactory(factoryId, factoryDomain)) {
            return false;
        }
        if (hasOwnerActionContinuationContext(context)) {
            return true;
        }
        if (matchesOwnerActionKeywordHeuristic(userInput)) {
            return true;
        }
        if (RESTAURANT_OWNER_ACTION_FORCE_PATTERN.matcher(userInput).find()) {
            return true;
        }
        return false;
    }

    boolean matchesOwnerActionKeywordHeuristic(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return false;
        }
        String input = userInput.toLowerCase(Locale.ROOT);
        boolean hasTopic = containsAny(input,
                "\u8001\u677f", "\u5e97\u957f", "\u533a\u57df\u7ecf\u7406",
                "\u684c\u578b", "\u684c\u5b50", "\u684c\u6570", "\u4e8c\u4eba\u684c", "\u4e24\u4eba\u684c",
                "\u56db\u4eba\u684c", "\u7ffb\u53f0", "\u6392\u961f", "\u7b49\u4f4d",
                "\u6392\u73ed", "\u4eba\u6548", "\u5458\u5de5", "\u5de5\u65f6", "\u524d\u5385", "\u540e\u53a8",
                "\u5907\u83dc", "\u5907\u8d27", "\u5e93\u5b58", "\u635f\u8017", "bom", "\u6bdb\u5229",
                "\u6210\u672c", "\u8bc4\u8bba", "\u5dee\u8bc4", "\u5927\u4f17\u70b9\u8bc4",
                "\u7f8e\u56e2", "\u6296\u97f3", "\u5ba2\u6d41", "\u753b\u50cf", "\u5546\u5708",
                "\u5546\u573a", "\u6d3b\u52a8", "\u5929\u6c14", "\u5957\u9910", "\u5c0f\u5957\u9910",
                "\u83dc\u54c1", "\u8425\u6536", "\u6536\u5165", "\u5ba2\u5355",
                "\u65e5\u5747", "\u5de5\u4f5c\u65e5", "\u540c\u5546\u5708", "\u8fde\u9501",
                "\u95e8\u5e97", "\u6392\u540d", "\u505a\u6cd5", "\u4f4e\u4ef7\u503c",
                "\u4f4e\u4ef7\u503c\u83dc", "\u54ea\u4e9b\u83dc", "\u4e3b\u63a8",
                "\u5355\u54c1", "\u52a0\u8d2d");
        boolean hasDecision = containsAny(input,
                "\u600e\u4e48\u8c03", "\u600e\u4e48\u6392", "\u600e\u4e48\u6539",
                "\u600e\u4e48\u63d0\u9ad8", "\u600e\u4e48\u63d0\u5347", "\u600e\u4e48\u4f18\u5316",
                "\u600e\u4e48\u505a", "\u600e\u4e48\u5b89\u6392", "\u5982\u4f55\u63d0\u9ad8",
                "\u5982\u4f55\u4f18\u5316", "\u5e94\u8be5\u600e\u4e48", "\u8981\u4e0d\u8981",
                "\u5148\u505a", "\u5148\u6539", "\u5148\u8bad\u7ec3", "\u5b89\u6392",
                "\u5904\u7406", "\u89e3\u51b3", "\u5efa\u8bae", "\u76f4\u63a5\u5efa\u8bae",
                "\u52a8\u4f5c", "\u505a\u4ec0\u4e48", "\u76ef\u4ec0\u4e48", "\u8c03\u6574",
                "\u63a8\u8350", "\u4e3b\u63a8", "\u5e2e\u6211\u7b97", "\u7b97\u4e00\u4e2a",
                "\u6709\u4ec0\u4e48\u529e\u6cd5", "\u5e94\u8be5\u590d\u5236",
                "\u590d\u5236\u54ea\u5bb6\u5e97", "\u590d\u5236", "\u5b66\u4e60",
                "\u95ee\u9898\u5728", "\u6267\u884c", "\u503c\u5f97\u4e3b\u63a8",
                "\u8981\u6392\u9664", "\u6392\u9664", "\u600e\u4e48\u5224\u65ad",
                "\u62c9\u52a8");
        return hasTopic && hasDecision;
    }

    boolean isRestaurantOwnerActionFactory(String factoryId, String factoryDomain) {
        if ("RESTAURANT".equalsIgnoreCase(factoryDomain)) {
            return true;
        }
        if (factoryId == null || factoryId.isBlank()) {
            return false;
        }
        String normalized = factoryId.trim().toUpperCase(Locale.ROOT);
        return "DEMO_REST".equals(normalized)
                || normalized.startsWith("RES_")
                || normalized.startsWith("REST_");
    }

    boolean hasRestaurantOwnerActionSignal(String factoryId, Map<String, Object> context) {
        if (isRestaurantOwnerActionFactory(factoryId, null)) {
            return true;
        }
        if (context == null || context.isEmpty()) {
            return false;
        }
        String storeName = stringValue(context.get("storeName"));
        String subSector = stringValue(context.get("subSector"));
        String businessType = stringValue(context.get("businessType"));
        String demoTenant = stringValue(context.get("tenant"));
        return isNonBlank(storeName)
                || isNonBlank(subSector)
                || "restaurant".equalsIgnoreCase(businessType)
                || "rest".equalsIgnoreCase(demoTenant);
    }

    private boolean isNonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private boolean hasOwnerActionContinuationContext(Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return false;
        }
        String sessionId = stringValue(context.get("ownerActionSessionId"));
        if (sessionId != null && !sessionId.isBlank()) {
            return true;
        }
        String scenario = stringValue(context.get("ownerActionScenario"));
        return scenario != null && !scenario.isBlank() && !"auto".equalsIgnoreCase(scenario);
    }

    @SuppressWarnings("unchecked")
    private IntentExecuteResponse executeRestaurantOwnerActionChat(String factoryId,
                                                                   IntentExecuteRequest request,
                                                                   Long userId) {
        Map<String, Object> context = request.getContext() == null
                ? Collections.emptyMap()
                : request.getContext();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("factory_id", factoryId);
        body.put("factoryId", factoryId);
        body.put("message", request.getUserInput());
        putIfPresent(body, "sessionId", stringValue(context.get("ownerActionSessionId")));
        putIfPresent(body, "demoScenario", stringValue(context.get("ownerActionScenario")));
        body.put("storeName", stringValueOrDefault(context.get("storeName"), "青花椒上海示范店"));
        body.put("subSector", stringValueOrDefault(context.get("subSector"), "中餐/川味酸菜鱼"));
        body.put("period", stringValueOrDefault(context.get("period"), "this_week"));

        Optional<ToolExecutor> advisorTool = toolRegistry.getExecutor("restaurant_owner_action_advisor");
        if (advisorTool.isEmpty()) {
            return buildRestaurantOwnerActionError("老板动作分析工具未配置，请稍后重试。", userId);
        }

        Map<String, Object> raw;
        try {
            ToolCall toolCall = ToolCall.of(
                    "owner-action-" + System.nanoTime(),
                    "restaurant_owner_action_advisor",
                    objectMapper.writeValueAsString(body));
            Map<String, Object> toolContext = new LinkedHashMap<>();
            toolContext.put("factoryId", factoryId);
            toolContext.put("userId", userId != null ? userId : 0L);
            String toolResponseJson = advisorTool.get().execute(toolCall, toolContext);
            raw = objectMapper.readValue(toolResponseJson, Map.class);
        } catch (Exception ex) {
            log.warn("餐饮老板动作建议 Tool 执行失败: {}", ex.getMessage(), ex);
            return buildRestaurantOwnerActionError("老板动作分析暂时不可用，请稍后重试。", userId);
        }

        boolean ok = Boolean.TRUE.equals(raw.get("success"));
        Object dataObj = raw.get("data");
        if (!ok || !(dataObj instanceof Map<?, ?> dataMapRaw)) {
            String message = stringValueOrDefault(raw.get("message"), "老板动作分析暂时没有返回，请稍后重试。");
            return buildRestaurantOwnerActionError(message, userId);
        }

        Map<String, Object> data = new LinkedHashMap<>((Map<String, Object>) dataMapRaw);
        normalizeOwnerActionSource(data);
        data.put("suggestedFollowups", normalizeOwnerActionFollowups(
                firstNonNull(data.get("suggestedFollowups"), data.get("followUpSuggestions")),
                stringValue(data.get("scenario"))));

        String answer = firstNonBlank(
                stringValue(data.get("responseText")),
                stringValue(data.get("answer")),
                "已生成老板决策建议。");
        String sessionId = stringValue(data.get("sessionId"));

        IntentExecuteResponse response = IntentExecuteResponse.builder()
                .intentRecognized(true)
                .intentCode("RESTAURANT_OWNER_ACTION_CHAT")
                .intentName("餐饮老板动作建议")
                .intentCategory("RESTAURANT")
                .sensitivityLevel("LOW")
                .confidence(0.99)
                .matchMethod("JAVA_OWNER_ACTION_TOOL_ROUTE")
                .status("SUCCESS")
                .message(answer)
                .formattedText(answer)
                .resultData(data)
                .executedAt(LocalDateTime.now())
                .sessionId(sessionId)
                .metadata(ownerActionMetadata(userId))
                .build();
        recordOwnerActionIntentMatch(factoryId, request, userId, response);
        return response;
    }

    private void recordOwnerActionIntentMatch(String factoryId,
                                              IntentExecuteRequest request,
                                              Long userId,
                                              IntentExecuteResponse response) {
        if (intentMatchRecordRepository == null || request == null || response == null
                || request.getUserInput() == null || request.getUserInput().isBlank()) {
            return;
        }
        try {
            String sessionId = firstNonBlank(
                    response.getSessionId(),
                    request.getSessionId(),
                    stringValue(request.getContext() != null ? request.getContext().get("ownerActionSessionId") : null));
            IntentMatchRecord record = new IntentMatchRecord();
            record.setFactoryId(factoryId != null ? factoryId : "DEFAULT");
            record.setUserId(userId != null ? userId : 0L);
            record.setSessionId(sessionId);
            record.setUserInput(request.getUserInput());
            record.setNormalizedInput(request.getUserInput().toLowerCase(Locale.ROOT).trim());
            record.setMatchedIntentCode("RESTAURANT_OWNER_ACTION_CHAT");
            record.setMatchedIntentName("餐饮老板动作建议");
            record.setMatchedIntentCategory("RESTAURANT");
            record.setConfidenceScore(BigDecimal.valueOf(0.99));
            record.setMatchMethod(IntentMatchRecord.MatchMethod.PHRASE_MATCH);
            record.setLlmCalled(false);
            record.setExecutionStatus(IntentMatchRecord.ExecutionStatus.EXECUTED);
            record.setExecutionResult(truncateForRecord(response.getMessage(), 1000));
            record.setExecutedAt(LocalDateTime.now());
            intentMatchRecordRepository.save(record);
        } catch (Exception e) {
            log.warn("Record restaurant owner-action intent match failed: {}", e.getMessage());
        }
    }

    private String truncateForRecord(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private IntentExecuteResponse buildRestaurantOwnerActionError(String message, Long userId) {
        return IntentExecuteResponse.builder()
                .intentRecognized(true)
                .intentCode("RESTAURANT_OWNER_ACTION_CHAT")
                .intentName("餐饮老板动作建议")
                .intentCategory("RESTAURANT")
                .status("ERROR")
                .message(message)
                .formattedText(message)
                .executedAt(LocalDateTime.now())
                .metadata(ownerActionMetadata(userId))
                .build();
    }

    private Map<String, Object> ownerActionMetadata(Long userId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "restaurant_owner_action");
        if (userId != null) {
            metadata.put("userId", userId);
        }
        return metadata;
    }

    private void normalizeOwnerActionSource(Map<String, Object> data) {
        Object source = data.get("source");
        if ("restaurant_owner_action_advisor".equals(source)) {
            data.put("advisorSource", "restaurant_owner_action_advisor");
        } else {
            data.putIfAbsent("advisorSource", "restaurant_owner_action_advisor");
        }
        data.put("source", "restaurant_owner_action");
    }

    private void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String stringValueOrDefault(Object value, String fallback) {
        String actual = stringValue(value);
        return actual == null || actual.isBlank() ? fallback : actual;
    }

    private String firstNonBlank(String first, String second, String fallback) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return fallback;
    }

    private Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    private List<Map<String, Object>> normalizeOwnerActionFollowups(Object followUps, String scenario) {
        if (!(followUps instanceof List<?> items)) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Object item : items) {
            String question;
            String label;
            String itemScenario = scenario;
            if (item instanceof Map<?, ?> followupMap) {
                question = firstNonBlank(
                        stringValue(followupMap.get("question")),
                        stringValue(followupMap.get("text")),
                        stringValue(followupMap.get("label")));
                label = firstNonBlank(stringValue(followupMap.get("label")), question, question);
                itemScenario = firstNonBlank(stringValue(followupMap.get("ownerActionScenario")), scenario, scenario);
            } else {
                question = stringValue(item);
                label = question;
            }
            if (question == null || question.isBlank()) {
                continue;
            }
            Map<String, Object> followup = new LinkedHashMap<>();
            followup.put("label", label != null && label.length() > 18 ? label.substring(0, 18) + "..." : label);
            followup.put("question", question);
            if (itemScenario != null && !itemScenario.isBlank()) {
                followup.put("ownerActionScenario", itemScenario);
            }
            normalized.add(followup);
            if (normalized.size() >= 4) {
                break;
            }
        }
        return normalized;
    }

    private List<IntentExecuteResponse.SuggestedAction> buildCandidateActions(IntentMatchResult matchResult, String factoryId) {
        List<IntentExecuteResponse.SuggestedAction> actions = new ArrayList<>();
        // C1: drop candidates whose owning intent's business_type is incompatible with this
        // factory's domain (e.g. a RESTAURANT tenant must not see 查询原料库存 manufacturing options).
        String factoryDomain = resolveFactoryDomainSafe(factoryId);
        if (matchResult.getTopCandidates() != null) {
            for (IntentMatchResult.CandidateIntent candidate : matchResult.getTopCandidates()) {
                if (actions.size() >= 3) break;
                if (!isCandidateCompatible(factoryId, factoryDomain, candidate.getIntentCode())) {
                    continue; // business-type mismatch — skip noise
                }
                Map<String, Object> params = new HashMap<>();
                params.put("intentCode", candidate.getIntentCode());
                params.put("forceExecute", true);
                actions.add(IntentExecuteResponse.SuggestedAction.builder()
                        .actionCode("SELECT_INTENT").actionName(candidate.getIntentName())
                        .description(candidate.getDescription() != null ? candidate.getDescription() :
                                String.format("置信度: %.0f%%", candidate.getConfidence() * 100))
                        .endpoint("/api/mobile/" + factoryId + "/ai-intents/execute")
                        .parameters(params).build());
            }
        }
        // C1: if filtering left ZERO data candidates (all incompatible with this domain), fall
        // back to factory-aware defaults so the user still gets actionable options (never empty).
        if (actions.isEmpty()) {
            for (IntentExecuteResponse.SuggestedAction d : buildDefaultSuggestions(factoryId)) {
                String code = d.getActionCode();
                if ("REPHRASE".equals(code) || "SHOW_INTENTS".equals(code)) continue;
                actions.add(d);
            }
        }
        actions.add(IntentExecuteResponse.SuggestedAction.builder()
                .actionCode("REPHRASE").actionName("重新描述").build());
        actions.add(IntentExecuteResponse.SuggestedAction.builder()
                .actionCode("SHOW_INTENTS").actionName("查看所有可用操作")
                .endpoint("/api/mobile/" + factoryId + "/ai-intents").build());
        return actions;
    }

    /**
     * Factory-aware default clarification choices. A RESTAURANT tenant gets restaurant
     * defaults (畅销菜品 / 门店营收排行 / 订单统计); all other tenants keep the original
     * manufacturing defaults (查询原料库存 / 查询生产批次). Package-private for unit testing.
     */
    List<IntentExecuteResponse.SuggestedAction> buildDefaultSuggestions(String factoryId) {
        List<IntentExecuteResponse.SuggestedAction> actions = new ArrayList<>();
        String factoryDomain = resolveFactoryDomainSafe(factoryId);
        if ("RESTAURANT".equalsIgnoreCase(factoryDomain)) {
            actions.add(IntentExecuteResponse.SuggestedAction.builder()
                    .actionCode("RESTAURANT_BESTSELLER_QUERY").actionName("查询畅销菜品")
                    .endpoint("/api/mobile/" + factoryId + "/ai-intents/execute").build());
            actions.add(IntentExecuteResponse.SuggestedAction.builder()
                    .actionCode("RESTAURANT_STORE_REVENUE_RANK").actionName("查询门店营收排行")
                    .endpoint("/api/mobile/" + factoryId + "/ai-intents/execute").build());
            actions.add(IntentExecuteResponse.SuggestedAction.builder()
                    .actionCode("RESTAURANT_ORDER_STATISTICS").actionName("查询订单统计")
                    .endpoint("/api/mobile/" + factoryId + "/ai-intents/execute").build());
        } else {
            actions.add(IntentExecuteResponse.SuggestedAction.builder()
                    .actionCode("MATERIAL_BATCH_QUERY").actionName("查询原料库存")
                    .endpoint("/api/mobile/" + factoryId + "/material-batches").build());
            actions.add(IntentExecuteResponse.SuggestedAction.builder()
                    .actionCode("PROCESSING_BATCH_LIST").actionName("查询生产批次")
                    .endpoint("/api/mobile/" + factoryId + "/processing/batches").build());
        }
        actions.add(IntentExecuteResponse.SuggestedAction.builder()
                .actionCode("REPHRASE").actionName("重新描述").build());
        actions.add(IntentExecuteResponse.SuggestedAction.builder()
                .actionCode("SHOW_INTENTS").actionName("查看所有可用操作")
                .endpoint("/api/mobile/" + factoryId + "/ai-intents").build());
        return actions;
    }

    private void applyResultFormatting(IntentExecuteResponse response) {
        String status = response.getStatus();
        boolean isSuccessStatus = "SUCCESS".equals(status) || "COMPLETED".equals(status);
        if (resultFormatterService != null && isSuccessStatus && response.getResultData() != null) {
            try {
                resultFormatterService.formatAndSet(response);
            } catch (Exception e) {
                log.debug("结果格式化失败（非致命）: {}", e.getMessage());
            }
        }
    }

    private void applyFormattedTextFallback(IntentExecuteResponse response) {
        String currentFT = response.getFormattedText();
        String msg = response.getMessage();
        boolean ftMissing = currentFT == null;
        boolean ftGeneric = currentFT != null && GENERIC_SHORT_REPLIES.contains(currentFT.trim());
        boolean ftShort = currentFT != null && currentFT.length() < 15;
        if ((ftMissing || ftGeneric || ftShort) && msg != null && msg.length() >= 20
                && msg.length() > (currentFT != null ? currentFT.length() : 0)) {
            response.setFormattedText(msg);
        } else if (ftMissing && msg != null && msg.length() >= 5) {
            response.setFormattedText(msg);
        }
        // Ultimate fallback
        if (response.getFormattedText() == null && response.getMessage() != null && !response.getMessage().isEmpty()) {
            response.setFormattedText(response.getMessage());
        }

        // Sprint 9 P0.2 (2026-05-22) — Terminal LLM-summarize gate for Workdesk / Skill orchestration
        // outputs. Detects underscore-prefixed metadata leaks (_toolCount / _executionOrder),
        // raw JSON dumps (5989-char sales-owner case), and bare templates ("包含 N 项数据指标").
        // Pipes through DashScopeClient.chatFast() for Chinese natural-language summary.
        // Opt-out via cretas.ai.workdesk-summarizer.enabled=false.
        if (workdeskOutputSummarizer != null) {
            try {
                workdeskOutputSummarizer.apply(response);
            } catch (Exception e) {
                log.warn("WorkdeskOutputSummarizer 应用失败 (non-blocking): {}", e.getMessage());
            }
        }
    }

    private void processResponseCaching(String factoryId, IntentExecuteRequest request,
                                         IntentMatchResult matchResult, IntentExecuteResponse response) {
        try {
            if (request.getContext() != null) {
                Object cacheHit = request.getContext().get("__cacheHit");
                if (Boolean.TRUE.equals(cacheHit)) {
                    response.setFromCache(true);
                    Object hitType = request.getContext().get("__cacheHitType");
                    response.setCacheHitType(hitType != null ? hitType.toString() : "SEMANTIC");
                    request.getContext().remove("__cacheHit");
                    request.getContext().remove("__cacheHitType");
                    return;
                }
            }
            if ("COMPLETED".equals(response.getStatus()) && request.getUserInput() != null) {
                semanticCacheService.cacheResult(factoryId, request.getUserInput(), matchResult, response);
            }
        } catch (Exception e) {
            log.warn("处理响应缓存失败: {}", e.getMessage());
        }
    }

    // ==================== 对话记忆 ====================

    /**
     * X1 Part B 修复 —— 在短路 / 显式意图路径持久化对话记忆。供下一轮 X1 续接继承 lastIntentCode。
     * 仅当 sessionId 非空时生效(无 session 的独立查询 parity/golden 完全不受影响)。fail-soft。
     * 包级可见便于单测。
     */
    void persistConversationMemoryForExplicitIntent(String factoryId, IntentExecuteRequest request,
                                                    IntentExecuteResponse response,
                                                    AIIntentConfig intent, Long userId) {
        if (request.getSessionId() == null || request.getSessionId().isEmpty()) {
            return;
        }
        try {
            conversationMemoryService.getOrCreateContext(factoryId, userId, request.getSessionId());
            IntentMatchResult syntheticMatch = IntentMatchResult.builder()
                    .bestMatch(intent)
                    .build();
            updateConversationMemory(request.getSessionId(), request, response,
                    syntheticMatch, factoryId, userId);
        } catch (Exception e) {
            log.warn("X1 显式意图路径对话记忆持久化失败: sessionId={}, error={}", request.getSessionId(), e.getMessage());
        }
    }

    private void updateConversationMemory(String sessionId, IntentExecuteRequest request,
                                           IntentExecuteResponse response, IntentMatchResult intentResult,
                                           String factoryId, Long userId) {
        try {
            String userInput = request.getUserInput();
            String assistantMessage = response.getMessage() != null ? response.getMessage() : "执行完成";
            conversationMemoryService.addMessage(sessionId, ConversationMessage.user(userInput));
            conversationMemoryService.addMessage(sessionId, ConversationMessage.assistant(assistantMessage));
            // Extract entities from response data
            extractAndUpdateEntitySlots(sessionId, response, intentResult);
            if (intentResult != null && intentResult.getBestMatch() != null) {
                conversationMemoryService.updateLastIntent(sessionId, intentResult.getBestMatch().getIntentCode());
            }
        } catch (Exception e) {
            log.warn("Failed to update conversation memory: sessionId={}, error={}", sessionId, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void extractAndUpdateEntitySlots(String sessionId, IntentExecuteResponse response,
                                              IntentMatchResult intentResult) {
        if (response.getAffectedEntities() != null) {
            for (IntentExecuteResponse.AffectedEntity entity : response.getAffectedEntities()) {
                try {
                    com.cretas.aims.dto.conversation.EntitySlot.SlotType slotType = mapEntityTypeToSlotType(entity.getEntityType());
                    if (slotType != null) {
                        var slot = com.cretas.aims.dto.conversation.EntitySlot.builder()
                                .type(slotType).id(entity.getEntityId()).name(entity.getEntityName()).build();
                        conversationMemoryService.updateEntitySlot(sessionId, slotType, slot);
                    }
                } catch (Exception e) { log.debug("Failed to update entity slot: {}", e.getMessage()); }
            }
        }
        // Fallback entity extraction from resultData
        Object data = response.getResultData();
        if (data == null) return;
        try {
            List<Map<String, Object>> items = null;
            if (data instanceof Map) {
                Map<String, Object> dataMap = (Map<String, Object>) data;
                extractTopStoreSlot(sessionId, dataMap.get("top_store"));
                extractTopDishSlot(sessionId, dataMap.get("top_dish"));
                if (dataMap.containsKey("content") && dataMap.get("content") instanceof List)
                    items = (List<Map<String, Object>>) dataMap.get("content");
            } else if (data instanceof List) {
                items = (List<Map<String, Object>>) data;
            }
            if (items == null || items.isEmpty()) return;
            Object firstItemObj = items.get(0);
            if (firstItemObj == null) return;
            Map<String, Object> firstItem;
            if (firstItemObj instanceof Map) {
                firstItem = (Map<String, Object>) firstItemObj;
            } else {
                firstItem = objectMapper.convertValue(firstItemObj,
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            }
            extractSlot(sessionId, firstItem, "BATCH", "id", "batchId", "batch_id");
            extractSlot(sessionId, firstItem, "SUPPLIER", "supplierId", "supplier_id");
            extractSlot(sessionId, firstItem, "PRODUCT", "productTypeId", "productId", "materialTypeId");
        } catch (Exception e) { log.debug("Entity extraction failed: {}", e.getMessage()); }
    }

    @SuppressWarnings("unchecked")
    private void extractTopStoreSlot(String sessionId, Object topStoreObj) {
        if (topStoreObj == null) return;
        try {
            Map<String, Object> topStore;
            if (topStoreObj instanceof Map) {
                topStore = (Map<String, Object>) topStoreObj;
            } else {
                topStore = objectMapper.convertValue(topStoreObj,
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            }
            String id = getStringValue(topStore, "store_id", "id");
            String name = getStringValue(topStore, "门店", "store_name", "name");
            if (id == null || name == null) return;
            var slot = com.cretas.aims.dto.conversation.EntitySlot.store(id, name);
            conversationMemoryService.updateEntitySlot(
                    sessionId,
                    com.cretas.aims.dto.conversation.EntitySlot.SlotType.STORE,
                    slot);
        } catch (Exception e) {
            log.debug("STORE entity extraction failed: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void extractTopDishSlot(String sessionId, Object topDishObj) {
        if (topDishObj == null) return;
        try {
            Map<String, Object> topDish;
            if (topDishObj instanceof Map) {
                topDish = (Map<String, Object>) topDishObj;
            } else {
                topDish = objectMapper.convertValue(topDishObj,
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            }
            String id = getStringValue(topDish, "dish_id", "product_id", "id");
            String name = getStringValue(topDish, "菜品", "dish_name", "product_name", "name");
            if (id == null || name == null) return;
            var slot = com.cretas.aims.dto.conversation.EntitySlot.dish(id, name);
            conversationMemoryService.updateEntitySlot(
                    sessionId,
                    com.cretas.aims.dto.conversation.EntitySlot.SlotType.DISH,
                    slot);
        } catch (Exception e) {
            log.debug("DISH entity extraction failed: {}", e.getMessage());
        }
    }

    private void extractSlot(String sessionId, Map<String, Object> item, String entityType, String... idKeys) {
        String id = getStringValue(item, idKeys);
        if (id != null) {
            var slotType = mapEntityTypeToSlotType(entityType);
            if (slotType != null) {
                var slot = com.cretas.aims.dto.conversation.EntitySlot.builder()
                        .type(slotType).id(id).build();
                conversationMemoryService.updateEntitySlot(sessionId, slotType, slot);
            }
        }
    }

    private String getStringValue(Map<String, Object> map, String... keys) {
        for (String key : keys) { Object v = map.get(key); if (v != null) return v.toString(); }
        return null;
    }

    private com.cretas.aims.dto.conversation.EntitySlot.SlotType mapEntityTypeToSlotType(String entityType) {
        if (entityType == null) return null;
        return switch (entityType.toUpperCase()) {
            case "BATCH", "MATERIAL_BATCH" -> com.cretas.aims.dto.conversation.EntitySlot.SlotType.BATCH;
            case "SUPPLIER" -> com.cretas.aims.dto.conversation.EntitySlot.SlotType.SUPPLIER;
            case "STORE" -> com.cretas.aims.dto.conversation.EntitySlot.SlotType.STORE;
            case "DISH" -> com.cretas.aims.dto.conversation.EntitySlot.SlotType.DISH;
            case "CUSTOMER" -> com.cretas.aims.dto.conversation.EntitySlot.SlotType.CUSTOMER;
            case "PRODUCT", "PRODUCT_TYPE" -> com.cretas.aims.dto.conversation.EntitySlot.SlotType.PRODUCT;
            case "WAREHOUSE", "LOCATION" -> com.cretas.aims.dto.conversation.EntitySlot.SlotType.WAREHOUSE;
            default -> null;
        };
    }

    // ==================== Drools ====================

    private ValidationResult validateWithDrools(String factoryId, AIIntentConfig intent,
                                                 IntentExecuteRequest request, Long userId, String userRole) {
        try {
            IntentValidationFact fact = IntentValidationFact.builder()
                    .intentCategory(intent.getIntentCategory())
                    .operation(extractOperationType(intent))
                    .timestamp(LocalDateTime.now())
                    .targetFactoryId(factoryId).currentFactoryId(factoryId)
                    .userRole(userRole)
                    .forceExecute(Boolean.TRUE.equals(request.getForceExecute()))
                    .batchSize(extractBatchSize(request))
                    .userId(userId).username(extractUsername(request))
                    .intentCode(intent.getIntentCode())
                    .build();

            ValidationResult result = ruleEngineService.executeRulesWithAudit(
                    factoryId, "intentValidation", "INTENT", intent.getIntentCode(),
                    userId, extractUsername(request), userRole, fact);

            return result != null ? result : ValidationResult.builder().valid(true).build();
        } catch (Exception e) {
            log.error("Drools规则验证异常: intentCode={}, error={}", intent.getIntentCode(), e.getMessage(), e);
            ValidationResult failedResult = ValidationResult.builder().valid(false).build();
            failedResult.addViolation("规则验证异常", "Drools规则引擎执行异常: " + e.getMessage(), "HIGH");
            return failedResult;
        }
    }

    private boolean shouldRunDroolsValidation(AIIntentConfig intent) {
        if (!validationEnabled || intent == null) {
            return false;
        }
        return !"QUERY".equals(extractOperationType(intent));
    }

    private String extractOperationType(AIIntentConfig intent) {
        String code = intent.getIntentCode();
        if (code.contains("CREATE") || code.contains("ADD")) return "CREATE";
        if (code.contains("UPDATE") || code.contains("MODIFY")) return "UPDATE";
        if (code.contains("DELETE") || code.contains("REMOVE")) return "DELETE";
        return "QUERY";
    }

    private int extractBatchSize(IntentExecuteRequest request) {
        if (request.getContext() == null) return 1;
        Object bs = request.getContext().get("batchSize");
        if (bs instanceof Integer) return (Integer) bs;
        if (bs instanceof String) { try { return Integer.parseInt((String) bs); } catch (NumberFormatException e) { return 1; } }
        return 1;
    }

    private String extractUsername(IntentExecuteRequest request) {
        if (request.getContext() == null) return "unknown";
        Object u = request.getContext().get("username");
        return u != null ? u.toString() : "unknown";
    }

    // ==================== NEED_MORE_INFO enrichment ====================

    private IntentExecuteResponse enrichWithClarificationQuestions(IntentExecuteResponse response,
                                                                    IntentExecuteRequest request,
                                                                    AIIntentConfig intent,
                                                                    String factoryId, Long userId) {
        try {
            List<String> missingParams = parseMissingParameters(response.getMessage());
            if (missingParams.isEmpty()) return response;

            List<String> clarificationQuestions = missingParams.stream()
                    .map(p -> generateQuestionForParameter(p))
                    .collect(Collectors.toList());
            if (missingParams.size() > 1) {
                clarificationQuestions.add("请提供以上所有必需信息，以便我能够帮您完成操作。");
            }

            List<ConversationService.RequiredParameter> requiredParameters = missingParams.stream()
                    .map(name -> ConversationService.RequiredParameter.builder()
                            .name(name).label(name).type("string").collected(false).build())
                    .collect(Collectors.toList());

            String sessionId = null;
            if (userId != null) {
                try {
                    var conversationResp = conversationService.startParameterCollection(
                            factoryId, userId, intent.getIntentCode(), intent.getIntentName(),
                            requiredParameters, clarificationQuestions);
                    if (conversationResp != null) sessionId = conversationResp.getSessionId();
                } catch (Exception e) { log.warn("Failed to create parameter collection session: {}", e.getMessage()); }
            }

            // Build parameter options
            List<IntentExecuteResponse.SuggestedAction> suggestedActions = new ArrayList<>();
            if (productTypeRepository != null) {
                for (String param : missingParams) {
                    if (param.toLowerCase().contains("productid")) {
                        try {
                            var products = productTypeRepository.findByFactoryId(factoryId);
                            if (products != null) {
                                int limit = Math.min(products.size(), 10);
                                for (int i = 0; i < limit; i++) {
                                    var p = products.get(i);
                                    suggestedActions.add(IntentExecuteResponse.SuggestedAction.builder()
                                            .actionCode("SELECT_PARAM_productId_" + p.getId())
                                            .actionName(p.getName())
                                            .description(p.getCode() != null ? "编码: " + p.getCode() : null)
                                            .build());
                                }
                            }
                        } catch (Exception e) { log.warn("查询产品列表失败: {}", e.getMessage()); }
                    }
                }
            }

            return IntentExecuteResponse.builder()
                    .intentRecognized(response.getIntentRecognized())
                    .intentCode(response.getIntentCode())
                    .intentName(response.getIntentName())
                    .intentCategory(response.getIntentCategory())
                    .status(response.getStatus())
                    .message("需要更多信息来完成此操作")
                    .clarificationQuestions(clarificationQuestions)
                    .suggestedActions(suggestedActions.isEmpty() ? null : suggestedActions)
                    .sessionId(sessionId)
                    .executedAt(response.getExecutedAt())
                    .build();
        } catch (Exception e) {
            log.error("Failed to enrich clarification questions: {}", e.getMessage(), e);
            return response;
        }
    }

    private List<String> parseMissingParameters(String message) {
        List<String> params = new ArrayList<>();
        if (message == null) return params;
        Pattern pattern = Pattern.compile("\\(([^)]+)\\)");
        Matcher matcher = pattern.matcher(message);
        while (matcher.find()) { String p = matcher.group(1).trim(); if (!p.isEmpty()) params.add(p); }
        return params;
    }

    private String generateQuestionForParameter(String paramName) {
        String lp = paramName.toLowerCase();
        if (lp.contains("batchid")) return "请问您要操作哪个批次？请提供批次ID。";
        if (lp.contains("quantity")) return "请问数量是多少？";
        if (lp.contains("date")) return "请问日期是？格式：yyyy-MM-dd";
        return "请提供 " + paramName + "。";
    }

    // ==================== Utility ====================

    private String getPrecomputedAnalysisContext(String factoryId) {
        if (factoryId == null || factoryId.isEmpty()) return null;
        try {
            LocalDateTime now = LocalDateTime.now();
            StringBuilder context = new StringBuilder();
            analysisResultRepository.findFirstByFactoryIdAndReportTypeAndExpiresAtAfterOrderByCreatedAtDesc(
                    factoryId, "daily", now).ifPresent(r -> context.append("## 日报\n").append(r.getAnalysisText()).append("\n"));
            analysisResultRepository.findFirstByFactoryIdAndReportTypeAndExpiresAtAfterOrderByCreatedAtDesc(
                    factoryId, "weekly", now).ifPresent(r -> context.append("## 周报\n").append(r.getAnalysisText()).append("\n"));
            if (context.length() == 0) {
                analysisResultRepository.findFirstByFactoryIdAndReportTypeAndExpiresAtAfterOrderByCreatedAtDesc(
                        factoryId, "monthly", now).ifPresent(r -> context.append("## 月报\n").append(r.getAnalysisText()).append("\n"));
            }
            return context.length() > 0 ? context.toString() : null;
        } catch (Exception e) { return null; }
    }

    private IntentMatchResult deserializeIntentResult(String json) {
        if (json == null || json.isEmpty()) return null;
        try { return objectMapper.readValue(json, IntentMatchResult.class); }
        catch (JsonProcessingException e) { return null; }
    }

    private void logBranchStats() {
        long total = branchToolDirect.get() + branchSkill.get() + branchDynamic.get() + branchNoMatch.get();
        if (total > 0 && total % 50 == 0) {
            log.info("[Branch:Stats] total={}, ToolDirect={}%, Skill={}%, Dynamic={}%, NoMatch={}%",
                    total,
                    branchToolDirect.get() * 100 / total,
                    branchSkill.get() * 100 / total,
                    branchDynamic.get() * 100 / total,
                    branchNoMatch.get() * 100 / total);
        }
    }
}

package com.cretas.aims.service.impl;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.dto.ChatCompletionRequest;
import com.cretas.aims.ai.dto.ChatCompletionResponse;
import com.cretas.aims.ai.dto.ChatMessage;
import com.cretas.aims.ai.dto.Tool;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.config.ArenaRLConfig;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.config.IntentMatchingConfig;
import com.cretas.aims.dto.arena.TournamentResult;
import com.cretas.aims.service.arena.ArenaRLTournamentService;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.dto.intent.IntentMatchResult.CandidateIntent;
import com.cretas.aims.dto.intent.IntentMatchResult.MatchMethod;
import com.cretas.aims.dto.intent.MultiIntentResult;
import com.cretas.aims.dto.intent.LlmIntentClassifyResponse;
import com.cretas.aims.dto.intent.LlmIntentClassifyResponse.CandidateResponse;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.intent.IntentOptimizationSuggestion;
import com.cretas.aims.entity.intent.IntentOptimizationSuggestion.SuggestionStatus;
import com.cretas.aims.exception.LlmSchemaValidationException;
import com.cretas.aims.exception.LlmSchemaValidationException.ValidationFailureType;
import com.cretas.aims.repository.IntentOptimizationSuggestionRepository;
import com.cretas.aims.repository.config.AIIntentConfigRepository;
import com.cretas.aims.service.ConversationService;
import com.cretas.aims.service.LlmIntentFallbackClient;
import com.cretas.aims.service.RAGRetrievalService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

// v12.1 Phase 2 imports
import com.cretas.aims.service.ConfidenceCalibrationService;

/**
 * LLM 意图识别 Fallback 客户端实现
 *
 * 支持两种模式：
 * 1. Python 服务调用 (legacy) - 调用 /api/ai/intent/classify 端点
 * 2. DashScope 直接调用 (new) - 直接调用阿里云 DashScope API
 *
 * 通过配置开关 cretas.ai.dashscope.migration.intent-classify 控制
 *
 * 优化：使用 OkHttp 连接池提升性能
 * - 连接复用减少 TCP 握手开销
 * - 连接池管理提升并发性能
 *
 * @author Cretas Team
 * @version 2.0.0
 * @since 2026-01-02
 */
@Slf4j
@Service
public class LlmIntentFallbackClientImpl implements LlmIntentFallbackClient {

    /**
     * D8v2: ThreadLocal 存储原始用户输入，用于 Tool Calling 领域检测。
     * 预处理器可能大幅简化输入（如"帮我查一下本月各产线的良品率对比"→"查产线"），
     * 导致领域关键词丢失。此 ThreadLocal 在 classifyIntent(originalInput) 重载中设置，
     * 在 getFilteredToolsForQuery() 中读取。
     */
    private static final ThreadLocal<String> originalInputHolder = new ThreadLocal<>();
    /** Canvas V2: factoryId for per-factory tool filtering in getFilteredToolsForQuery */
    private static final ThreadLocal<String> factoryIdHolder = new ThreadLocal<>();
    /**
     * P4 Task 4.4: recent conversation turns for LLM prompt enrichment.
     * Set by {@link com.cretas.aims.service.impl.AIIntentServiceImpl} before
     * delegating to the pipeline so the turns flow down to buildIntentClassifyPrompt.
     */
    static final ThreadLocal<java.util.List<com.cretas.aims.entity.conversation.ConversationTurn>>
            conversationTurnsHolder = new ThreadLocal<>();

    @Value("${cretas.ai.service.url:http://localhost:8083}")
    private String aiServiceUrl;

    @Value("${cretas.ai.intent.auto-create.enabled:true}")
    private boolean autoCreateIntentEnabled;

    @Value("${cretas.ai.intent.auto-create.min-confidence:0.6}")
    private double autoCreateMinConfidence;

    /**
     * 工厂级意图自动审批开关
     * 当开启时，工厂级别的 CREATE_INTENT 建议会自动通过并创建意图，无需手动审批
     * 平台级意图（factoryId 为 PLATFORM 或 null）仍需手动审批
     */
    @Value("${cretas.ai.intent.auto-create.factory-auto-approve:true}")
    private boolean factoryAutoApproveEnabled;

    // ==================== 多意图识别配置 (模块C) ====================

    @Value("${cretas.ai.multi-intent.enabled:true}")
    private boolean multiIntentEnabled;

    @Value("${cretas.ai.multi-intent.max-intents:3}")
    private int maxIntents;

    @Value("${cretas.ai.multi-intent.user-confirm-threshold:0.7}")
    private double userConfirmThreshold;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final OkHttpClient httpClient;

    private final DashScopeClient dashScopeClient;

    private final DashScopeConfig dashScopeConfig;

    private final IntentOptimizationSuggestionRepository suggestionRepository;

    private final AIIntentConfigRepository intentConfigRepository;

    private final ConversationService conversationService;

    private final ToolRegistry toolRegistry;

    private final IntentMatchingConfig intentMatchingConfig;

    private final ArenaRLTournamentService arenaRLTournamentService;

    private final ArenaRLConfig arenaRLConfig;

    // v11.4 RAG: RAG 检索服务，用于动态 Few-Shot 示例
    private final RAGRetrievalService ragRetrievalService;

    // D8v2: 意图知识库，用于领域检测 → 工具过滤
    private final IntentKnowledgeBase intentKnowledgeBase;

    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    // ==================== Category 定义（两阶段分类用） ====================

    /**
     * Category 描述映射表
     * 用于两阶段分类的第一阶段（粗分类）
     *
     * 总计 16 个 Category，约 113 个意图
     */
    private static final Map<String, String> CATEGORY_DESCRIPTIONS = Map.ofEntries(
            Map.entry("MATERIAL", "原材料管理 - 库存查询、批次领用、过期预警、低库存告警"),
            Map.entry("QUALITY", "质量检测 - 质检任务执行、结果查询、不合格品处置"),
            Map.entry("SHIPMENT", "出货物流 - 创建出货单、发货确认、物流跟踪、收货确认"),
            Map.entry("CRM", "客户供应商 - 客户管理、供应商评价、联系人维护"),
            Map.entry("HR", "人事考勤 - 打卡签到、考勤查询、请假申请"),
            Map.entry("ALERT", "告警管理 - 告警查询、告警确认、告警解决、告警诊断"),
            Map.entry("SCALE", "电子秤设备 - 秤设备列表、称重操作、校准管理"),
            Map.entry("REPORT", "报表统计 - 生产报表、质量报表、库存报表、综合看板"),
            Map.entry("DATA_OP", "数据操作 - 批量更新、数据修改、数据导入导出"),
            Map.entry("SYSTEM", "系统配置 - 调度模式、功能开关、系统参数"),
            Map.entry("CONFIG", "业务配置 - 转化率设置、规则配置、阈值调整"),
            Map.entry("USER", "用户管理 - 创建用户、角色分配、权限管理"),
            Map.entry("FORM", "表单生成 - 动态表单创建、表单模板管理"),
            Map.entry("META", "意图管理 - 意图创建、意图测试、意图优化"),
            Map.entry("PROCESSING", "生产批次 - 批次创建、批次启动、批次完成、加工记录"),
            Map.entry("EQUIPMENT", "设备管理 - 设备状态查询、设备告警、设备维护保养")
    );

    /**
     * Category 示例输入映射
     * 帮助 LLM 理解每个 Category 的典型用户表达
     */
    private static final Map<String, List<String>> CATEGORY_EXAMPLES = Map.ofEntries(
            Map.entry("MATERIAL", List.of("查看原材料库存", "领用一批原料", "快过期的材料有哪些")),
            Map.entry("QUALITY", List.of("执行质检任务", "查看质检结果", "处理不合格品")),
            Map.entry("SHIPMENT", List.of("创建出货单", "确认发货", "查看物流状态")),
            Map.entry("CRM", List.of("查看客户列表", "评价供应商", "添加新客户")),
            Map.entry("HR", List.of("打卡签到", "查看考勤记录", "请假申请")),
            Map.entry("ALERT", List.of("有什么告警", "确认这个警报", "解决设备异常")),
            Map.entry("SCALE", List.of("电子秤列表", "开始称重", "校准秤设备")),
            Map.entry("REPORT", List.of("看生产报表", "今日产量统计", "库存报告")),
            Map.entry("DATA_OP", List.of("批量更新数据", "修改产品信息", "导出数据")),
            Map.entry("SYSTEM", List.of("切换调度模式", "开启某功能", "系统设置")),
            Map.entry("CONFIG", List.of("设置转化率", "配置规则", "调整阈值")),
            Map.entry("USER", List.of("创建新用户", "分配角色", "修改权限")),
            Map.entry("FORM", List.of("创建表单", "编辑表单模板")),
            Map.entry("META", List.of("创建新意图", "测试意图识别")),
            Map.entry("PROCESSING", List.of("创建生产批次", "开始加工", "完成批次")),
            Map.entry("EQUIPMENT", List.of("设备状态查询", "设备告警处理", "安排设备维护"))
    );

    // ==================== D8v2: Domain → Tool 前缀映射（工具过滤用） ====================

    /**
     * 每个 Domain 对应的工具名称前缀。
     * 工具命名规则: {domain}_{action}，如 material_batch_query, equipment_list
     */
    private static final Map<IntentKnowledgeBase.Domain, Set<String>> DOMAIN_TOOL_PREFIXES;
    static {
        Map<IntentKnowledgeBase.Domain, Set<String>> m = new EnumMap<>(IntentKnowledgeBase.Domain.class);
        m.put(IntentKnowledgeBase.Domain.EQUIPMENT, Set.of("equipment_", "dahua_", "isapi_"));
        m.put(IntentKnowledgeBase.Domain.SCALE, Set.of("scale_"));
        m.put(IntentKnowledgeBase.Domain.PROCESSING, Set.of("processing_", "production_"));
        m.put(IntentKnowledgeBase.Domain.MATERIAL, Set.of("material_"));
        m.put(IntentKnowledgeBase.Domain.ALERT, Set.of("alert_"));
        m.put(IntentKnowledgeBase.Domain.QUALITY, Set.of("quality_"));
        m.put(IntentKnowledgeBase.Domain.SHIPMENT, Set.of("shipment_", "trace_"));
        m.put(IntentKnowledgeBase.Domain.REPORT, Set.of("report_"));
        m.put(IntentKnowledgeBase.Domain.INVENTORY, Set.of("material_"));  // 库存复用 material tools
        m.put(IntentKnowledgeBase.Domain.CUSTOMER, Set.of("customer_", "supplier_"));
        m.put(IntentKnowledgeBase.Domain.USER, Set.of("user_"));
        m.put(IntentKnowledgeBase.Domain.SYSTEM, Set.of("scheduling_", "factory_"));
        m.put(IntentKnowledgeBase.Domain.FINANCE, Set.of("report_finance", "report_cost", "report_bom", "conversion_", "finance_", "financial_"));
        m.put(IntentKnowledgeBase.Domain.RESTAURANT, Set.of("restaurant_", "menu_", "dish_", "order_"));
        DOMAIN_TOOL_PREFIXES = Collections.unmodifiableMap(m);
    }

    /** 元工具前缀：始终包含（仅意图创建和系统查询工具，保持精简） */
    private static final Set<String> META_TOOL_PREFIXES = Set.of(
            "create_new_intent", "generate_handler", "query_entity", "governance_"
    );

    @Value("${cretas.ai.conversation.threshold:0.3}")
    private double conversationThreshold;

    @Autowired
    public LlmIntentFallbackClientImpl(
            @Qualifier("aiServiceHttpClient") @Autowired(required = false) OkHttpClient aiServiceHttpClient,
            @Autowired(required = false) DashScopeClient dashScopeClient,
            @Autowired(required = false) DashScopeConfig dashScopeConfig,
            @Autowired(required = false) IntentOptimizationSuggestionRepository suggestionRepository,
            @Autowired(required = false) AIIntentConfigRepository intentConfigRepository,
            @Autowired(required = false) ConversationService conversationService,
            @Autowired(required = false) ToolRegistry toolRegistry,
            @Autowired(required = false) IntentMatchingConfig intentMatchingConfig,
            @Autowired(required = false) ArenaRLTournamentService arenaRLTournamentService,
            @Autowired(required = false) ArenaRLConfig arenaRLConfig,
            @Autowired(required = false) RAGRetrievalService ragRetrievalService,
            @Autowired(required = false) IntentKnowledgeBase intentKnowledgeBase) {
        // OkHttp 客户端
        if (aiServiceHttpClient != null) {
            this.httpClient = aiServiceHttpClient;
            log.info("Using configured aiServiceHttpClient with connection pool");
        } else {
            this.httpClient = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(10, TimeUnit.SECONDS)
                    .build();
            log.info("Using default OkHttpClient (no connection pool configured)");
        }

        // DashScope 客户端
        this.dashScopeClient = dashScopeClient;
        this.dashScopeConfig = dashScopeConfig;

        // 建议仓库
        this.suggestionRepository = suggestionRepository;

        // 意图配置仓库 (用于自动创建工厂级意图)
        this.intentConfigRepository = intentConfigRepository;

        // 多轮对话服务
        this.conversationService = conversationService;

        // Tool Registry (用于 Tool Calling)
        this.toolRegistry = toolRegistry;

        // 意图匹配配置 (用于两阶段分类)
        this.intentMatchingConfig = intentMatchingConfig;

        if (dashScopeConfig != null && dashScopeConfig.shouldUseDirect("intent-classify")) {
            log.info("DashScope direct intent classification ENABLED");
        } else {
            log.info("Using Python service for intent classification (DashScope direct: disabled)");
        }

        // 两阶段分类日志
        if (intentMatchingConfig != null && intentMatchingConfig.isTwoPhaseClassificationEnabled()) {
            log.info("Two-phase classification ENABLED (threshold={} intents)",
                    intentMatchingConfig.getTwoPhaseThreshold());
        } else {
            log.info("Two-phase classification DISABLED - using single-phase classification");
        }

        if (autoCreateIntentEnabled && suggestionRepository != null) {
            log.info("Auto-create intent suggestion ENABLED (minConfidence={})", autoCreateMinConfidence);
            if (factoryAutoApproveEnabled && intentConfigRepository != null) {
                log.info("Factory-level intent AUTO-APPROVE ENABLED - factory intents will be created directly without review");
            } else {
                log.info("Factory-level intent auto-approve DISABLED - all intents require manual review");
            }
            if (toolRegistry != null && toolRegistry.hasExecutor("create_new_intent")) {
                log.info("Tool Calling mode ENABLED - will use create_new_intent tool instead of hardcoded logic");
            } else {
                log.info("Tool Calling mode DISABLED - fallback to legacy auto-create logic");
            }
        }

        if (conversationService != null) {
            log.info("Multi-turn conversation support ENABLED (threshold={})", 0.3);
        }

        // ArenaRL 锦标赛服务
        this.arenaRLTournamentService = arenaRLTournamentService;
        this.arenaRLConfig = arenaRLConfig;

        // v11.4 RAG: RAG 检索服务
        this.ragRetrievalService = ragRetrievalService;
        if (ragRetrievalService != null) {
            log.info("RAG Few-Shot enhancement ENABLED for LLM fallback");
        }

        // D8v2: 意图知识库（用于领域检测 → 工具过滤）
        this.intentKnowledgeBase = intentKnowledgeBase;

        if (arenaRLConfig != null && arenaRLConfig.isIntentDisambiguationEnabled()) {
            log.info("ArenaRL intent disambiguation ENABLED (ambiguity threshold={})",
                    arenaRLConfig.getIntentDisambiguation().getAmbiguityThreshold());
        } else {
            log.info("ArenaRL intent disambiguation DISABLED");
        }
    }

    // ==================== P4 Task 4.4: Conversation Context Prefix Helper ====================

    /**
     * Build a Chinese-language prefix summarizing recent conversation turns
     * for LLM prompt enrichment (P4 Task 4.4).
     *
     * <p>Input: list from {@link com.cretas.aims.service.ConversationStateService#loadRecent}
     * — newest turn at index 0. Output: reversed to chronological order for natural
     * reading, with each turn showing:
     * <pre>
     * [Turn N] 用户问: ...
     *   调用工具: ...
     *   返回关键字段: key1=v1, key2=v2, key3=v3
     * </pre>
     *
     * <p>Response dicts are truncated to the first 3 fields to keep the
     * prompt tight. Empty history → empty string (no section header at all).
     *
     * <p>Static for easy unit testing without Spring context.
     */
    public static String buildConversationContextPrefix(
            java.util.List<com.cretas.aims.entity.conversation.ConversationTurn> recent) {
        if (recent == null || recent.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 对话历史 (最近 ").append(recent.size()).append(" 轮)\n\n");

        // recent is newest-first; reverse to oldest-first for natural reading
        for (int i = recent.size() - 1; i >= 0; i--) {
            com.cretas.aims.entity.conversation.ConversationTurn t = recent.get(i);
            int turnNum = recent.size() - i;

            sb.append("[Turn ").append(turnNum).append("] ");
            sb.append("用户问: ").append(t.userMessage() == null ? "" : t.userMessage()).append("\n");

            if (t.toolName() != null && !t.toolName().isBlank()) {
                sb.append("  调用工具: ").append(t.toolName()).append("\n");
            }

            if (t.response() != null && !t.response().isEmpty()) {
                String summary = t.response().entrySet().stream()
                        .limit(3)  // keep prompt tight
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .collect(java.util.stream.Collectors.joining(", "));
                sb.append("  返回关键字段: ").append(summary).append("\n");
            }

            sb.append("\n");
        }

        sb.append("---\n\n");
        return sb.toString();
    }

    @Override
    public IntentMatchResult classifyIntent(String userInput, List<AIIntentConfig> availableIntents, String factoryId, Long userId, String userRole) {
        log.info("Calling LLM fallback for intent classification: factoryId={}, userId={}, role={}, input='{}'",
                 factoryId, userId, userRole, truncate(userInput, 50));

        // Canvas V2: store factoryId for per-factory tool filtering
        factoryIdHolder.set(factoryId);
        try {
            // 根据配置选择调用方式
            if (shouldUseDashScopeDirect()) {
                return classifyIntentDirect(userInput, availableIntents, factoryId, userId, userRole);
            } else {
                return classifyIntentViaPython(userInput, availableIntents, factoryId, userId, userRole);
            }
        } finally {
            factoryIdHolder.remove();
        }
    }

    @Override
    public IntentMatchResult classifyIntent(String userInput, String originalInput,
                                             List<AIIntentConfig> availableIntents,
                                             String factoryId, Long userId, String userRole) {
        // D8v2: 存储原始输入用于 Tool Calling 领域检测
        originalInputHolder.set(originalInput);
        try {
            return classifyIntent(userInput, availableIntents, factoryId, userId, userRole);
        } finally {
            originalInputHolder.remove();
        }
    }

    /**
     * 带多轮对话支持的意图分类
     *
     * 当单次分类的置信度低于阈值 (默认30%) 时，启动多轮对话模式:
     * 1. 检查是否有活跃的对话会话
     * 2. 如果有，继续对话
     * 3. 如果没有，创建新会话并开始对话
     * 4. 返回对话状态和澄清问题
     *
     * @param userInput 用户输入
     * @param availableIntents 可用意图列表
     * @param factoryId 工厂ID
     * @param userId 用户ID
     * @return 增强的意图识别结果
     */
    @Override
    public EnhancedIntentResult classifyIntentWithConversation(
            String userInput,
            List<AIIntentConfig> availableIntents,
            String factoryId,
            Long userId) {

        log.info("Classifying intent with conversation support: factory={}, user={}, input='{}'",
                factoryId, userId, truncate(userInput, 50));

        // 先尝试单次分类
        IntentMatchResult singleResult = classifyIntent(userInput, availableIntents, factoryId, userId, null);

        // 检查是否需要多轮对话
        if (!needsMultiTurnConversation(singleResult)) {
            log.debug("Single classification sufficient: confidence={}", singleResult.getConfidence());
            return EnhancedIntentResult.fromMatchResult(singleResult);
        }

        // 多轮对话服务不可用时，返回单次结果
        if (conversationService == null) {
            log.warn("Conversation service not available, returning single result");
            return EnhancedIntentResult.fromMatchResult(singleResult);
        }

        // 检查是否有活跃会话
        var activeSession = conversationService.getActiveSession(factoryId, userId);

        if (activeSession.isPresent()) {
            // 继续已有对话
            log.info("Continuing active conversation: session={}", activeSession.get().getSessionId());
            ConversationService.ConversationResponse response =
                    conversationService.continueConversation(activeSession.get().getSessionId(), userInput);

            return convertToEnhancedResult(response, singleResult);
        } else {
            // 开始新对话
            log.info("Starting new multi-turn conversation for low confidence result: {}",
                    singleResult.getConfidence());
            ConversationService.ConversationResponse response =
                    conversationService.startConversation(factoryId, userId, userInput);

            return convertToEnhancedResult(response, singleResult);
        }
    }

    /**
     * 将对话响应转换为增强结果
     */
    private EnhancedIntentResult convertToEnhancedResult(
            ConversationService.ConversationResponse response,
            IntentMatchResult fallbackResult) {

        if (response.isCompleted()) {
            // 对话完成，构建完整的匹配结果
            IntentMatchResult completedResult = IntentMatchResult.builder()
                    .userInput(fallbackResult != null ? fallbackResult.getUserInput() : "")
                    .confidence(response.getConfidence() != null ? response.getConfidence() : 0.9)
                    .matchMethod(MatchMethod.LLM)
                    .isStrongSignal(true)
                    .requiresConfirmation(false)
                    .clarificationQuestion(response.getMessage())
                    .build();

            return EnhancedIntentResult.builder()
                    .matchResult(completedResult)
                    .needsConversation(false)
                    .conversationSessionId(response.getSessionId())
                    .currentRound(response.getCurrentRound())
                    .build();
        }

        // 对话进行中，需要用户继续回复
        List<CandidateOption> options = null;
        if (response.getCandidates() != null) {
            options = response.getCandidates().stream()
                    .map(c -> CandidateOption.builder()
                            .intentCode(c.getIntentCode())
                            .intentName(c.getIntentName())
                            .confidence(c.getConfidence() != null ? c.getConfidence() : 0.5)
                            .description(c.getDescription())
                            .build())
                    .collect(java.util.stream.Collectors.toList());
        }

        return EnhancedIntentResult.needsConversation(
                response.getSessionId(),
                response.getCurrentRound(),
                response.getMessage(),
                options
        );
    }

    @Override
    public boolean needsMultiTurnConversation(IntentMatchResult matchResult) {
        if (matchResult == null) {
            return true;
        }
        // 置信度低于阈值且没有明确匹配时需要多轮对话
        return matchResult.getConfidence() < conversationThreshold && matchResult.getBestMatch() == null;
    }

    @Override
    public double getConversationThreshold() {
        return conversationThreshold;
    }

    /**
     * 检查是否应该使用 DashScope 直接调用
     */
    private boolean shouldUseDashScopeDirect() {
        return dashScopeConfig != null
                && dashScopeClient != null
                && dashScopeConfig.shouldUseDirect("intent-classify")
                && dashScopeClient.isAvailable();
    }

    /**
     * 使用 DashScope 直接进行意图分类 (新方式)
     *
     * 支持两种分类模式：
     * 1. 两阶段分类：当意图数量 >= 阈值时启用，先粗分类再细分类
     * 2. 单阶段分类：意图数量较少时直接在全量意图中匹配
     *
     * 两阶段分类的优势：
     * - 减少单次 LLM 处理的选项数量
     * - 提高分类准确率
     * - 更好的可解释性（先确定领域再确定具体意图）
     */
    private IntentMatchResult classifyIntentDirect(String userInput, List<AIIntentConfig> availableIntents, String factoryId, Long userId, String userRole) {
        log.debug("Using DashScope direct intent classification, intent count: {}",
                availableIntents != null ? availableIntents.size() : 0);

        try {
            // 判断是否使用两阶段分类
            if (shouldUseTwoPhaseClassification(availableIntents)) {
                log.info("[Classification] Using TWO-PHASE mode for {} intents (threshold: {})",
                        availableIntents.size(),
                        intentMatchingConfig != null ? intentMatchingConfig.getTwoPhaseThreshold() : "N/A");
                return classifyIntentTwoPhase(userInput, availableIntents, factoryId, userId, userRole);
            } else {
                log.debug("[Classification] Using SINGLE-PHASE mode for {} intents",
                        availableIntents != null ? availableIntents.size() : 0);
                return classifyIntentSinglePhase(userInput, availableIntents, factoryId, userId, userRole);
            }

        } catch (Exception e) {
            log.error("DashScope direct intent classification failed: {}", e.getMessage(), e);

            // 降级到 Python 服务
            if (isPythonServiceHealthy()) {
                log.info("Falling back to Python service due to DashScope error");
                return classifyIntentViaPython(userInput, availableIntents, factoryId, userId, userRole);
            }

            return IntentMatchResult.empty(userInput);
        }
    }

    /**
     * 使用 Python 服务进行意图分类 (旧方式)
     */
    private IntentMatchResult classifyIntentViaPython(String userInput, List<AIIntentConfig> availableIntents, String factoryId, Long userId, String userRole) {
        log.debug("Using Python service for intent classification");

        try {
            // 构建请求体
            Map<String, Object> requestBody = buildClassifyRequest(userInput, availableIntents, factoryId);

            // 调用 Python 端点
            String responseJson = callPythonEndpoint("/api/ai/intent/classify", requestBody);

            // 解析响应
            return parseClassifyResponse(responseJson, userInput, availableIntents, factoryId, userId, userRole);

        } catch (Exception e) {
            log.error("LLM intent classification failed: {}", e.getMessage(), e);
            // 返回空结果，不阻断流程
            return IntentMatchResult.empty(userInput);
        }
    }

    // v12.1 Phase 2: 动态 Few-Shot 服务
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DynamicFewShotService dynamicFewShotService;

    // v12.1 Phase 2: 置信度校准服务
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.ConfidenceCalibrationService confidenceCalibrationService;

    // W0 write-guard (intent-w0) — SITE F. The LLM Tool-Calling fallback (executeToolCallingWorkflow)
    // calls ToolExecutor.execute() DIRECTLY on a tool the LLM autonomously selected, bypassing
    // ToolDispatchService (Site B) and all confidence/sensitivity gating. A classifier misroute that
    // reaches this fallback could otherwise drive a write/destructive tool to silently execute with NO
    // user confirmation step. Field-injected (this class mixes constructor + @Autowired field injection);
    // WriteGuardService is stateless → safe to call from any thread.
    // Required injection (WriteGuardService is an unconditional @Service so always present) for
    // fail-CLOSED consistency with Sites A-E — required=false made Site F fail OPEN if the bean
    // were ever absent, defeating the destructive-op safety net.
    @org.springframework.beans.factory.annotation.Autowired
    private com.cretas.aims.ai.tool.WriteGuardService writeGuardService;

    // W9 红线 (AI-RBAC 系统性收口) — SITE F: central RBAC enforce alongside the W0 write-guard.
    @org.springframework.beans.factory.annotation.Autowired
    private com.cretas.aims.ai.tool.ToolRbacEnforcer toolRbacEnforcer;

    /**
     * 构建意图分类系统提示词
     *
     * v12.1 Phase 2 增强:
     * 1. 使用 Chain-of-Thought 4 步分析框架
     * 2. 使用 MMR 算法选择多样化的 Few-Shot 示例
     * 3. 覆盖范围优先：先判断是否在系统能力范围内，超范围诚实返回 UNKNOWN(≤0.3)，不硬套
     * 4. v11.4 RAG: 动态注入相似历史案例作为 Few-Shot 示例
     *
     * @param availableIntents 可用意图列表
     * @param userInput        用户输入（用于 RAG 检索）
     * @param factoryId        工厂ID（用于 RAG 检索）
     */
    private String buildIntentClassifyPrompt(List<AIIntentConfig> availableIntents, String userInput, String factoryId) {
        StringBuilder sb = new StringBuilder();

        // P4 Task 4.4: Prepend conversation history if available via ThreadLocal
        java.util.List<com.cretas.aims.entity.conversation.ConversationTurn> recentTurns =
                conversationTurnsHolder.get();
        String contextPrefix = buildConversationContextPrefix(recentTurns);
        if (!contextPrefix.isEmpty()) {
            sb.append(contextPrefix);
            log.debug("[P4.4] Injected {} conversation turn(s) into LLM prompt", recentTurns.size());
        }

        // ===== Chain-of-Thought 4 步分析框架 =====
        sb.append("你是一个意图识别助手。请按照以下 4 步思考链 (Chain-of-Thought) 分析用户输入，选择最匹配的意图。\n\n");

        sb.append("## 思考步骤 (必须在 reasoning 字段中体现)\n\n");
        sb.append("**Step 1 - 实体识别**: 识别用户输入中的关键实体\n");
        sb.append("  - 时间实体: 今天、本周、上月、Q1 等\n");
        sb.append("  - 地点实体: 仓库、车间、A区、华东 等\n");
        sb.append("  - 物料实体: 原材料、批次号、产品名称 等\n");
        sb.append("  - 数量实体: 100kg、50件、3个 等\n");
        sb.append("  - 人员实体: 张三、操作员、供应商 等\n\n");

        sb.append("**Step 2 - 意图分析**: 分析用户想要执行的操作类型\n");
        sb.append("  - 查询类: 查看、查询、看看、有哪些、多少\n");
        sb.append("  - 创建类: 创建、新建、添加、录入\n");
        sb.append("  - 更新类: 修改、更新、变更、调整\n");
        sb.append("  - 删除类: 删除、取消、作废\n");
        sb.append("  - 执行类: 执行、启动、完成、确认\n\n");

        sb.append("**Step 3 - 领域确定**: 确定属于哪个业务领域\n");
        sb.append("  - 物料管理: 库存、批次、领用、过期\n");
        sb.append("  - 出货物流: 发货、物流、运输、收货\n");
        sb.append("  - 人事考勤: 打卡、签到、考勤、请假\n");
        sb.append("  - 质量检测: 质检、合格率、不良品\n");
        sb.append("  - 设备管理: 设备、机器、告警、维护\n");
        sb.append("  - 报表统计: 报表、统计、分析、趋势\n\n");

        sb.append("**Step 4 - 最终决策**: 基于以上分析，选择最匹配的意图代码和置信度\n\n");

        // Few-Shot 示例 (口语化表达映射) - 方案 B
        sb.append("## 口语化表达示例\n\n");
        sb.append("以下是常见的口语化表达与标准意图的映射关系，请参考这些示例进行判断：\n\n");
        sb.append("| 口语化表达 | 对应意图 |\n");
        sb.append("|-----------|--------|\n");
        // 考勤类
        sb.append("| 打个卡、签到、我要打卡 | CLOCK_IN |\n");
        sb.append("| 签退、下班打卡 | CLOCK_OUT |\n");
        sb.append("| 我打卡了没、今天出勤了没 | ATTENDANCE_STATUS |\n");
        sb.append("| 这周考勤、看看考勤记录、今天几个人上班 | ATTENDANCE_HISTORY |\n");
        sb.append("| 张三的出勤、某人出勤记录、员工考勤 | ATTENDANCE_HISTORY |\n");
        sb.append("| 出勤统计、考勤汇总 | ATTENDANCE_HISTORY |\n");
        // 原材料类
        sb.append("| 看看原料库存、查一下原材料、查批次 | MATERIAL_BATCH_QUERY |\n");
        sb.append("| 领用原材料、使用原料 | MATERIAL_BATCH_USE |\n");
        sb.append("| 快过期的原料、临期原材料 | MATERIAL_EXPIRING_ALERT |\n");
        sb.append("| 原料快没了、缺货预警 | MATERIAL_LOW_STOCK_ALERT |\n");
        // 设备类 - 注意区分查询和更新
        sb.append("| 秤有哪些、电子秤列表 | SCALE_LIST_DEVICES |\n");
        sb.append("| 设备保养、维护记录 | EQUIPMENT_MAINTENANCE |\n");
        sb.append("| 哪个机器有问题、设备告警 | ALERT_BY_EQUIPMENT |\n");
        sb.append("| 设备状态、机台运行情况、设备健康度 | EQUIPMENT_STATUS_QUERY |\n");
        sb.append("| 更新设备状态、修改设备状态 | EQUIPMENT_STATUS_UPDATE |\n");
        // 质检类 - 注意区分查询和统计
        sb.append("| 做一下质检、开始QC | QUALITY_CHECK_EXECUTE |\n");
        sb.append("| 看看质检记录、QC检测有哪些 | QUALITY_CHECK_QUERY |\n");
        sb.append("| 质检结果、今天合格率、不良品数量 | QUALITY_CHECK_QUERY |\n");
        sb.append("| 不合格品怎么处理、处置不良品 | QUALITY_DISPOSITION_EXECUTE |\n");
        // 告警类
        sb.append("| 有什么警报、所有告警、今天异常情况 | ALERT_LIST |\n");
        sb.append("| 处理掉这个警报、关闭告警 | ALERT_RESOLVE |\n");
        sb.append("| 分析一下警报原因、告警诊断 | ALERT_DIAGNOSE |\n");
        // 发货/物流类
        sb.append("| 物流到哪了、发货状态、物流跟踪 | SHIPMENT_QUERY |\n");
        sb.append("| 今天出货量、发货量、出货统计 | SHIPMENT_QUERY |\n");
        // 报表类 - 销售相关归入看板概览（注意：销售查询用REPORT_DASHBOARD_OVERVIEW，不是REPORT_FINANCE）
        sb.append("| 看看报表、数据总览 | REPORT_DASHBOARD_OVERVIEW |\n");
        sb.append("| 销售情况、销售数据、销售报表、本月销售、卖了多少 | REPORT_DASHBOARD_OVERVIEW |\n");
        sb.append("| 销售排名、销冠、业绩排名、谁卖得最好、谁最厉害 | REPORT_KPI |\n");
        sb.append("| 各部门业绩、部门排名、哪个部门最好 | REPORT_KPI |\n");
        sb.append("| 销售趋势、销售走势、华东区销售、北京销售额 | REPORT_TRENDS |\n");
        sb.append("| 各地区数据、区域分析、地区销售 | REPORT_TRENDS |\n");
        sb.append("| 库存情况、库存多少 | REPORT_INVENTORY |\n");
        sb.append("| 毛利率多少、利润率、财务指标 | REPORT_FINANCE |\n");
        // 生产类 - 区分实时状态和报表（注意：产量查询用PRODUCTION_STATUS_QUERY，不是PROCESSING_BATCH_TIMELINE）
        sb.append("| 今天生产了多少、产量多少、车间产量、生产进度、产量 | PRODUCTION_STATUS_QUERY |\n");
        sb.append("| 生产报表、产量统计报告 | REPORT_PRODUCTION |\n");
        // 原料消耗报表
        sb.append("| 原料消耗、原材料使用量、用了多少原料 | REPORT_MATERIAL_CONSUMPTION |\n");
        // 供应商/客户类
        sb.append("| 供货商名单、供货方有哪些 | SUPPLIER_LIST |\n");
        sb.append("| 找一下供货商、查询供货方 | SUPPLIER_SEARCH |\n");
        sb.append("| 老客户名单、买家列表 | CUSTOMER_LIST |\n");
        sb.append("\n");

        // v12.1 Phase 2: 使用 MMR 算法选择多样化的 Few-Shot 示例
        if (dynamicFewShotService != null && userInput != null && !userInput.isEmpty()) {
            try {
                List<DynamicFewShotService.FewShotExample> mmrExamples =
                    dynamicFewShotService.selectFewShotExamples(factoryId, userInput, 6);
                if (mmrExamples != null && !mmrExamples.isEmpty()) {
                    sb.append("## 动态相似案例 (MMR 算法选择)\n\n");
                    sb.append("以下是与当前输入相似且多样化的历史案例（相关性 + 多样性平衡）：\n\n");
                    sb.append("| 用户输入 | 意图代码 | 意图名称 | 相关度 |\n");
                    sb.append("|---------|---------|--------|-------|\n");
                    for (DynamicFewShotService.FewShotExample example : mmrExamples) {
                        sb.append(String.format("| %s | %s | %s | %.2f |\n",
                            example.getUserInput(),
                            example.getIntentCode(),
                            example.getIntentName() != null ? example.getIntentName() : "",
                            example.getRelevanceScore()));
                    }
                    sb.append("\n");
                    log.debug("[MMR Few-Shot] Injected {} diverse examples for input: '{}'",
                        mmrExamples.size(), truncate(userInput, 30));
                }
            } catch (Exception e) {
                log.warn("[MMR Few-Shot] Failed to select examples: {}", e.getMessage());
                // 降级到 RAG 服务
            }
        }

        // v11.4 RAG: 动态注入相似历史案例作为 Few-Shot 示例 (降级方案)
        if ((dynamicFewShotService == null) && ragRetrievalService != null && userInput != null && !userInput.isEmpty()) {
            try {
                List<RAGRetrievalService.RAGExample> ragExamples =
                    ragRetrievalService.getFewShotExamples(factoryId, userInput, 3);
                if (ragExamples != null && !ragExamples.isEmpty()) {
                    sb.append("## 相似历史案例参考\n\n");
                    sb.append("以下是与当前输入相似的历史成功案例，请优先参考：\n\n");
                    sb.append("| 用户输入 | 意图代码 | 意图名称 |\n");
                    sb.append("|---------|---------|--------|\n");
                    for (RAGRetrievalService.RAGExample example : ragExamples) {
                        sb.append(String.format("| %s | %s | %s |\n",
                            example.getUserInput(),
                            example.getIntentCode(),
                            example.getIntentName() != null ? example.getIntentName() : ""));
                    }
                    sb.append("\n");
                    log.debug("[RAG Few-Shot] Injected {} dynamic examples for input: '{}'",
                        ragExamples.size(), truncate(userInput, 30));
                }
            } catch (Exception e) {
                log.warn("[RAG Few-Shot] Failed to retrieve examples: {}", e.getMessage());
                // 静默失败，不影响主流程
            }
        }

        sb.append("## 可用意图列表\n\n");

        for (AIIntentConfig intent : availableIntents) {
            sb.append(String.format("- **%s** (%s): %s\n",
                    intent.getIntentCode(),
                    intent.getIntentName(),
                    intent.getDescription() != null ? intent.getDescription() : ""));
            if (intent.getKeywords() != null && !intent.getKeywords().isEmpty()) {
                sb.append(String.format("  关键词: %s\n", String.join(", ", intent.getKeywords())));
            }
        }

        sb.append("\n## 重要规则\n\n");
        sb.append("1. **先判断是否在覆盖范围内**：如果用户问题确实不属于以上任何意图的能力范围"
                + "(超出本系统当前功能，例如闲聊/常识问答/本系统不支持的操作)，"
                + "必须返回 intent_code=UNKNOWN 且 confidence≤0.3，并在 reasoning 说明为何超出范围。"
                + "绝不要为了\"给个答案\"而硬套一个最接近但并不真正匹配的意图——硬套=自信答错，"
                + "比诚实说\"暂不支持\"更糟。只有当问题确实匹配某意图时才选它。\n");
        sb.append("2. **理解同义词**：参考上面的口语化示例，用户可能使用不同的表达方式描述相同的意图\n");
        sb.append("3. **优先语义匹配**：只要问题确实落在某意图能力范围内，即使没有完全匹配的关键词，"
                + "也要根据语义选择最相关的意图(此规则不适用于超范围问题——见规则1)\n");
        sb.append("4. **置信度必须诚实**：真正匹配时置信度可在 0.6 以上；勉强/硬套的匹配置信度不得虚高，"
                + "宁可低置信度或 UNKNOWN。置信度要反映真实匹配程度，不是\"尽量给高\"。\n");
        sb.append("5. **区分查询和更新**：\"设备状态\"是查询(QUERY)，\"更新设备状态\"才是更新(UPDATE)\n");
        sb.append("6. **销售类意图优先级**：销售情况/数据→REPORT_DASHBOARD_OVERVIEW，销售排名→REPORT_KPI，不要用REPORT_FINANCE\n");
        sb.append("7. **生产类意图优先级**：产量/今天生产→PRODUCTION_STATUS_QUERY，不要用PROCESSING_BATCH_TIMELINE\n");
        sb.append("8. **质检类意图优先级**：质检结果/合格率→QUALITY_CHECK_QUERY，不要用QUALITY_STATS\n");
        sb.append("9. **供应商排名 vs 销售排名**：供应商排名→SUPPLIER_RANKING，销售排名/业绩排名→REPORT_KPI\n\n");

        sb.append("## 输出格式 (必须遵循 CoT 格式)\n\n");
        sb.append("请以 JSON 格式返回，reasoning 字段**必须**包含 4 步思考链分析：\n");
        sb.append("```json\n");
        sb.append("{\n");
        sb.append("  \"intent_code\": \"真正匹配的意图代码；若问题确实超出覆盖范围则填 UNKNOWN(勿硬套)\",\n");
        sb.append("  \"confidence\": 0.0-1.0 之间的置信度,\n");
        sb.append("  \"reasoning\": \"Step1-实体识别:... Step2-意图分析:... Step3-领域确定:... Step4-最终决策:...\",\n");
        sb.append("  \"entities\": {\n");
        sb.append("    \"time\": \"识别的时间实体，如'今天'、'本周'\",\n");
        sb.append("    \"location\": \"识别的地点实体\",\n");
        sb.append("    \"material\": \"识别的物料实体\",\n");
        sb.append("    \"quantity\": \"识别的数量实体\",\n");
        sb.append("    \"person\": \"识别的人员实体\"\n");
        sb.append("  },\n");
        sb.append("  \"action_type\": \"QUERY|CREATE|UPDATE|DELETE|EXECUTE\",\n");
        sb.append("  \"domain\": \"MATERIAL|SHIPMENT|HR|QUALITY|EQUIPMENT|REPORT|OTHER\",\n");
        sb.append("  \"other_candidates\": [\n");
        sb.append("    {\"intent_code\": \"其他可能的意图\", \"confidence\": 0.0-1.0}\n");
        sb.append("  ]\n");
        sb.append("}\n");
        sb.append("```\n\n");
        sb.append("仅返回 JSON，不要包含其他文字。reasoning 必须体现完整的 4 步分析过程。");

        return sb.toString();
    }

    // ==================== 两阶段分类实现 ====================

    /**
     * 判断是否应该使用两阶段分类
     *
     * 条件：
     * 1. 配置已启用两阶段分类
     * 2. 可用意图数量超过阈值
     * 3. DashScope 客户端可用
     *
     * @param availableIntents 可用意图列表
     * @return true 表示应使用两阶段分类
     */
    private boolean shouldUseTwoPhaseClassification(List<AIIntentConfig> availableIntents) {
        if (intentMatchingConfig == null) {
            return false;
        }
        if (!intentMatchingConfig.isTwoPhaseClassificationEnabled()) {
            return false;
        }
        if (availableIntents == null) {
            return false;
        }
        // 只有当意图数量超过阈值时才使用两阶段分类
        return availableIntents.size() >= intentMatchingConfig.getTwoPhaseThreshold();
    }

    /**
     * 两阶段意图分类主方法
     *
     * 工作流程：
     * 1. 第一阶段（粗分类）：从 16 个 Category 中选择最匹配的分类
     * 2. 根据选中的 Category 筛选出相关意图
     * 3. 第二阶段（细分类）：在筛选后的意图列表中进行精确匹配
     *
     * 优势：
     * - 减少 LLM 单次处理的选项数量（从 100+ 降至 6-15）
     * - 提高分类准确率
     * - 两次 LLM 调用，但每次负载更小
     *
     * @param userInput 用户输入
     * @param availableIntents 可用意图列表
     * @param factoryId 工厂ID
     * @param userId 用户ID
     * @param userRole 用户角色
     * @return 意图匹配结果
     */
    private IntentMatchResult classifyIntentTwoPhase(
            String userInput,
            List<AIIntentConfig> availableIntents,
            String factoryId,
            Long userId,
            String userRole) {

        log.info("[Two-Phase] Starting two-phase classification for input: '{}'", truncate(userInput, 50));

        try {
            // ===== 第一阶段：粗分类 =====
            log.debug("[Two-Phase] Phase 1: Category classification");
            String categoryPrompt = buildCategoryClassifyPrompt();
            String categoryResponse = dashScopeClient.classifyIntent(categoryPrompt, userInput);
            String matchedCategory = parseCategoryClassifyResponse(categoryResponse);

            if (matchedCategory == null || matchedCategory.isEmpty() || "UNKNOWN".equalsIgnoreCase(matchedCategory)) {
                log.warn("[Two-Phase] Phase 1 failed to match category, falling back to single-phase");
                // 降级到单阶段分类
                return classifyIntentSinglePhase(userInput, availableIntents, factoryId, userId, userRole);
            }

            log.info("[Two-Phase] Phase 1 result: category={}", matchedCategory);

            // ===== 筛选该 Category 下的意图 =====
            List<AIIntentConfig> categoryIntents = filterIntentsByCategory(availableIntents, matchedCategory);

            if (categoryIntents.isEmpty()) {
                log.warn("[Two-Phase] No intents found for category '{}', trying related categories", matchedCategory);
                // 尝试扩展到相关 Category
                categoryIntents = filterIntentsByRelatedCategories(availableIntents, matchedCategory);

                if (categoryIntents.isEmpty()) {
                    log.warn("[Two-Phase] Still no intents found, falling back to single-phase");
                    return classifyIntentSinglePhase(userInput, availableIntents, factoryId, userId, userRole);
                }
            }

            log.info("[Two-Phase] Phase 2: Fine classification among {} intents (category: {})",
                    categoryIntents.size(), matchedCategory);

            // ===== 第二阶段：细分类 =====
            String intentPrompt = buildIntentClassifyPromptForCategory(matchedCategory, categoryIntents);
            String intentResponse = dashScopeClient.classifyIntent(intentPrompt, userInput);

            // 解析细分类结果（复用现有解析逻辑）
            IntentMatchResult result = parseDirectClassifyResponse(
                    intentResponse, userInput, categoryIntents, factoryId, userId, userRole);

            // 如果细分类失败，尝试在全量意图中匹配
            if (result.getBestMatch() == null && result.getConfidence() < 0.5) {
                log.warn("[Two-Phase] Phase 2 low confidence, trying full intent list as fallback");
                return classifyIntentSinglePhase(userInput, availableIntents, factoryId, userId, userRole);
            }

            log.info("[Two-Phase] Classification completed: intent={}, confidence={}",
                    result.getBestMatch() != null ? result.getBestMatch().getIntentCode() : "UNKNOWN",
                    result.getConfidence());

            return result;

        } catch (Exception e) {
            log.error("[Two-Phase] Classification failed: {}, falling back to single-phase", e.getMessage(), e);
            // 降级到单阶段分类
            return classifyIntentSinglePhase(userInput, availableIntents, factoryId, userId, userRole);
        }
    }

    /**
     * 单阶段分类（原有逻辑封装）
     * 用于两阶段分类失败时的降级处理
     */
    private IntentMatchResult classifyIntentSinglePhase(
            String userInput,
            List<AIIntentConfig> availableIntents,
            String factoryId,
            Long userId,
            String userRole) {

        log.debug("[Single-Phase] Using single-phase classification");
        String systemPrompt = buildIntentClassifyPrompt(availableIntents, userInput, factoryId);
        String responseJson = dashScopeClient.classifyIntent(systemPrompt, userInput);
        return parseDirectClassifyResponse(responseJson, userInput, availableIntents, factoryId, userId, userRole);
    }

    /**
     * 构建 Category 粗分类提示词
     *
     * 设计原则：
     * 1. 简洁清晰的 Category 描述
     * 2. 提供示例帮助 LLM 理解
     * 3. 输出格式严格限定为 JSON
     *
     * @return 粗分类系统提示词
     */
    private String buildCategoryClassifyPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个意图分类助手。根据用户输入，选择最匹配的业务类别。\n\n");

        sb.append("## 可用类别\n\n");
        sb.append("| 类别代码 | 描述 | 示例输入 |\n");
        sb.append("|----------|------|----------|\n");

        for (Map.Entry<String, String> entry : CATEGORY_DESCRIPTIONS.entrySet()) {
            String category = entry.getKey();
            String description = entry.getValue();
            List<String> examples = CATEGORY_EXAMPLES.getOrDefault(category, List.of());
            String exampleStr = examples.isEmpty() ? "-" : String.join("; ", examples);

            sb.append(String.format("| %s | %s | %s |\n", category, description, exampleStr));
        }

        sb.append("\n## 重要规则\n\n");
        sb.append("1. **选相关类别；都不属于则 UNKNOWN**：根据语义选择最相关的类别；"
                + "如果用户问题不属于以上任何类别(超出本系统功能)，返回 category=UNKNOWN 且 confidence≤0.3，"
                + "不要硬选一个并不相关的类别。\n");
        sb.append("2. **理解口语表达**：用户可能使用口语化的方式描述需求\n");
        sb.append("3. **关注核心意图**：忽略修饰词，关注用户真正想做什么\n\n");

        sb.append("## 输出格式\n\n");
        sb.append("请以 JSON 格式返回：\n");
        sb.append("```json\n");
        sb.append("{\n");
        sb.append("  \"category\": \"类别代码\",\n");
        sb.append("  \"confidence\": 0.0-1.0,\n");
        sb.append("  \"reasoning\": \"判断理由\"\n");
        sb.append("}\n");
        sb.append("```\n\n");
        sb.append("仅返回 JSON，不要包含其他文字。");

        return sb.toString();
    }

    /**
     * 解析 Category 粗分类响应
     *
     * @param responseJson LLM 返回的 JSON 响应
     * @return 匹配的 Category 代码，解析失败返回 null
     */
    private String parseCategoryClassifyResponse(String responseJson) {
        try {
            // 提取 JSON 部分
            Pattern pattern = Pattern.compile("\\{[\\s\\S]*\\}");
            Matcher matcher = pattern.matcher(responseJson);

            if (!matcher.find()) {
                log.warn("[Two-Phase] Could not extract JSON from category response: {}",
                        truncate(responseJson, 100));
                return null;
            }

            JsonNode json = objectMapper.readTree(matcher.group());

            String category = json.has("category") ? json.get("category").asText() : null;
            double confidence = json.has("confidence") ? json.get("confidence").asDouble() : 0.5;
            String reasoning = json.has("reasoning") ? json.get("reasoning").asText() : null;

            log.debug("[Two-Phase] Category parse result: category={}, confidence={}, reasoning={}",
                    category, confidence, truncate(reasoning, 50));

            // 验证 Category 是否有效
            if (category != null && CATEGORY_DESCRIPTIONS.containsKey(category.toUpperCase())) {
                return category.toUpperCase();
            }

            // 尝试模糊匹配
            if (category != null) {
                String upperCategory = category.toUpperCase();
                for (String validCategory : CATEGORY_DESCRIPTIONS.keySet()) {
                    if (upperCategory.contains(validCategory) || validCategory.contains(upperCategory)) {
                        log.debug("[Two-Phase] Fuzzy matched category: {} -> {}", category, validCategory);
                        return validCategory;
                    }
                }
            }

            log.warn("[Two-Phase] Invalid category returned: {}", category);
            return null;

        } catch (Exception e) {
            log.error("[Two-Phase] Failed to parse category response: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 构建 Category 内的细分类提示词
     *
     * 与单阶段分类的区别：
     * 1. 意图数量更少（通常 6-15 个）
     * 2. 可以提供更详细的意图描述
     * 3. 强调该 Category 的上下文
     *
     * @param category 已匹配的 Category
     * @param categoryIntents 该 Category 下的意图列表
     * @return 细分类系统提示词
     */
    private String buildIntentClassifyPromptForCategory(String category, List<AIIntentConfig> categoryIntents) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个意图识别助手。用户的需求属于「");
        sb.append(CATEGORY_DESCRIPTIONS.getOrDefault(category, category));
        sb.append("」类别。\n\n");
        sb.append("请从以下意图中选择最匹配的一个：\n\n");

        sb.append("## 可用意图列表\n\n");

        for (AIIntentConfig intent : categoryIntents) {
            sb.append(String.format("### %s (%s)\n", intent.getIntentCode(), intent.getIntentName()));
            if (intent.getDescription() != null && !intent.getDescription().isEmpty()) {
                sb.append(String.format("描述: %s\n", intent.getDescription()));
            }
            if (intent.getKeywords() != null && !intent.getKeywords().isEmpty()) {
                sb.append(String.format("关键词: %s\n", intent.getKeywords()));
            }
            sb.append("\n");
        }

        sb.append("## 重要规则\n\n");
        sb.append("1. **先判断是否在覆盖范围内**：如果用户问题确实不属于上述任何意图的能力范围"
                + "(超出本系统当前功能，例如闲聊/常识问答/本系统不支持的操作)，必须返回 "
                + "intent_code=UNKNOWN 且 confidence≤0.3。绝不要为了\"给个答案\"而硬套一个最接近但"
                + "并不真正匹配的意图——硬套=自信答错。只有当问题确实匹配某意图时才选它。\n");
        sb.append("2. **语义优先**：只要问题确实落在某意图能力范围内，即使没有完全匹配的关键词，"
                + "也要根据语义选择(此规则不适用于超范围问题——见规则1)\n");
        sb.append("3. **置信度必须诚实**：真正匹配时置信度可在 0.7 以上；勉强/硬套的匹配置信度不得"
                + "虚高，宁可低置信度或 UNKNOWN。置信度要反映真实匹配程度。\n\n");

        sb.append("## 输出格式\n\n");
        sb.append("```json\n");
        sb.append("{\n");
        sb.append("  \"intent_code\": \"匹配的意图代码\",\n");
        sb.append("  \"confidence\": 0.0-1.0,\n");
        sb.append("  \"reasoning\": \"判断理由\",\n");
        sb.append("  \"other_candidates\": [{\"intent_code\": \"...\", \"confidence\": 0.0-1.0}]\n");
        sb.append("}\n");
        sb.append("```\n\n");
        sb.append("仅返回 JSON。");

        return sb.toString();
    }

    /**
     * 根据 Category 筛选意图
     *
     * @param availableIntents 所有可用意图
     * @param category 目标 Category
     * @return 属于该 Category 的意图列表
     */
    private List<AIIntentConfig> filterIntentsByCategory(List<AIIntentConfig> availableIntents, String category) {
        if (availableIntents == null || category == null) {
            return Collections.emptyList();
        }

        String upperCategory = category.toUpperCase();

        return availableIntents.stream()
                .filter(intent -> {
                    // 方式1: 直接匹配 intentCategory 字段
                    if (intent.getIntentCategory() != null &&
                            intent.getIntentCategory().toUpperCase().equals(upperCategory)) {
                        return true;
                    }

                    // 方式2: 通过 intentCode 前缀推断 Category
                    String intentCode = intent.getIntentCode();
                    if (intentCode != null) {
                        String upperCode = intentCode.toUpperCase();
                        // 检查是否以 Category 开头（如 MATERIAL_BATCH_QUERY 属于 MATERIAL）
                        if (upperCode.startsWith(upperCategory + "_")) {
                            return true;
                        }
                        // 特殊映射处理
                        if (matchesCategoryByCodePattern(upperCode, upperCategory)) {
                            return true;
                        }
                    }

                    return false;
                })
                .collect(Collectors.toList());
    }

    /**
     * 根据相关 Category 筛选意图（扩展匹配）
     *
     * 当主 Category 没有匹配的意图时，尝试匹配相关 Category
     * 例如：EQUIPMENT 可能与 ALERT、SCALE 相关
     *
     * @param availableIntents 所有可用意图
     * @param primaryCategory 主 Category
     * @return 相关 Category 的意图列表
     */
    private List<AIIntentConfig> filterIntentsByRelatedCategories(
            List<AIIntentConfig> availableIntents, String primaryCategory) {

        // Category 关联映射
        Map<String, List<String>> relatedCategories = Map.ofEntries(
                Map.entry("EQUIPMENT", List.of("ALERT", "SCALE")),
                Map.entry("ALERT", List.of("EQUIPMENT", "QUALITY")),
                Map.entry("SCALE", List.of("EQUIPMENT")),
                Map.entry("QUALITY", List.of("PROCESSING", "MATERIAL")),
                Map.entry("PROCESSING", List.of("MATERIAL", "QUALITY", "EQUIPMENT")),
                Map.entry("MATERIAL", List.of("PROCESSING", "QUALITY")),
                Map.entry("SHIPMENT", List.of("CRM")),
                Map.entry("CRM", List.of("SHIPMENT")),
                Map.entry("REPORT", List.of("DATA_OP")),
                Map.entry("DATA_OP", List.of("REPORT")),
                Map.entry("SYSTEM", List.of("CONFIG", "USER")),
                Map.entry("CONFIG", List.of("SYSTEM")),
                Map.entry("USER", List.of("HR", "SYSTEM"))
        );

        List<String> related = relatedCategories.getOrDefault(primaryCategory.toUpperCase(), Collections.emptyList());

        if (related.isEmpty()) {
            return Collections.emptyList();
        }

        List<AIIntentConfig> result = new ArrayList<>();
        for (String relatedCategory : related) {
            result.addAll(filterIntentsByCategory(availableIntents, relatedCategory));
        }

        log.debug("[Two-Phase] Expanded to related categories {}, found {} intents",
                related, result.size());

        return result;
    }

    /**
     * 通过意图代码模式匹配 Category
     *
     * 处理特殊的意图代码命名模式
     */
    private boolean matchesCategoryByCodePattern(String intentCode, String category) {
        // 特殊映射规则
        Map<String, List<String>> categoryPatterns = Map.ofEntries(
                // HR 类别的特殊前缀
                Map.entry("HR", List.of("CLOCK_", "ATTENDANCE_", "LEAVE_")),
                // CRM 类别的特殊前缀
                Map.entry("CRM", List.of("CUSTOMER_", "SUPPLIER_")),
                // REPORT 类别的特殊前缀
                Map.entry("REPORT", List.of("REPORT_", "DASHBOARD_", "STATS_")),
                // META 类别的特殊前缀
                Map.entry("META", List.of("INTENT_", "CREATE_INTENT")),
                // PROCESSING 类别的特殊前缀
                Map.entry("PROCESSING", List.of("BATCH_", "PRODUCTION_"))
        );

        List<String> patterns = categoryPatterns.get(category);
        if (patterns == null) {
            return false;
        }

        for (String pattern : patterns) {
            if (intentCode.startsWith(pattern)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 解析 DashScope 直接调用的响应
     */
    private IntentMatchResult parseDirectClassifyResponse(String responseJson,
                                                           String userInput,
                                                           List<AIIntentConfig> availableIntents,
                                                           String factoryId,
                                                           Long userId,
                                                           String userRole) {
        try {
            // 提取 JSON 部分
            Pattern pattern = Pattern.compile("\\{[\\s\\S]*\\}");
            Matcher matcher = pattern.matcher(responseJson);

            if (!matcher.find()) {
                log.warn("Could not extract JSON from DashScope response: {}", truncate(responseJson, 100));
                return IntentMatchResult.empty(userInput);
            }

            JsonNode json = objectMapper.readTree(matcher.group());

            // 提取字段 (v12.1 Phase 2: 增强 CoT 解析)
            String intentCode = json.has("intent_code") ? json.get("intent_code").asText() : "UNKNOWN";
            double llmConfidence = json.has("confidence") ? json.get("confidence").asDouble() : 0.5;
            String reasoning = json.has("reasoning") ? json.get("reasoning").asText() : null;
            String actionType = json.has("action_type") ? json.get("action_type").asText() : null;
            String domain = json.has("domain") ? json.get("domain").asText() : null;

            // 解析实体 (CoT Step 1 结果)
            Map<String, String> entities = new HashMap<>();
            if (json.has("entities") && json.get("entities").isObject()) {
                JsonNode entitiesNode = json.get("entities");
                if (entitiesNode.has("time")) entities.put("time", entitiesNode.get("time").asText());
                if (entitiesNode.has("location")) entities.put("location", entitiesNode.get("location").asText());
                if (entitiesNode.has("material")) entities.put("material", entitiesNode.get("material").asText());
                if (entitiesNode.has("quantity")) entities.put("quantity", entitiesNode.get("quantity").asText());
                if (entitiesNode.has("person")) entities.put("person", entitiesNode.get("person").asText());
            }

            // 验证 CoT reasoning 格式
            boolean hasValidCoT = reasoning != null &&
                    (reasoning.contains("Step") || reasoning.contains("step") ||
                     (reasoning.contains("实体") && reasoning.contains("意图") && reasoning.contains("领域")));
            if (!hasValidCoT && reasoning != null) {
                log.debug("[CoT] LLM reasoning may not follow 4-step format: {}", truncate(reasoning, 100));
            }

            // Clamp confidence
            llmConfidence = Math.max(0.0, Math.min(1.0, llmConfidence));

            // v12.1 Phase 2: 多源置信度融合
            double confidence = llmConfidence;
            if (confidenceCalibrationService != null && !"UNKNOWN".equalsIgnoreCase(intentCode)) {
                try {
                    // 构建置信度输入
                    com.cretas.aims.service.ConfidenceCalibrationService.ConfidenceInputs inputs =
                        com.cretas.aims.service.ConfidenceCalibrationService.ConfidenceInputs.builder()
                            .llmConfidence(llmConfidence)
                            .llmReasoning(reasoning)
                            .build();

                    // 计算融合置信度
                    com.cretas.aims.service.ConfidenceCalibrationService.CalibratedConfidence calibrated =
                        confidenceCalibrationService.calibrate(factoryId, userId, intentCode, inputs);

                    if (calibrated != null) {
                        double oldConfidence = confidence;
                        confidence = calibrated.getFinalConfidence();
                        log.debug("[Confidence Calibration] intent={}, llm={}, calibrated={}, adjustment={}",
                                intentCode,
                                String.format("%.3f", llmConfidence),
                                String.format("%.3f", confidence),
                                String.format("%+.3f", confidence - oldConfidence));
                    }
                } catch (Exception e) {
                    log.warn("[Confidence Calibration] Failed: {}, using LLM confidence", e.getMessage());
                }
            }

            // 查找匹配的意图配置
            AIIntentConfig matchedConfig = null;
            if (!"UNKNOWN".equalsIgnoreCase(intentCode)) {
                matchedConfig = availableIntents.stream()
                        .filter(c -> intentCode.equalsIgnoreCase(c.getIntentCode()))
                        .findFirst()
                        .orElse(null);
            }

            // Phase 0.1 (audit follow-up): honest out-of-coverage UNKNOWN → clean
            // no-match decline. Do NOT fall through into fuzzy-match / auto-create
            // tool-calling (line ~1388), which makes a 2nd LLM call and could invoke
            // a business read tool — re-escalating an honest "I can't do that" into
            // a wrong answer. Matches the classify prompt (out-of-scope → UNKNOWN,
            // confidence≤0.3). confidence here is the raw LLM value (calibration
            // skips UNKNOWN), so it reflects the model's own out-of-scope signal.
            if ("UNKNOWN".equalsIgnoreCase(intentCode) && confidence <= 0.3) {
                log.info("[Phase0] out-of-coverage UNKNOWN (conf={}) → graceful no-match decline: '{}'",
                        confidence, truncate(userInput, 50));
                return IntentMatchResult.empty(userInput);
            }

            if (matchedConfig == null) {
                log.warn("DashScope returned unknown intent code: '{}', confidence: {}", intentCode, confidence);

                // ===== 方案 A: 高置信度时信任 LLM 判断，尝试模糊匹配 =====
                // 当 LLM 高置信度(>=0.6)理解意图但代码不在列表中时，尝试模糊匹配
                if (confidence >= 0.6 && !"UNKNOWN".equalsIgnoreCase(intentCode)) {
                    log.info("[Fuzzy Match] LLM suggested '{}' with confidence {}, attempting fuzzy match",
                            intentCode, confidence);

                    // 尝试模糊匹配：基于代码相似性或语义相似性
                    AIIntentConfig fuzzyMatch = findFuzzyMatchIntent(intentCode, availableIntents);
                    if (fuzzyMatch != null) {
                        log.info("[Fuzzy Match] Found similar intent: {} for LLM suggestion: {}",
                                fuzzyMatch.getIntentCode(), intentCode);
                        matchedConfig = fuzzyMatch;
                        // 稍微降低置信度，因为是模糊匹配
                        confidence = Math.max(0.5, confidence - 0.1);
                    } else {
                        // 尝试通过 LLM reasoning 提取可执行操作
                        if (reasoning != null && !reasoning.isEmpty()) {
                            AIIntentConfig reasoningMatch = findIntentFromReasoning(reasoning, availableIntents);
                            if (reasoningMatch != null) {
                                log.info("[Reasoning Match] Found intent from reasoning: {} for input: {}",
                                        reasoningMatch.getIntentCode(), truncate(userInput, 50));
                                matchedConfig = reasoningMatch;
                                confidence = Math.max(0.5, confidence - 0.15);
                            }
                        }
                    }
                }

                // 如果模糊匹配成功，继续处理（不返回空结果）
                if (matchedConfig != null) {
                    log.info("[Fuzzy Match Success] Proceeding with matched intent: {}", matchedConfig.getIntentCode());
                } else {
                    // 使用 Tool Calling 让 LLM 决定是否创建新意图 (替代硬编码逻辑)
                    if (autoCreateIntentEnabled && factoryId != null && shouldUseToolCalling()) {
                        log.info("[Tool Calling] Intent not matched, asking LLM whether to create new intent");
                        return tryCreateIntentViaToolCalling(userInput, availableIntents, factoryId,
                                intentCode, reasoning, confidence, userId, userRole);
                    } else if (autoCreateIntentEnabled && factoryId != null) {
                        // 降级：Tool Calling 不可用时使用旧逻辑
                        log.warn("[Legacy Mode] Tool Calling unavailable, using hardcoded logic");
                        boolean suggestionCreated = false;
                        double suggestionConfidence = 0.0;

                        if (!"UNKNOWN".equalsIgnoreCase(intentCode)) {
                            log.info("[CREATE_INTENT] LLM suggested new intent code: {} for input: {}",
                                    intentCode, truncate(userInput, 50));
                            tryCreateIntentSuggestion(factoryId, userInput, intentCode, null, reasoning, confidence);
                            suggestionCreated = true;
                            suggestionConfidence = Math.max(confidence, 0.75);  // 确保 >= 0.70
                        } else if (reasoning != null && !reasoning.isEmpty()) {
                            String generatedCode = generateIntentCodeFromInput(userInput);
                            String generatedName = generateIntentNameFromInput(userInput);
                            log.info("[CREATE_INTENT] LLM returned UNKNOWN with reasoning, generating suggestion: {} ({})",
                                    generatedCode, generatedName);
                            tryCreateIntentSuggestion(factoryId, userInput, generatedCode, generatedName, reasoning, 0.5);
                            suggestionCreated = true;
                            suggestionConfidence = 0.75;  // UNKNOWN 但有推理，设为 0.75 触发学习
                        }

                        // 如果创建了建议，返回带有置信度的结果以触发自学习
                        if (suggestionCreated) {
                            return IntentMatchResult.builder()
                                    .userInput(userInput)
                                    .confidence(suggestionConfidence)
                                    .matchMethod(MatchMethod.LLM)
                                    .isStrongSignal(false)
                                    .requiresConfirmation(true)
                                    .clarificationQuestion("已创建新的意图配置建议，等待审核。")
                                    .build();
                        }
                    }

                    return IntentMatchResult.empty(userInput);
                }
            }

            // 构建候选列表
            List<CandidateIntent> candidates = new ArrayList<>();
            candidates.add(CandidateIntent.builder()
                    .intentCode(matchedConfig.getIntentCode())
                    .intentName(matchedConfig.getIntentName())
                    .intentCategory(matchedConfig.getIntentCategory())
                    .confidence(confidence)
                    .matchScore((int) (confidence * 100))
                    .matchedKeywords(List.of())
                    .matchMethod(MatchMethod.LLM)
                    .description(matchedConfig.getDescription())
                    .build());

            // 处理其他候选
            if (json.has("other_candidates") && json.get("other_candidates").isArray()) {
                for (JsonNode candidate : json.get("other_candidates")) {
                    String code = candidate.has("intent_code") ? candidate.get("intent_code").asText() : null;
                    double candConf = candidate.has("confidence") ? candidate.get("confidence").asDouble() : 0.3;

                    if (code != null) {
                        AIIntentConfig candConfig = availableIntents.stream()
                                .filter(c -> code.equalsIgnoreCase(c.getIntentCode()))
                                .findFirst()
                                .orElse(null);

                        if (candConfig != null) {
                            candidates.add(CandidateIntent.builder()
                                    .intentCode(candConfig.getIntentCode())
                                    .intentName(candConfig.getIntentName())
                                    .intentCategory(candConfig.getIntentCategory())
                                    .confidence(candConf)
                                    .matchScore((int) (candConf * 100))
                                    .matchedKeywords(List.of())
                                    .matchMethod(MatchMethod.LLM)
                                    .description(candConfig.getDescription())
                                    .build());
                        }
                    }
                }
            }

            // 判断信号强度
            boolean isStrongSignal = confidence >= 0.8;
            String sensitivityLevel = matchedConfig.getSensitivityLevel();
            boolean requiresConfirmation = confidence < 0.7
                    || "HIGH".equals(sensitivityLevel)
                    || "CRITICAL".equals(sensitivityLevel);

            return IntentMatchResult.builder()
                    .bestMatch(matchedConfig)
                    .topCandidates(candidates)
                    .confidence(confidence)
                    .matchMethod(MatchMethod.LLM)
                    .matchedKeywords(List.of())
                    .isStrongSignal(isStrongSignal)
                    .requiresConfirmation(requiresConfirmation)
                    .userInput(userInput)
                    .clarificationQuestion(reasoning)
                    .build();

        } catch (Exception e) {
            log.error("Failed to parse DashScope response: {}", e.getMessage(), e);
            return IntentMatchResult.empty(userInput);
        }
    }

    /**
     * 检查 Python 服务是否健康
     */
    private boolean isPythonServiceHealthy() {
        try {
            return isHealthy();
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== LLM Reranking 实现 ====================

    /**
     * 对候选意图进行 LLM Reranking
     *
     * 这是两阶段检索架构的第二阶段:
     * - 第一阶段: 语义评分系统生成 Top-N 候选 (已完成)
     * - 第二阶段: LLM 对候选进行精细化重排序 (本方法)
     *
     * 使用场景: 中置信度区间 (0.58-0.85)，语义评分有把握但不确定
     *
     * @param userInput 用户输入
     * @param candidates 候选意图列表 (已按置信度排序)
     * @param factoryId 工厂ID
     * @return Reranking 结果
     */
    @Override
    public RerankingResult rerankCandidates(String userInput,
                                             List<CandidateIntent> candidates,
                                             String factoryId) {
        log.info("[Reranking] Starting reranking for input: '{}', candidates: {}",
                truncate(userInput, 50), candidates.size());

        if (candidates == null || candidates.isEmpty()) {
            log.warn("[Reranking] No candidates provided");
            return RerankingResult.failure("No candidates to rerank");
        }

        try {
            // 1. 构建 Reranking Prompt
            String systemPrompt = buildRerankingPrompt(candidates);

            // 2. 调用 LLM
            String responseText;
            if (shouldUseDashScopeDirect()) {
                log.debug("[Reranking] Using DashScope direct");
                responseText = dashScopeClient.classifyIntent(systemPrompt, userInput);
            } else {
                log.debug("[Reranking] Using Python service");
                Map<String, Object> requestBody = new HashMap<>();
                requestBody.put("system_prompt", systemPrompt);
                requestBody.put("user_input", userInput);
                requestBody.put("factory_id", factoryId);
                String responseJson = callPythonEndpoint("/api/chat/general-analysis", requestBody);
                responseText = extractResponseText(responseJson);
            }

            // 3. 解析 Reranking 结果
            return parseRerankingResponse(responseText, candidates);

        } catch (Exception e) {
            log.error("[Reranking] Failed: {}", e.getMessage(), e);
            return RerankingResult.failure(e.getMessage());
        }
    }

    /**
     * 构建 Reranking Prompt
     *
     * Prompt 设计原则:
     * 1. 告知 LLM 这是"确认"任务，不是"分类"任务
     * 2. 提供语义评分结果作为参考信号
     * 3. 包含意图描述和示例帮助理解
     * 4. 要求输出 JSON 格式便于解析
     */
    private String buildRerankingPrompt(List<CandidateIntent> candidates) {
        StringBuilder sb = new StringBuilder();

        // v6.0 CoT (Chain-of-Thought) 增强版 Prompt
        sb.append("你是一个意图确认助手。请按以下思考链分析用户输入，确定最准确的意图。\n\n");

        sb.append("## 思考步骤 (Chain-of-Thought)\n\n");
        sb.append("请按顺序回答以下问题来分析用户意图:\n\n");
        sb.append("1. **理解用户意图**: 用户想做什么? (查询/操作/统计/对比?)\n");
        sb.append("2. **识别关键实体**: 涉及什么对象? (批次/物料/设备/订单/人员?)\n");
        sb.append("3. **判断操作粒度**: 查单个还是列表? 详情还是汇总统计?\n");
        sb.append("4. **匹配候选意图**: 哪个候选最符合上述分析?\n\n");

        sb.append("## 候选意图列表 (按语义评分排序)\n\n");

        for (int i = 0; i < candidates.size(); i++) {
            CandidateIntent candidate = candidates.get(i);
            sb.append(String.format("%d. **%s** (语义评分: %.2f)\n",
                    i + 1,
                    candidate.getIntentCode(),
                    candidate.getConfidence()));

            if (candidate.getIntentName() != null) {
                sb.append(String.format("   名称: %s\n", candidate.getIntentName()));
            }
            if (candidate.getDescription() != null && !candidate.getDescription().isEmpty()) {
                sb.append(String.format("   描述: %s\n", candidate.getDescription()));
            }

            // 如果有示例查询，从数据库获取并展示
            AIIntentConfig config = findIntentConfig(candidate.getIntentCode());
            if (config != null) {
                List<String> examples = config.getExampleQueriesList();
                if (examples != null && !examples.isEmpty()) {
                    sb.append("   示例: ");
                    sb.append(examples.stream().limit(3).collect(Collectors.joining("; ")));
                    sb.append("\n");
                }
            }
            sb.append("\n");
        }

        sb.append("## 输出格式\n\n");
        sb.append("请以 JSON 格式返回，reasoning 字段必须包含上述 4 个思考步骤的分析:\n");
        sb.append("```json\n");
        sb.append("{\n");
        sb.append("  \"reasoning\": \"1.用户想... 2.涉及对象是... 3.操作粒度是... 4.因此选择...\",\n");
        sb.append("  \"selected_intent\": \"选中的意图代码\",\n");
        sb.append("  \"confidence\": 0.0-1.0 之间的置信度,\n");
        sb.append("  \"agrees_with_ranking\": true/false (是否同意语义评分排序)\n");
        sb.append("}\n");
        sb.append("```\n\n");
        sb.append("仅返回 JSON，不要包含其他文字。");

        return sb.toString();
    }

    /**
     * 解析 Reranking 响应
     */
    private RerankingResult parseRerankingResponse(String responseText, List<CandidateIntent> originalCandidates) {
        if (responseText == null || responseText.trim().isEmpty()) {
            log.warn("[Reranking] Empty response");
            return RerankingResult.failure("Empty LLM response");
        }

        try {
            // 提取 JSON (可能被 markdown 代码块包裹)
            String jsonStr = extractJsonFromMarkdown(responseText);
            JsonNode root = objectMapper.readTree(jsonStr);

            final String llmSelectedIntent = root.path("selected_intent").asText();
            double confidence = root.path("confidence").asDouble(0.0);
            String reasoning = root.path("reasoning").asText("");
            boolean agreesWithRanking = root.path("agrees_with_ranking").asBoolean(true);

            // 验证选中的意图在候选列表中
            boolean validIntent = originalCandidates.stream()
                    .anyMatch(c -> c.getIntentCode().equals(llmSelectedIntent));

            String selectedIntent;
            if (!validIntent) {
                log.warn("[Reranking] LLM selected invalid intent: {}", llmSelectedIntent);
                // 降级到第一个候选
                selectedIntent = originalCandidates.get(0).getIntentCode();
                confidence = originalCandidates.get(0).getConfidence();
                reasoning = "LLM选择无效，使用语义评分最佳结果";
                agreesWithRanking = true;
            } else {
                selectedIntent = llmSelectedIntent;
            }

            // 检查是否与原排序一致
            boolean matchesOriginal = originalCandidates.get(0).getIntentCode().equals(selectedIntent);

            log.info("[Reranking] Result: intent={}, confidence={:.2f}, agreesWithRanking={}, matchesOriginal={}",
                    selectedIntent, confidence, agreesWithRanking, matchesOriginal);

            return RerankingResult.success(selectedIntent, confidence, reasoning, matchesOriginal);

        } catch (Exception e) {
            log.error("[Reranking] Failed to parse response: {}, raw: {}",
                    e.getMessage(), truncate(responseText, 200));
            return RerankingResult.failure("Failed to parse LLM response: " + e.getMessage());
        }
    }

    /**
     * 从 markdown 代码块中提取 JSON
     */
    private String extractJsonFromMarkdown(String text) {
        if (text == null) return "{}";

        // 尝试提取 ```json ... ``` 代码块
        Pattern pattern = Pattern.compile("```(?:json)?\\s*\\n?([\\s\\S]*?)\\n?```", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }

        // 如果没有代码块，尝试直接解析
        String trimmed = text.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }

        return text;
    }

    /**
     * 根据意图代码查找配置
     */
    private AIIntentConfig findIntentConfig(String intentCode) {
        try {
            return intentConfigRepository.findByIntentCode(intentCode).orElse(null);
        } catch (Exception e) {
            log.warn("[Reranking] Failed to find intent config for: {}", intentCode);
            return null;
        }
    }

    @Override
    public String generateClarificationQuestion(String userInput,
                                                  List<CandidateIntent> candidateIntents,
                                                  String factoryId) {
        log.info("Generating clarification question: factoryId={}, candidates={}",
                 factoryId, candidateIntents.size());

        try {
            // 构建请求体
            Map<String, Object> requestBody = buildClarifyRequest(userInput, candidateIntents, factoryId);

            // 调用 Python 端点
            String responseJson = callPythonEndpoint("/api/ai/intent/clarify", requestBody);

            // 解析响应
            return parseClarifyResponse(responseJson);

        } catch (Exception e) {
            log.error("Clarification question generation failed: {}", e.getMessage(), e);
            // 返回默认问题
            return generateDefaultClarificationQuestion(candidateIntents);
        }
    }

    /**
     * 为缺失参数生成澄清问题
     *
     * 工作流程：
     * 1. 构建提示词，说明用户意图和缺失的参数
     * 2. 调用 LLM 生成自然友好的澄清问题
     * 3. 解析返回的问题列表（最多3个）
     * 4. 如果 LLM 调用失败，使用模板生成降级问题
     *
     * @param userInput 用户原始输入
     * @param intent 已匹配的意图配置
     * @param missingParameters 缺失的参数名列表
     * @param factoryId 工厂ID
     * @return 澄清问题列表（1-3个）
     */
    @Override
    public List<String> generateClarificationQuestionsForMissingParams(
            String userInput,
            AIIntentConfig intent,
            List<String> missingParameters,
            String factoryId) {

        log.info("Generating clarification questions for missing params: intent={}, missing={}",
                intent.getIntentCode(), missingParameters);

        // 参数校验
        if (missingParameters == null || missingParameters.isEmpty()) {
            log.warn("No missing parameters provided, returning empty list");
            return Collections.emptyList();
        }

        try {
            // 1. 构建提示词
            String prompt = buildClarificationPrompt(userInput, intent, missingParameters);

            // 2. 调用 LLM（优先使用 DashScope 直接调用）
            String responseText;
            if (shouldUseDashScopeDirect()) {
                log.debug("Using DashScope direct for clarification questions");
                responseText = dashScopeClient.chat(prompt, userInput);
            } else {
                log.debug("Using Python service for clarification questions");
                // 使用 Python 服务的通用接口
                Map<String, Object> requestBody = new HashMap<>();
                requestBody.put("system_prompt", prompt);
                requestBody.put("user_input", userInput);
                requestBody.put("factory_id", factoryId);

                String responseJson = callPythonEndpoint("/api/chat/general-analysis", requestBody);
                responseText = extractResponseText(responseJson);
            }

            // 3. 解析 LLM 返回的问题列表
            List<String> questions = parseClarificationQuestions(responseText);

            if (questions.isEmpty()) {
                log.warn("LLM returned empty questions, falling back to template");
                return generateTemplateClarificationQuestions(intent, missingParameters);
            }

            log.info("Successfully generated {} clarification questions via LLM", questions.size());
            return questions;

        } catch (Exception e) {
            log.error("Failed to generate clarification questions via LLM: {}", e.getMessage(), e);
            // 降级到模板生成
            return generateTemplateClarificationQuestions(intent, missingParameters);
        }
    }

    /**
     * 构建澄清问题的提示词
     *
     * 提示词要求：
     * - 口语化、自然友好
     * - 无技术术语
     * - 问题简洁明了
     * - 最多3个问题
     *
     * @param userInput 用户输入
     * @param intent 意图配置
     * @param missingParameters 缺失的参数列表
     * @return 提示词
     */
    private String buildClarificationPrompt(
            String userInput,
            AIIntentConfig intent,
            List<String> missingParameters) {

        StringBuilder sb = new StringBuilder();
        sb.append("你是一个友好的助手。用户想要「").append(intent.getIntentName()).append("」，");
        sb.append("但缺少一些必要信息。\n\n");

        sb.append("用户输入：\"").append(userInput).append("\"\n\n");

        sb.append("缺失的信息：\n");
        for (String param : missingParameters) {
            String friendlyName = getParameterFriendlyName(param);
            sb.append("- ").append(friendlyName).append(" (").append(param).append(")\n");
        }

        sb.append("\n请生成1-3个自然友好的问题，引导用户补充这些信息。\n");
        sb.append("\n要求：\n");
        sb.append("1. 问题要口语化，避免技术术语\n");
        sb.append("2. 每个问题独立一行\n");
        sb.append("3. 最多3个问题\n");
        sb.append("4. 如果缺失多个参数，可以合并成一个问题\n");
        sb.append("5. 不要编号，直接输出问题\n");
        sb.append("\n示例输出：\n");
        sb.append("请问是哪个批次的材料？\n");
        sb.append("需要更新多少数量？\n");

        return sb.toString();
    }

    /**
     * 解析 LLM 返回的澄清问题列表
     *
     * 支持的格式：
     * - 每行一个问题
     * - 可能包含编号（会自动去除）
     * - 最多返回3个问题
     *
     * @param responseText LLM 返回的文本
     * @return 问题列表
     */
    private List<String> parseClarificationQuestions(String responseText) {
        if (responseText == null || responseText.trim().isEmpty()) {
            return Collections.emptyList();
        }

        List<String> questions = new ArrayList<>();
        String[] lines = responseText.split("\\n");

        for (String line : lines) {
            String cleaned = line.trim();

            // 跳过空行
            if (cleaned.isEmpty()) {
                continue;
            }

            // 移除常见的编号格式
            cleaned = cleaned.replaceFirst("^[0-9]+[\\.、]\\s*", "");
            cleaned = cleaned.replaceFirst("^[一二三四五][\\.、]\\s*", "");
            cleaned = cleaned.replaceFirst("^[\\-\\*•]\\s*", "");

            // 确保是问句或有意义的语句
            if (cleaned.length() > 3) {
                questions.add(cleaned);

                // 最多3个问题
                if (questions.size() >= 3) {
                    break;
                }
            }
        }

        return questions;
    }

    /**
     * 生成模板澄清问题（降级方案）
     *
     * 当 LLM 调用失败时使用简单模板生成问题。
     * 使用参数友好名称，生成标准化的问题。
     *
     * @param intent 意图配置
     * @param missingParameters 缺失的参数列表
     * @return 问题列表
     */
    private List<String> generateTemplateClarificationQuestions(
            AIIntentConfig intent,
            List<String> missingParameters) {

        log.info("Using template to generate clarification questions (fallback mode)");

        List<String> questions = new ArrayList<>();

        // 如果只有1个参数，生成简单问题
        if (missingParameters.size() == 1) {
            String param = missingParameters.get(0);
            String friendlyName = getParameterFriendlyName(param);
            questions.add(String.format("请问%s是什么？", friendlyName));
            return questions;
        }

        // 多个参数：生成一个汇总问题 + 最多2个单独问题
        StringBuilder summary = new StringBuilder("请提供以下信息：");
        for (int i = 0; i < missingParameters.size(); i++) {
            String param = missingParameters.get(i);
            String friendlyName = getParameterFriendlyName(param);
            summary.append(friendlyName);
            if (i < missingParameters.size() - 1) {
                summary.append("、");
            }
        }
        questions.add(summary.toString());

        // 为前两个参数生成单独的问题
        for (int i = 0; i < Math.min(2, missingParameters.size()); i++) {
            String param = missingParameters.get(i);
            String friendlyName = getParameterFriendlyName(param);
            questions.add(String.format("具体来说，%s是多少？", friendlyName));
        }

        // 最多返回3个问题
        return questions.subList(0, Math.min(3, questions.size()));
    }

    /**
     * 获取参数的友好名称
     *
     * 将技术参数名映射为用户友好的中文名称。
     *
     * @param parameterName 参数名
     * @return 友好名称
     */
    private String getParameterFriendlyName(String parameterName) {
        if (parameterName == null) {
            return "信息";
        }

        // 参数名映射表
        Map<String, String> nameMapping = new HashMap<>();
        nameMapping.put("batchId", "批次编号");
        nameMapping.put("batchNumber", "批次编号");
        nameMapping.put("quantity", "数量");
        nameMapping.put("materialTypeId", "材料类型");
        nameMapping.put("materialType", "材料类型");
        nameMapping.put("supplierId", "供应商");
        nameMapping.put("supplier", "供应商");
        nameMapping.put("productionDate", "生产日期");
        nameMapping.put("expiryDate", "到期日期");
        nameMapping.put("warehouseId", "仓库");
        nameMapping.put("location", "位置");
        nameMapping.put("operator", "操作人");
        nameMapping.put("operatorId", "操作人");
        nameMapping.put("reason", "原因");
        nameMapping.put("remark", "备注");
        nameMapping.put("status", "状态");
        nameMapping.put("priority", "优先级");
        nameMapping.put("startDate", "开始日期");
        nameMapping.put("endDate", "结束日期");
        nameMapping.put("category", "类别");
        nameMapping.put("type", "类型");
        nameMapping.put("name", "名称");
        nameMapping.put("description", "描述");
        nameMapping.put("price", "价格");
        nameMapping.put("cost", "成本");
        nameMapping.put("weight", "重量");
        nameMapping.put("unit", "单位");
        nameMapping.put("deviceId", "设备");
        nameMapping.put("deviceCode", "设备编号");

        return nameMapping.getOrDefault(parameterName, parameterName);
    }

    /**
     * 从 Python 服务的响应中提取文本内容
     *
     * @param responseJson JSON 响应
     * @return 提取的文本
     */
    private String extractResponseText(String responseJson) {
        try {
            JsonNode json = objectMapper.readTree(responseJson);
            if (json.has("data")) {
                JsonNode data = json.get("data");
                if (data.has("response")) {
                    return data.get("response").asText();
                }
                if (data.has("text")) {
                    return data.get("text").asText();
                }
            }
            return responseJson;
        } catch (Exception e) {
            log.warn("Failed to parse response JSON, returning raw text: {}", e.getMessage());
            return responseJson;
        }
    }

    @Override
    public boolean isHealthy() {
        // 优先检查 DashScope 直接调用是否可用
        if (dashScopeConfig != null && dashScopeClient != null
                && dashScopeConfig.shouldUseDirect("intent-classify")
                && dashScopeClient.isAvailable()) {
            log.debug("LLM service healthy via DashScope direct");
            return true;
        }

        // 降级到检查本地 Python 服务
        String url = aiServiceUrl + "/api/ai/intent/health";
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            return response.isSuccessful();
        } catch (Exception e) {
            log.warn("LLM service health check failed (Python service unavailable, DashScope not configured): {}", e.getMessage());
            return false;
        }
    }

    // ==================== 请求构建 ====================

    private Map<String, Object> buildClassifyRequest(String userInput,
                                                       List<AIIntentConfig> availableIntents,
                                                       String factoryId) {
        Map<String, Object> request = new HashMap<>();
        request.put("user_input", userInput);
        request.put("factory_id", factoryId);

        // 将意图配置转换为简化格式供 LLM 理解
        List<Map<String, Object>> intents = availableIntents.stream()
                .map(this::simplifyIntentConfig)
                .collect(Collectors.toList());
        request.put("available_intents", intents);

        return request;
    }

    private Map<String, Object> simplifyIntentConfig(AIIntentConfig config) {
        Map<String, Object> simplified = new HashMap<>();
        simplified.put("intent_code", config.getIntentCode());
        simplified.put("intent_name", config.getIntentName());
        simplified.put("intent_category", config.getIntentCategory());
        simplified.put("description", config.getDescription());
        // sampleQuestions field does not exist in AIIntentConfig, using keywords instead
        simplified.put("keywords", config.getKeywords());
        return simplified;
    }

    private Map<String, Object> buildClarifyRequest(String userInput,
                                                      List<CandidateIntent> candidateIntents,
                                                      String factoryId) {
        Map<String, Object> request = new HashMap<>();
        request.put("user_input", userInput);
        request.put("factory_id", factoryId);

        // 候选意图信息
        List<Map<String, Object>> candidates = candidateIntents.stream()
                .map(c -> {
                    Map<String, Object> candidate = new HashMap<>();
                    candidate.put("intent_code", c.getIntentCode());
                    candidate.put("intent_name", c.getIntentName());
                    candidate.put("description", c.getDescription());
                    candidate.put("confidence", c.getConfidence());
                    return candidate;
                })
                .collect(Collectors.toList());
        request.put("candidate_intents", candidates);

        return request;
    }

    // ==================== HTTP 调用 (OkHttp) ====================

    private String callPythonEndpoint(String endpoint, Map<String, Object> requestBody) throws IOException {
        String url = aiServiceUrl + endpoint;
        log.debug("Calling Python endpoint: {}", url);

        // 序列化请求体
        String jsonBody = objectMapper.writeValueAsString(requestBody);
        log.debug("Request body: {}", truncate(jsonBody, 500));

        // 构建 OkHttp 请求
        RequestBody body = RequestBody.create(jsonBody, JSON_MEDIA_TYPE);
        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .addHeader("Accept", "application/json")
                .build();

        // 执行请求
        try (Response response = httpClient.newCall(request).execute()) {
            int responseCode = response.code();
            log.debug("Response code: {}", responseCode);

            ResponseBody responseBody = response.body();
            String result = responseBody != null ? responseBody.string() : "";

            if (response.isSuccessful()) {
                log.debug("Response: {}", truncate(result, 500));
                return result;
            } else {
                throw new IOException("HTTP error " + responseCode + ": " + result);
            }
        }
    }

    // ==================== 响应解析 (软 Schema 验证) ====================

    /**
     * 解析 LLM 分类响应 - 使用软 Schema 验证
     *
     * 软验证策略：
     * 1. 忽略未知字段（@JsonIgnoreProperties(ignoreUnknown = true)）
     * 2. 置信度自动 clamp 到 [0.0, 1.0]
     * 3. 缺失字段使用默认值
     * 4. 意图代码不存在时返回空结果（不抛异常）
     */
    private IntentMatchResult parseClassifyResponse(String responseJson,
                                                     String userInput,
                                                     List<AIIntentConfig> availableIntents,
                                                     String factoryId,
                                                     Long userId,
                                                     String userRole) throws IOException {
        // Step 1: 使用 DTO 反序列化（软 Schema 验证）
        LlmIntentClassifyResponse response;
        try {
            response = objectMapper.readValue(responseJson, LlmIntentClassifyResponse.class);
        } catch (JsonProcessingException e) {
            log.error("LLM response JSON parse failed: {}", e.getMessage());
            // 软验证：解析失败不抛异常，返回空结果
            return IntentMatchResult.empty(userInput);
        }

        // Step 2: 检查响应状态
        if (response.getSuccess() == null || !response.getSuccess()) {
            String message = response.getMessage() != null ? response.getMessage() : "Unknown error";
            log.warn("LLM classification returned failure: {}", message);
            return IntentMatchResult.empty(userInput);
        }

        // Step 3: 处理 data 包装（兼容两种格式）
        LlmIntentClassifyResponse data = response.getActualData();

        // Step 3b: 处理 Python ai_proxy 的 classification 字段
        // Python 返回 {"data": {"classification": "{\"intent\":\"X\",\"confidence\":0.85,...}"}}
        // classification 是 LLM 原始输出的 JSON 字符串，需要解析并提取字段
        if (data.getClassification() != null && !data.getClassification().trim().isEmpty()
                && "UNKNOWN".equals(data.getSafeIntentCode())) {
            try {
                String classificationStr = data.getClassification().trim();
                // 去除可能的 markdown 代码块包装
                if (classificationStr.startsWith("```")) {
                    classificationStr = classificationStr.replaceAll("^```(?:json)?\\s*", "")
                            .replaceAll("\\s*```$", "");
                }
                JsonNode classNode = objectMapper.readTree(classificationStr);
                if (classNode.has("intent")) {
                    data.setMatchedIntentCode(classNode.get("intent").asText("UNKNOWN"));
                }
                if (classNode.has("confidence")) {
                    data.setConfidence(classNode.get("confidence").asDouble(0.5));
                }
                if (classNode.has("reasoning")) {
                    data.setReasoning(classNode.get("reasoning").asText());
                }
                // 提取候选意图（如果有）
                if (classNode.has("other_candidates") && classNode.get("other_candidates").isArray()) {
                    List<LlmIntentClassifyResponse.CandidateResponse> candidates = objectMapper.convertValue(
                            classNode.get("other_candidates"),
                            new TypeReference<List<LlmIntentClassifyResponse.CandidateResponse>>() {});
                    data.setOtherCandidates(candidates);
                }
                log.info("[Python classify] Parsed classification string: intent={}, confidence={}",
                        data.getSafeIntentCode(), data.getSafeConfidence());
            } catch (Exception e) {
                log.warn("[Python classify] Failed to parse classification string: {}", e.getMessage());
            }
        }

        // Step 4: 使用安全 getter 提取字段（自动 clamp/default）
        String matchedIntentCode = data.getSafeIntentCode();
        Double confidence = data.getSafeConfidence();
        String reasoning = data.getReasoning();

        // Step 5: 业务验证 - 意图代码必须存在于已知列表
        AIIntentConfig matchedConfig = null;
        if (!"UNKNOWN".equals(matchedIntentCode)) {
            matchedConfig = availableIntents.stream()
                    .filter(c -> matchedIntentCode.equalsIgnoreCase(c.getIntentCode()))
                    .findFirst()
                    .orElse(null);
        }

        if (matchedConfig == null) {
            log.warn("LLM returned unknown/invalid intent code: '{}' (soft validation: returning empty result)",
                    matchedIntentCode);

            // 使用 Tool Calling 让 LLM 决定是否创建新意图 (替代硬编码逻辑)
            if (autoCreateIntentEnabled && factoryId != null && shouldUseToolCalling()) {
                log.info("[Tool Calling] Intent not matched (Python path), asking LLM whether to create new intent");
                return tryCreateIntentViaToolCalling(userInput, availableIntents, factoryId,
                        matchedIntentCode, reasoning, confidence, userId, userRole);
            } else if (autoCreateIntentEnabled) {
                // 降级：Tool Calling 不可用时使用旧逻辑
                log.warn("[Legacy Mode] Tool Calling unavailable (Python path), using hardcoded logic");
                if (!"UNKNOWN".equalsIgnoreCase(matchedIntentCode) && confidence >= autoCreateMinConfidence) {
                    tryCreateIntentSuggestion(factoryId, userInput, matchedIntentCode,
                            null, reasoning, confidence);
                } else if ("UNKNOWN".equalsIgnoreCase(matchedIntentCode) && reasoning != null && !reasoning.isEmpty()) {
                    String generatedCode = generateIntentCodeFromInput(userInput);
                    String generatedName = generateIntentNameFromInput(userInput);
                    log.info("LLM returned UNKNOWN with reasoning, generating intent suggestion: {} ({})",
                            generatedCode, generatedName);
                    tryCreateIntentSuggestion(factoryId, userInput, generatedCode,
                            generatedName, reasoning, 0.5);
                }
            }

            // 软验证：不抛 LlmSchemaValidationException，返回空结果
            return IntentMatchResult.empty(userInput);
        }

        // Step 6: 构建候选意图列表
        List<CandidateIntent> candidates = new ArrayList<>();
        candidates.add(CandidateIntent.builder()
                .intentCode(matchedConfig.getIntentCode())
                .intentName(matchedConfig.getIntentName())
                .intentCategory(matchedConfig.getIntentCategory())
                .confidence(confidence)
                .matchScore((int) (confidence * 100))
                .matchedKeywords(List.of())
                .matchMethod(MatchMethod.LLM)
                .description(matchedConfig.getDescription())
                .build());

        // Step 7: 提取其他候选（使用 DTO 的 getMergedCandidates）
        List<LlmIntentClassifyResponse.CandidateResponse> otherCandidates = data.getMergedCandidates();
        if (otherCandidates != null) {
            for (LlmIntentClassifyResponse.CandidateResponse candidate : otherCandidates) {
                String code = candidate.getIntentCode();
                if (code == null || code.trim().isEmpty()) {
                    continue;  // 软验证：跳过无效候选
                }

                Double candConfidence = candidate.getSafeConfidence();

                AIIntentConfig candConfig = availableIntents.stream()
                        .filter(c -> code.equalsIgnoreCase(c.getIntentCode()))
                        .findFirst()
                        .orElse(null);

                if (candConfig != null) {
                    candidates.add(CandidateIntent.builder()
                            .intentCode(candConfig.getIntentCode())
                            .intentName(candConfig.getIntentName())
                            .intentCategory(candConfig.getIntentCategory())
                            .confidence(candConfidence)
                            .matchScore((int) (candConfidence * 100))
                            .matchedKeywords(List.of())
                            .matchMethod(MatchMethod.LLM)
                            .description(candConfig.getDescription())
                            .build());
                }
            }
        }

        // Step 8: 判断信号强度和确认需求
        boolean isStrongSignal = confidence >= 0.8;

        String sensitivityLevel = matchedConfig.getSensitivityLevel();
        boolean requiresConfirmation = confidence < 0.7
                || "HIGH".equals(sensitivityLevel)
                || "CRITICAL".equals(sensitivityLevel);

        // Step 9: 记录软验证日志（用于监控异常模式）
        if (confidence > 0.99 && (otherCandidates == null || otherCandidates.isEmpty())) {
            log.warn("Suspicious LLM response: high confidence ({}) without alternatives for input: '{}'",
                    confidence, truncate(userInput, 30));
        }

        return IntentMatchResult.builder()
                .bestMatch(matchedConfig)
                .topCandidates(candidates)
                .confidence(confidence)
                .matchMethod(MatchMethod.LLM)
                .matchedKeywords(List.of())
                .isStrongSignal(isStrongSignal)
                .requiresConfirmation(requiresConfirmation)
                .userInput(userInput)
                .clarificationQuestion(reasoning)
                .build();
    }

    @SuppressWarnings("unchecked")
    private String parseClarifyResponse(String responseJson) throws IOException {
        Map<String, Object> response = objectMapper.readValue(responseJson,
                new TypeReference<Map<String, Object>>() {});

        Boolean success = (Boolean) response.get("success");
        if (success == null || !success) {
            return null;
        }

        Map<String, Object> data = (Map<String, Object>) response.get("data");
        if (data == null) {
            return null;
        }

        return (String) data.get("clarification_question");
    }

    // ==================== 工具方法 ====================

    private String generateDefaultClarificationQuestion(List<CandidateIntent> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return "请问您具体想要执行什么操作？";
        }

        StringBuilder sb = new StringBuilder("请问您想要：\n");
        for (int i = 0; i < Math.min(candidates.size(), 3); i++) {
            CandidateIntent candidate = candidates.get(i);
            sb.append(String.format("%d. %s (%s)\n", i + 1, candidate.getIntentName(), candidate.getDescription()));
        }
        sb.append("\n请选择或描述您的需求。");
        return sb.toString();
    }

    private String truncate(String str, int maxLength) {
        if (str == null) return "null";
        if (str.length() <= maxLength) return str;
        return str.substring(0, maxLength) + "...";
    }

    // ==================== 新意图建议生成 (自学习核心) ====================

    /**
     * 尝试创建「新意图」建议
     *
     * 当 LLM 返回的意图代码不在已有配置中时，说明 LLM 识别到了一个潜在的新意图模式。
     *
     * 差异化审批机制：
     * - 工厂级意图 (factoryId 不为 PLATFORM/null)：自动审批并创建，无需人工审核
     * - 平台级意图 (factoryId 为 PLATFORM 或晋升请求)：需要平台管理员审批
     *
     * 此方法会：
     * 1. 检查是否已存在相同 suggestedIntentCode 的待处理建议
     * 2. 如果存在，累加 frequency 并更新样例
     * 3. 如果不存在：
     *    a. 工厂级 + 自动审批开启 → 直接创建意图配置
     *    b. 否则 → 创建待审核的 CREATE_INTENT 建议
     *
     * @param factoryId       工厂ID
     * @param userInput       用户输入
     * @param suggestedCode   LLM 建议的意图代码
     * @param suggestedName   LLM 建议的意图名称 (可能为 null)
     * @param reasoning       LLM 推理说明
     * @param confidence      LLM 置信度
     */
    @Transactional
    protected void tryCreateIntentSuggestion(String factoryId,
                                              String userInput,
                                              String suggestedCode,
                                              String suggestedName,
                                              String reasoning,
                                              double confidence) {
        if (suggestionRepository == null) {
            log.debug("Suggestion repository not available, skipping CREATE_INTENT suggestion");
            return;
        }

        try {
            // 检查是否已有相同 suggestedIntentCode 的待处理建议
            List<IntentOptimizationSuggestion> existing = suggestionRepository
                    .findPendingCreateIntentSuggestion(factoryId, suggestedCode);

            if (!existing.isEmpty()) {
                // 已存在：累加频率
                IntentOptimizationSuggestion suggestion = existing.get(0);
                suggestion.incrementFrequency(userInput);
                suggestionRepository.save(suggestion);
                log.info("Updated existing CREATE_INTENT suggestion: code={}, frequency={}",
                        suggestedCode, suggestion.getFrequency());
            } else {
                // 不存在：根据工厂级/平台级决定处理方式
                String name = suggestedName != null ? suggestedName : suggestedCode;
                String category = inferCategory(suggestedCode);
                String keywords = extractKeywords(userInput);

                // 判断是否是工厂级意图 (可以自动审批)
                boolean isFactoryLevel = isFactoryLevelIntent(factoryId);
                boolean shouldAutoApprove = isFactoryLevel && factoryAutoApproveEnabled && intentConfigRepository != null;

                if (shouldAutoApprove) {
                    // 工厂级意图自动审批：直接创建意图配置
                    autoApproveAndCreateIntent(factoryId, suggestedCode, name, keywords, category,
                                                userInput, reasoning, confidence);
                } else {
                    // 平台级意图或自动审批关闭：创建待审核建议
                    IntentOptimizationSuggestion suggestion = IntentOptimizationSuggestion.createNewIntentSuggestion(
                            factoryId,
                            userInput,
                            suggestedCode,
                            name,
                            keywords,
                            category,
                            confidence,
                            reasoning != null ? reasoning : "LLM 自动识别的新意图模式"
                    );

                    suggestionRepository.save(suggestion);
                    log.info("Created new CREATE_INTENT suggestion (pending review): factoryId={}, code={}, confidence={}",
                            factoryId, suggestedCode, confidence);
                }
            }
        } catch (Exception e) {
            log.error("Failed to create intent suggestion: {}", e.getMessage(), e);
            // 不阻断主流程
        }
    }

    /**
     * 判断是否是工厂级意图
     * 工厂级意图的 factoryId 是具体的工厂ID (如 F001)，不是 PLATFORM 或 null
     */
    private boolean isFactoryLevelIntent(String factoryId) {
        if (factoryId == null || factoryId.trim().isEmpty()) {
            return false;
        }
        String fid = factoryId.trim().toUpperCase();
        return !fid.equals("PLATFORM") && !fid.equals("GLOBAL") && !fid.equals("SYSTEM");
    }

    /**
     * 自动审批并创建工厂级意图
     *
     * 当工厂级意图自动审批开启时，直接创建意图配置：
     * 1. 创建 AIIntentConfig 实体
     * 2. 保存到数据库
     * 3. 创建一个已审批的建议记录 (用于审计追踪)
     */
    private void autoApproveAndCreateIntent(String factoryId,
                                             String intentCode,
                                             String intentName,
                                             String keywords,
                                             String category,
                                             String userInput,
                                             String reasoning,
                                             double confidence) {
        try {
            // 1. 检查意图代码是否已存在
            if (intentConfigRepository.existsByFactoryIdAndIntentCode(factoryId, intentCode)) {
                log.warn("Intent code already exists, skipping auto-create: factoryId={}, code={}",
                        factoryId, intentCode);
                return;
            }

            // 2. 创建新的意图配置
            AIIntentConfig newIntent = AIIntentConfig.builder()
                    .factoryId(factoryId)
                    .intentCode(intentCode)
                    .intentName(intentName)
                    .keywords(keywords)  // keywords is already a JSON string
                    .intentCategory(category)
                    .priority(50)  // 默认中等优先级
                    .isActive(true)
                    .description("由AI自学习机制自动创建 (工厂级自动审批)。LLM推理: " +
                                (reasoning != null ? reasoning : "自动识别的新意图模式"))
                    .build();

            intentConfigRepository.save(newIntent);
            log.info("[AUTO-APPROVED] Created factory-level intent: factoryId={}, code={}, name={}, confidence={}",
                    factoryId, intentCode, intentName, confidence);

            // 3. 创建已审批的建议记录 (用于审计追踪)
            IntentOptimizationSuggestion suggestion = IntentOptimizationSuggestion.createNewIntentSuggestion(
                    factoryId,
                    userInput,
                    intentCode,
                    intentName,
                    keywords,
                    category,
                    confidence,
                    reasoning != null ? reasoning : "LLM 自动识别的新意图模式"
            );
            suggestion.setStatus(SuggestionStatus.APPLIED);
            suggestion.setApprovalNotes("工厂级意图自动审批 - 由系统自动创建");
            suggestionRepository.save(suggestion);

        } catch (Exception e) {
            log.error("Failed to auto-approve and create intent: factoryId={}, code={}, error={}",
                    factoryId, intentCode, e.getMessage(), e);
            // 降级：创建待审核的建议
            log.info("Falling back to pending suggestion due to auto-approve failure");
            IntentOptimizationSuggestion fallbackSuggestion = IntentOptimizationSuggestion.createNewIntentSuggestion(
                    factoryId, userInput, intentCode, intentName, keywords, category, confidence,
                    reasoning != null ? reasoning : "LLM 自动识别的新意图模式 (自动审批失败，需人工审核)"
            );
            suggestionRepository.save(fallbackSuggestion);
        }
    }

    /**
     * 解析关键词 JSON 字符串为 List
     */
    private List<String> parseKeywordsList(String keywordsJson) {
        if (keywordsJson == null || keywordsJson.trim().isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(keywordsJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse keywords JSON: {}", keywordsJson);
            // 尝试将整个字符串作为单个关键词
            String cleaned = keywordsJson.replaceAll("[\\[\\]\"']", "").trim();
            return cleaned.isEmpty() ? new ArrayList<>() : List.of(cleaned);
        }
    }

    /**
     * 从意图代码推断分类
     */
    private String inferCategory(String intentCode) {
        if (intentCode == null) return "UNKNOWN";

        String code = intentCode.toUpperCase();
        if (code.contains("QUERY") || code.contains("LIST") || code.contains("GET") || code.contains("SEARCH")) {
            return "DATA_QUERY";
        } else if (code.contains("CREATE") || code.contains("ADD") || code.contains("NEW") || code.contains("SAVE")) {
            return "DATA_OP";
        } else if (code.contains("UPDATE") || code.contains("MODIFY") || code.contains("EDIT")) {
            return "DATA_OP";
        } else if (code.contains("DELETE") || code.contains("REMOVE")) {
            return "DATA_OP";
        } else if (code.contains("REPORT") || code.contains("ANALYSIS") || code.contains("STATS")) {
            return "ANALYSIS";
        } else if (code.contains("ALERT") || code.contains("WARN") || code.contains("NOTIFY")) {
            return "ALERT";
        } else if (code.contains("SCHEDULE") || code.contains("PLAN") || code.contains("TASK")) {
            return "SCHEDULE";
        } else if (code.contains("CONFIG") || code.contains("SETTING") || code.contains("SETUP")) {
            return "CONFIG";
        } else if (code.contains("FORM") || code.contains("INPUT") || code.contains("SUBMIT")) {
            return "FORM";
        } else {
            return "GENERAL";
        }
    }

    /**
     * 从用户输入提取关键词作为初始建议
     */
    private String extractKeywords(String userInput) {
        if (userInput == null || userInput.trim().isEmpty()) {
            return "[]";
        }

        // 简单实现：将整句作为一个关键词
        // 更复杂的实现可以使用分词
        String escaped = userInput.replace("\"", "\\\"").replace("\n", " ").trim();
        return "[\"" + escaped + "\"]";
    }

    // ==================== UNKNOWN 意图处理 (自学习增强) ====================

    /**
     * 从用户输入生成建议的意图代码
     * 用于 LLM 返回 UNKNOWN 时，自动生成一个候选意图代码
     *
     * 规则：
     * 1. 提取关键动词 (查询/分析/创建/更新/删除 等)
     * 2. 提取关键名词 (碳排放/能耗/环保 等)
     * 3. 组合成 UPPER_SNAKE_CASE 格式
     *
     * @param userInput 用户输入
     * @return 生成的意图代码，如 "CARBON_FOOTPRINT_QUERY"
     */
    private String generateIntentCodeFromInput(String userInput) {
        if (userInput == null || userInput.trim().isEmpty()) {
            return "NEW_INTENT_" + System.currentTimeMillis() % 10000;
        }

        String input = userInput.trim().toLowerCase();

        // 动词映射
        String action = "QUERY";  // 默认动作
        if (input.contains("分析") || input.contains("统计") || input.contains("报表")) {
            action = "ANALYSIS";
        } else if (input.contains("查询") || input.contains("查看") || input.contains("获取") || input.contains("看看")) {
            action = "QUERY";
        } else if (input.contains("创建") || input.contains("新建") || input.contains("添加") || input.contains("录入")) {
            action = "CREATE";
        } else if (input.contains("修改") || input.contains("更新") || input.contains("编辑") || input.contains("调整")) {
            action = "UPDATE";
        } else if (input.contains("删除") || input.contains("移除") || input.contains("取消")) {
            action = "DELETE";
        } else if (input.contains("导出") || input.contains("下载") || input.contains("生成")) {
            action = "EXPORT";
        } else if (input.contains("设置") || input.contains("配置") || input.contains("管理")) {
            action = "CONFIG";
        }

        // 主题提取 (简单规则，提取关键词)
        String subject = extractSubjectFromInput(input);

        return subject.toUpperCase() + "_" + action;
    }

    /**
     * 从用户输入提取主题名词
     */
    private String extractSubjectFromInput(String input) {
        // 常见主题词映射
        if (input.contains("碳排放") || input.contains("碳足迹") || input.contains("碳")) {
            return "CARBON_FOOTPRINT";
        } else if (input.contains("环保") || input.contains("合规") || input.contains("环境")) {
            return "ENVIRONMENTAL_COMPLIANCE";
        } else if (input.contains("能源") || input.contains("能耗") || input.contains("电")) {
            return "ENERGY_CONSUMPTION";
        } else if (input.contains("成本") || input.contains("费用") || input.contains("预算")) {
            return "COST";
        } else if (input.contains("供应商") || input.contains("供货商")) {
            return "SUPPLIER";
        } else if (input.contains("客户") || input.contains("顾客")) {
            return "CUSTOMER";
        } else if (input.contains("库存") || input.contains("存货")) {
            return "INVENTORY";
        } else if (input.contains("质量") || input.contains("品质")) {
            return "QUALITY";
        } else if (input.contains("安全") || input.contains("风险")) {
            return "SAFETY";
        } else if (input.contains("人员") || input.contains("员工") || input.contains("人力")) {
            return "HR";
        } else if (input.contains("设备") || input.contains("机器") || input.contains("机械")) {
            return "EQUIPMENT";
        } else if (input.contains("物流") || input.contains("运输") || input.contains("配送")) {
            return "LOGISTICS";
        } else if (input.contains("订单") || input.contains("采购")) {
            return "ORDER";
        } else if (input.contains("仓库") || input.contains("仓储")) {
            return "WAREHOUSE";
        } else {
            // 无法识别：使用时间戳生成唯一代码
            return "CUSTOM_" + (System.currentTimeMillis() % 100000);
        }
    }

    /**
     * 从用户输入生成建议的意图名称
     * 用于 CREATE_INTENT 建议的显示名称
     *
     * @param userInput 用户输入
     * @return 生成的意图名称，如 "碳排放足迹查询"
     */
    private String generateIntentNameFromInput(String userInput) {
        if (userInput == null || userInput.trim().isEmpty()) {
            return "新意图";
        }

        String input = userInput.trim();

        // 截取前20个字符作为名称基础
        String baseName = input.length() > 20 ? input.substring(0, 20) + "..." : input;

        // 清理特殊字符
        baseName = baseName.replaceAll("[\\[\\]{}\"'\\\\]", "").trim();

        return "新功能: " + baseName;
    }

    // ==================== 方案 A: 模糊匹配辅助方法 ====================

    /**
     * 模糊匹配意图配置
     * 当 LLM 返回的 intent_code 不在列表中但语义相近时，尝试找到最接近的意图
     *
     * 匹配策略：
     * 1. 代码前缀匹配（如 MATERIAL_BATCH_XXX → MATERIAL_BATCH_QUERY）
     * 2. 关键动词匹配（查询→QUERY, 创建→CREATE, 列表→LIST）
     * 3. 领域关键词匹配（原材料→MATERIAL, 质检→QUALITY, 考勤→ATTENDANCE）
     */
    private AIIntentConfig findFuzzyMatchIntent(String suggestedCode, List<AIIntentConfig> availableIntents) {
        if (suggestedCode == null || suggestedCode.isEmpty()) {
            return null;
        }

        String upperCode = suggestedCode.toUpperCase();

        // 策略1: 前缀匹配 - 找到相同前缀的意图
        String[] parts = upperCode.split("_");
        if (parts.length >= 2) {
            String prefix = parts[0] + "_" + parts[1]; // 如 MATERIAL_BATCH

            for (AIIntentConfig intent : availableIntents) {
                if (intent.getIntentCode().toUpperCase().startsWith(prefix)) {
                    log.debug("[Fuzzy Match] Prefix match: {} → {}", suggestedCode, intent.getIntentCode());
                    return intent;
                }
            }
        }

        // 策略2: 动词映射
        Map<String, String> verbMappings = Map.ofEntries(
                Map.entry("查", "_QUERY"), Map.entry("查询", "_QUERY"),
                Map.entry("搜索", "_SEARCH"), Map.entry("找", "_SEARCH"),
                Map.entry("列表", "_LIST"), Map.entry("所有", "_LIST"), Map.entry("全部", "_LIST"),
                Map.entry("添加", "_CREATE"), Map.entry("新增", "_CREATE"), Map.entry("创建", "_CREATE"),
                Map.entry("修改", "_UPDATE"), Map.entry("更新", "_UPDATE"), Map.entry("编辑", "_UPDATE"),
                Map.entry("删除", "_DELETE"), Map.entry("移除", "_DELETE")
        );

        for (Map.Entry<String, String> entry : verbMappings.entrySet()) {
            if (upperCode.contains(entry.getValue().substring(1))) { // 去掉前缀下划线比较
                // 找到包含该动词后缀的意图
                for (AIIntentConfig intent : availableIntents) {
                    if (intent.getIntentCode().toUpperCase().endsWith(entry.getValue())) {
                        // 如果领域前缀也匹配，优先返回
                        if (parts.length > 0 && intent.getIntentCode().toUpperCase().startsWith(parts[0])) {
                            log.debug("[Fuzzy Match] Verb+Domain match: {} → {}", suggestedCode, intent.getIntentCode());
                            return intent;
                        }
                    }
                }
            }
        }

        // 策略3: 领域关键词映射
        Map<String, String> domainMappings = Map.ofEntries(
                Map.entry("MATERIAL", "MATERIAL_BATCH_QUERY"),
                Map.entry("原材料", "MATERIAL_BATCH_QUERY"),
                Map.entry("原料", "MATERIAL_BATCH_QUERY"),
                Map.entry("QUALITY", "QUALITY_CHECK_QUERY"),
                Map.entry("质检", "QUALITY_CHECK_QUERY"),
                Map.entry("ATTENDANCE", "ATTENDANCE_TODAY"),
                Map.entry("考勤", "ATTENDANCE_TODAY"),
                Map.entry("打卡", "CLOCK_IN"),
                Map.entry("SCALE", "SCALE_LIST_DEVICES"),
                Map.entry("电子秤", "SCALE_LIST_DEVICES"),
                Map.entry("秤", "SCALE_LIST_DEVICES"),
                Map.entry("ALERT", "ALERT_LIST"),
                Map.entry("告警", "ALERT_LIST"),
                Map.entry("SUPPLIER", "SUPPLIER_LIST"),
                Map.entry("供应商", "SUPPLIER_LIST"),
                Map.entry("CUSTOMER", "CUSTOMER_LIST"),
                Map.entry("客户", "CUSTOMER_LIST"),
                Map.entry("REPORT", "REPORT_DASHBOARD_OVERVIEW"),
                Map.entry("报表", "REPORT_DASHBOARD_OVERVIEW")
        );

        for (Map.Entry<String, String> entry : domainMappings.entrySet()) {
            if (upperCode.contains(entry.getKey().toUpperCase())) {
                String targetCode = entry.getValue();
                AIIntentConfig match = availableIntents.stream()
                        .filter(i -> i.getIntentCode().equalsIgnoreCase(targetCode))
                        .findFirst()
                        .orElse(null);
                if (match != null) {
                    log.debug("[Fuzzy Match] Domain keyword match: {} → {}", suggestedCode, match.getIntentCode());
                    return match;
                }
            }
        }

        return null;
    }

    /**
     * 从 LLM reasoning 中提取意图
     * 分析 LLM 的推理文本，找到它实际理解的意图
     *
     * 示例 reasoning:
     * - "用户想要上班打卡" → CLOCK_IN
     * - "用户询问原材料库存情况" → MATERIAL_BATCH_QUERY
     */
    private AIIntentConfig findIntentFromReasoning(String reasoning, List<AIIntentConfig> availableIntents) {
        if (reasoning == null || reasoning.isEmpty()) {
            return null;
        }

        // 关键词到意图代码的映射
        Map<String, String> keywordToIntent = Map.ofEntries(
                // 考勤相关
                Map.entry("打卡", "CLOCK_IN"),
                Map.entry("签到", "CLOCK_IN"),
                Map.entry("上班", "CLOCK_IN"),
                Map.entry("签退", "CLOCK_OUT"),
                Map.entry("下班", "CLOCK_OUT"),
                Map.entry("考勤", "ATTENDANCE_TODAY"),
                Map.entry("出勤", "ATTENDANCE_TODAY"),
                // 原材料相关
                Map.entry("原材料", "MATERIAL_BATCH_QUERY"),
                Map.entry("原料", "MATERIAL_BATCH_QUERY"),
                Map.entry("库存", "MATERIAL_BATCH_QUERY"),
                Map.entry("领用", "MATERIAL_BATCH_USE"),
                // 质检相关
                Map.entry("质检", "QUALITY_CHECK_QUERY"),
                Map.entry("检测", "QUALITY_CHECK_EXECUTE"),
                Map.entry("质量", "QUALITY_CHECK_QUERY"),
                // 设备相关
                Map.entry("电子秤", "SCALE_LIST_DEVICES"),
                Map.entry("称重设备", "SCALE_LIST_DEVICES"),
                Map.entry("秤", "SCALE_LIST_DEVICES"),
                Map.entry("设备", "SCALE_LIST_DEVICES"),
                // 告警相关
                Map.entry("告警", "ALERT_LIST"),
                Map.entry("警报", "ALERT_LIST"),
                Map.entry("异常", "ALERT_ACTIVE"),
                // 供应商/客户
                Map.entry("供应商", "SUPPLIER_LIST"),
                Map.entry("供货", "SUPPLIER_LIST"),
                Map.entry("客户", "CUSTOMER_LIST"),
                Map.entry("买家", "CUSTOMER_LIST"),
                // 报表
                Map.entry("报表", "REPORT_DASHBOARD_OVERVIEW"),
                Map.entry("报告", "REPORT_DASHBOARD_OVERVIEW"),
                Map.entry("统计", "REPORT_DASHBOARD_OVERVIEW"),
                Map.entry("数据", "REPORT_DASHBOARD_OVERVIEW"),
                // 生产
                Map.entry("生产", "PRODUCTION_PLAN_LIST"),
                Map.entry("加工", "PROCESSING_BATCH_LIST"),
                Map.entry("批次", "PROCESSING_BATCH_LIST"),
                // 溯源
                Map.entry("溯源", "TRACE_BATCH"),
                Map.entry("追溯", "TRACE_BATCH")
        );

        // 在 reasoning 中查找关键词
        for (Map.Entry<String, String> entry : keywordToIntent.entrySet()) {
            if (reasoning.contains(entry.getKey())) {
                String targetCode = entry.getValue();
                AIIntentConfig match = availableIntents.stream()
                        .filter(i -> i.getIntentCode().equalsIgnoreCase(targetCode))
                        .findFirst()
                        .orElse(null);
                if (match != null) {
                    log.debug("[Reasoning Match] Found keyword '{}' → intent: {}", entry.getKey(), match.getIntentCode());
                    return match;
                }
            }
        }

        return null;
    }

    // ==================== Tool Calling 流程 (新架构) ====================

    /**
     * 检查是否应该使用 Tool Calling 模式
     *
     * 条件：
     * 1. ToolRegistry 可用
     * 2. DashScopeClient 可用
     * 3. create_new_intent 工具已注册
     *
     * @return true 表示可以使用 Tool Calling
     */
    private boolean shouldUseToolCalling() {
        return toolRegistry != null
                && dashScopeClient != null
                && dashScopeClient.isAvailable()
                && toolRegistry.hasExecutor("create_new_intent");
    }

    /**
     * 通过 Tool Calling 创建新意图（新架构）
     *
     * 工作流程：
     * 1. 构建系统提示词，说明当前情况（意图不匹配）
     * 2. 从 ToolRegistry 获取工具定义列表
     * 3. 调用 DashScopeClient.chatCompletion() 带上 tools 参数
     * 4. 检查响应中的 tool_calls
     * 5. 如果有 tool_calls，执行对应的 ToolExecutor
     * 6. 返回执行结果
     *
     * @param userInput 用户输入
     * @param availableIntents 当前可用意图列表
     * @param factoryId 工厂ID
     * @param suggestedIntentCode LLM 首次建议的意图代码
     * @param reasoning LLM 推理说明
     * @param confidence 置信度
     * @return 意图匹配结果
     */
    private IntentMatchResult tryCreateIntentViaToolCalling(
            String userInput,
            List<AIIntentConfig> availableIntents,
            String factoryId,
            String suggestedIntentCode,
            String reasoning,
            double confidence,
            Long userId,
            String userRole) {

        try {
            log.debug("[Tool Calling] Starting tool calling workflow for intent creation");

            // 1. 构建系统提示词
            String systemPrompt = buildToolCallingSystemPrompt(
                    userInput,
                    availableIntents,
                    suggestedIntentCode,
                    reasoning
            );

            // 2. 获取可用工具列表（D8v2: 领域过滤，136→20~30 工具）
            List<Tool> tools = getFilteredToolsForQuery(userInput, suggestedIntentCode, reasoning, userRole);
            log.debug("[Tool Calling] Available tools: {}",
                    tools.stream().map(t -> t.getFunction().getName()).collect(Collectors.toList()));

            // 3. 构建请求
            ChatCompletionRequest request = ChatCompletionRequest.builder()
                    .model(dashScopeConfig.getModel())
                    .messages(List.of(
                            ChatMessage.system(systemPrompt),
                            ChatMessage.user(userInput)
                    ))
                    .tools(tools)
                    .toolChoice("auto")  // 让 LLM 自主决定是否调用工具
                    .maxTokens(dashScopeConfig.getMaxTokens())
                    .temperature(dashScopeConfig.getLowTemperature())  // 使用低温度确保输出稳定
                    .build();

            // 4. 调用 LLM
            log.info("[Tool Calling] Calling DashScope with {} tools (role: {})", tools.size(), userRole);
            ChatCompletionResponse response = dashScopeClient.chatCompletion(request);

            if (response.hasError()) {
                log.error("[Tool Calling] DashScope API error: {}", response.getErrorMessage());
                return IntentMatchResult.empty(userInput);
            }

            // 5. 检查 tool_calls
            List<ToolCall> toolCalls = extractToolCalls(response);
            if (toolCalls == null || toolCalls.isEmpty()) {
                log.info("[Tool Calling] LLM decided NOT to create new intent");
                // LLM 认为不需要创建新意图，返回空结果
                return IntentMatchResult.empty(userInput);
            }

            // 6. 执行工具调用
            log.info("[Tool Calling] LLM requested {} tool calls", toolCalls.size());
            for (ToolCall toolCall : toolCalls) {
                String toolName = toolCall.getFunction().getName();
                log.info("[Tool Calling] Executing tool: {}", toolName);

                // 获取工具执行器
                Optional<ToolExecutor> executorOpt = toolRegistry.getExecutor(toolName);
                if (!executorOpt.isPresent()) {
                    log.warn("[Tool Calling] Tool executor not found: {}", toolName);
                    continue;
                }

                ToolExecutor executor = executorOpt.get();

                // 构建执行上下文（包含 userId 和 userRole 用于权限验证）
                Map<String, Object> context = buildToolExecutionContext(factoryId, userId, userRole);

                // W0 write-guard (intent-w0) — SITE F: this LLM-fallback path calls executor.execute()
                // directly on an LLM-selected tool, bypassing ToolDispatchService (Site B). The fallback
                // has NO user confirmation step, so a write tool here is unconfirmed by construction —
                // block it. isConfirmed(context) is false (buildToolExecutionContext sets no confirm
                // flag); pass Map.of() defensively so a future confirm flag cannot leak through.
                if (writeGuardService.isWriteTool(executor)
                        && !writeGuardService.isConfirmed(java.util.Map.of())) {
                    log.warn("W0 write-guard (llm-fallback): blocked write tool {} (no confirmation in LLM tool-calling path)", toolName);
                    throw new IllegalStateException("W0 write-guard: tool '" + toolName + "' 需要显式确认后才能执行");
                }

                // W9 红线 (AI-RBAC 系统性收口) — SITE F: LLM-fallback path bypasses Site B. Central RBAC
                // enforce on the LLM-selected tool. context (buildToolExecutionContext) carries userId.
                if (!toolRbacEnforcer.isAllowed(executor, context)) {
                    log.warn("W9 AI-RBAC (llm-fallback): denied tool={}", toolName);
                    throw new IllegalStateException(toolRbacEnforcer.denyMessage(executor, context));
                }

                // 执行工具
                String result = executor.execute(toolCall, context);
                log.info("[Tool Calling] Tool execution result: {}", truncate(result, 200));

                // 返回结果，置信度设为 0.75 以触发自学习机制
                // 自学习阈值为 0.70，Tool Calling 成功创建意图建议后应触发学习
                return IntentMatchResult.builder()
                        .userInput(userInput)
                        .confidence(0.75)  // 设置 >= 0.70 以触发自学习
                        .matchMethod(MatchMethod.LLM)
                        .isStrongSignal(false)
                        .requiresConfirmation(true)
                        .clarificationQuestion("已创建新的意图配置，等待管理员审核激活后即可使用。")
                        .build();
            }

            return IntentMatchResult.empty(userInput);

        } catch (Exception e) {
            log.error("[Tool Calling] Failed to execute tool calling workflow: {}", e.getMessage(), e);
            // 降级：返回空结果，不阻断主流程
            return IntentMatchResult.empty(userInput);
        }
    }

    /**
     * D8v2: 根据用户查询的领域过滤工具列表。
     *
     * <p>策略：
     * 1. 从 suggestedIntentCode 推断主领域
     * 2. 从 userInput 关键词推断领域 + 次要领域
     * 3. 展开关联领域（如 QUALITY → REPORT + PROCESSING）
     * 4. 收集匹配领域的工具前缀 + 始终包含的元工具
     * 5. 如果过滤结果太少（<5），回退到角色过滤</p>
     *
     * @return 过滤后的工具列表（通常 15~30 个）
     */
    private List<Tool> getFilteredToolsForQuery(String userInput, String suggestedIntentCode, String reasoning, String userRole) {
        if (intentKnowledgeBase == null || toolRegistry == null) {
            return userRole != null
                    ? toolRegistry.getToolDefinitionsForRole(userRole)
                    : toolRegistry.getAllToolDefinitions();
        }

        // 1. 收集所有匹配的领域（不仅仅是得分最高的那个）
        Set<IntentKnowledgeBase.Domain> domains = new HashSet<>();

        // 从 suggestedIntentCode 推断
        if (suggestedIntentCode != null && !suggestedIntentCode.isEmpty()) {
            IntentKnowledgeBase.Domain d = intentKnowledgeBase.getDomainFromIntentCode(suggestedIntentCode);
            if (d != IntentKnowledgeBase.Domain.GENERAL) {
                domains.add(d);
            }
        }

        // 从 userInput 关键词推断（多领域匹配）
        domains.addAll(detectAllDomains(userInput));

        // D8v2 fix: 预处理器可能去掉原始关键词（如"帮我查一下本月各产线的良品率对比"→"查产线"），
        // 用 originalInput（通过 ThreadLocal 传入）和 reasoning 补充领域检测
        String origInput = originalInputHolder.get();
        if (origInput != null && !origInput.equals(userInput)) {
            Set<IntentKnowledgeBase.Domain> origDomains = detectAllDomains(origInput);
            if (!origDomains.isEmpty()) {
                log.info("[Tool Calling] D8v2 originalInput='{}' detected additional domains: {}", truncate(origInput, 50), origDomains);
                domains.addAll(origDomains);
            }
        }
        if (reasoning != null && !reasoning.isEmpty()) {
            domains.addAll(detectAllDomains(reasoning));
        }

        // 如果没匹配到任何领域，回退
        if (domains.isEmpty()) {
            log.info("[Tool Calling] No domain detected, falling back to role-based filtering (role: {})", userRole);
            return userRole != null
                    ? toolRegistry.getToolDefinitionsForRole(userRole)
                    : toolRegistry.getAllToolDefinitions();
        }

        // 2. 收集工具前缀（直接使用检测到的领域，不做关联扩展以保持工具集精简）
        Set<String> toolPrefixes = new HashSet<>();
        for (IntentKnowledgeBase.Domain d : domains) {
            Set<String> prefixes = DOMAIN_TOOL_PREFIXES.get(d);
            if (prefixes != null) {
                toolPrefixes.addAll(prefixes);
            }
        }

        // 3. 按前缀过滤
        List<Tool> filtered = toolRegistry.getToolDefinitionsForDomains(toolPrefixes, META_TOOL_PREFIXES, userRole);

        log.info("[Tool Calling] Domains={}, filtered {} tools (from {}), prefixes={}",
                domains, filtered.size(), toolRegistry.getToolCount(), toolPrefixes);

        // 5. 安全检查
        if (filtered.size() < 5) {
            log.warn("[Tool Calling] Too few tools after domain filter ({}), falling back to role-based", filtered.size());
            return userRole != null
                    ? toolRegistry.getToolDefinitionsForRole(userRole)
                    : toolRegistry.getAllToolDefinitions();
        }

        return applyFactoryToolFilter(filtered);
    }

    /**
     * Canvas V2: Remove tools disabled for the current factory.
     * Uses factoryIdHolder ThreadLocal set in classifyIntent().
     */
    private List<Tool> applyFactoryToolFilter(List<Tool> tools) {
        String factoryId = factoryIdHolder.get();
        if (factoryId == null || factoryId.isBlank()) return tools;

        // Get factory-enabled tools (returns all if no config exists)
        List<Tool> factoryTools = toolRegistry.getToolsForFactory(factoryId);
        Set<String> enabledNames = factoryTools.stream()
                .map(t -> t.getFunction().getName())
                .collect(java.util.stream.Collectors.toSet());

        List<Tool> result = tools.stream()
                .filter(t -> enabledNames.contains(t.getFunction().getName()))
                .collect(java.util.stream.Collectors.toList());

        if (result.size() < tools.size()) {
            log.info("[Canvas V2] Factory {} tool filter: {} → {} tools",
                    factoryId, tools.size(), result.size());
        }
        return result;
    }

    /**
     * 检测用户输入中所有匹配的领域（而非仅得分最高的那个）。
     * 例如 "帮我查各产线的良品率" 同时匹配 PROCESSING（生产）和 QUALITY（良品率）。
     */
    private Set<IntentKnowledgeBase.Domain> detectAllDomains(String userInput) {
        if (userInput == null || userInput.trim().isEmpty()) {
            return Collections.emptySet();
        }

        String normalized = userInput.trim().toLowerCase();
        Set<IntentKnowledgeBase.Domain> matched = new HashSet<>();

        for (IntentKnowledgeBase.Domain domain : IntentKnowledgeBase.Domain.values()) {
            if (domain == IntentKnowledgeBase.Domain.GENERAL) continue;

            Set<String> keywords = intentKnowledgeBase.getDomainKeywords(domain);
            for (String keyword : keywords) {
                if (normalized.contains(keyword.toLowerCase())) {
                    matched.add(domain);
                    log.debug("[Tool Calling] Domain {} matched by keyword '{}' in '{}'",
                            domain, keyword, normalized.length() > 30 ? normalized.substring(0, 30) + "..." : normalized);
                    break;
                }
            }
        }

        log.info("[Tool Calling] detectAllDomains: input='{}', matched={}, QUALITY keywords={}",
                normalized.length() > 40 ? normalized.substring(0, 40) + "..." : normalized,
                matched,
                intentKnowledgeBase.getDomainKeywords(IntentKnowledgeBase.Domain.QUALITY).size());

        return matched;
    }

    /**
     * 构建 Tool Calling 系统提示词
     */
    private String buildToolCallingSystemPrompt(
            String userInput,
            List<AIIntentConfig> availableIntents,
            String suggestedIntentCode,
            String reasoning) {

        StringBuilder sb = new StringBuilder();
        sb.append("你是一个智能意图识别助手。用户的输入无法匹配现有的意图配置。\n\n");
        sb.append("## 当前情况\n\n");
        sb.append(String.format("- 用户输入: \"%s\"\n", userInput));
        sb.append(String.format("- 首次分类结果: %s (不在已知列表中)\n", suggestedIntentCode));
        if (reasoning != null && !reasoning.isEmpty()) {
            sb.append(String.format("- 推理说明: %s\n", reasoning));
        }
        sb.append("\n");

        sb.append("## 已有意图列表\n\n");
        for (AIIntentConfig intent : availableIntents) {
            sb.append(String.format("- %s (%s): %s\n",
                    intent.getIntentCode(),
                    intent.getIntentName(),
                    intent.getDescription() != null ? intent.getDescription() : ""));
        }
        sb.append("\n");

        sb.append("## 你的任务\n\n");
        sb.append("请判断是否需要创建新的意图配置。如果用户的需求确实是一个新的功能模式，调用 `create_new_intent` 工具。\n");
        sb.append("如果用户的需求可能是错误输入、或不应该作为独立意图，则不要调用任何工具。\n\n");

        sb.append("## 决策标准\n\n");
        sb.append("创建新意图的条件：\n");
        sb.append("- 用户描述了明确的功能需求\n");
        sb.append("- 该需求在现有意图列表中找不到相似项\n");
        sb.append("- 该需求具有可重复性（不是一次性的特殊请求）\n\n");

        sb.append("不创建新意图的情况：\n");
        sb.append("- 用户输入过于模糊或无意义\n");
        sb.append("- 用户可能是在测试系统\n");
        sb.append("- 用户的需求可以通过现有意图满足（只是表达方式不同）\n");

        return sb.toString();
    }

    /**
     * 从响应中提取 tool_calls
     */
    private List<ToolCall> extractToolCalls(ChatCompletionResponse response) {
        if (response.getChoices() == null || response.getChoices().isEmpty()) {
            return null;
        }

        ChatCompletionResponse.Choice choice = response.getChoices().get(0);
        if (choice.getMessage() != null) {
            return choice.getMessage().getToolCalls();
        }

        return null;
    }

    /**
     * 构建工具执行上下文
     */
    private Map<String, Object> buildToolExecutionContext(String factoryId, Long userId, String userRole) {
        Map<String, Object> context = new HashMap<>();
        context.put("factoryId", factoryId);
        context.put("userId", userId);
        context.put("userRole", userRole);
        return context;
    }

    // ==================== 多意图识别实现 (模块C) ====================

    /**
     * 多意图识别 - 识别用户输入中的一个或多个意图
     *
     * 支持识别:
     * - 连接词: "顺便"、"还有"、"另外"、"以及"、"同时"
     * - 并列结构: "A和B"、"既要A又要B"
     * - 区分多意图 vs 复合查询
     *
     * @param userInput 用户输入
     * @param factoryId 工厂ID
     * @param contextSummary 对话上下文摘要 (可选)
     * @return 多意图识别结果
     */
    @Override
    public MultiIntentResult classifyMultiIntent(String userInput, String factoryId, String contextSummary) {
        log.info("[Multi-Intent] Classifying multi-intent for input: '{}', factoryId: {}",
                truncate(userInput, 50), factoryId);

        // 检查是否启用多意图识别
        if (!multiIntentEnabled) {
            log.debug("[Multi-Intent] Multi-intent classification is disabled");
            return buildSingleIntentFallback(userInput, factoryId);
        }

        // 检查 DashScope 客户端是否可用
        if (dashScopeClient == null) {
            log.warn("[Multi-Intent] DashScope client not available, falling back to single intent");
            return buildSingleIntentFallback(userInput, factoryId);
        }

        try {
            // 获取可用意图列表
            List<AIIntentConfig> availableIntents = getAvailableIntents(factoryId);

            if (availableIntents == null || availableIntents.isEmpty()) {
                log.warn("[Multi-Intent] No available intents for factoryId: {}", factoryId);
                return MultiIntentResult.builder()
                        .isMultiIntent(false)
                        .intents(List.of())
                        .executionStrategy(MultiIntentResult.ExecutionStrategy.SEQUENTIAL)
                        .overallConfidence(0.0)
                        .reasoning("No available intents found")
                        .build();
            }

            // 构建多意图识别 Prompt
            String systemPrompt = buildMultiIntentClassifyPrompt(availableIntents, contextSummary);

            // 调用 DashScope 进行多意图识别
            String responseJson = dashScopeClient.classifyIntent(systemPrompt, userInput);

            // 解析响应
            return parseMultiIntentResponse(responseJson, availableIntents);

        } catch (Exception e) {
            log.error("[Multi-Intent] Classification failed: {}", e.getMessage(), e);
            return buildSingleIntentFallback(userInput, factoryId);
        }
    }

    /**
     * 构建多意图分类系统提示词
     *
     * @param intents 可用意图列表
     * @param contextSummary 对话上下文摘要
     * @return 系统提示词
     */
    private String buildMultiIntentClassifyPrompt(List<AIIntentConfig> intents, String contextSummary) {
        StringBuilder sb = new StringBuilder();

        sb.append("## 系统角色\n");
        sb.append("你是一个意图识别助手。分析用户输入，识别一个或多个意图。\n\n");

        sb.append("## 多意图识别规则\n");
        sb.append("1. 识别连接词: \"顺便\"、\"还有\"、\"另外\"、\"以及\"、\"同时\"\n");
        sb.append("2. 识别并列结构: \"A和B\"、\"既要A又要B\"\n");
        sb.append("3. 区分真正多意图 vs 复合查询:\n");
        sb.append("   - 多意图: \"查库存，顺便看看发货\" → 2个独立意图\n");
        sb.append("   - 复合查询: \"对比A和B的库存\" → 1个对比意图\n\n");

        // 上下文
        if (contextSummary != null && !contextSummary.isEmpty()) {
            sb.append("## 对话上下文\n");
            sb.append(contextSummary).append("\n\n");
        }

        // 意图列表
        sb.append("## 可用意图列表\n");
        for (AIIntentConfig intent : intents) {
            sb.append("- **").append(intent.getIntentCode()).append("**");
            sb.append(" (").append(intent.getIntentName()).append("): ");
            sb.append(intent.getDescription() != null ? intent.getDescription() : "").append("\n");

            // 添加关键词参考
            if (intent.getKeywords() != null && !intent.getKeywords().isEmpty()) {
                sb.append("  关键词: ").append(String.join(", ", intent.getKeywords())).append("\n");
            }
        }

        sb.append("\n## 执行策略说明\n");
        sb.append("- **PARALLEL**: 意图之间无依赖，可并行执行\n");
        sb.append("- **SEQUENTIAL**: 意图之间有先后顺序依赖\n");
        sb.append("- **USER_CONFIRM**: 需要用户确认后执行（风险操作或置信度不足）\n\n");

        sb.append("## 输出格式 (严格JSON)\n");
        sb.append("```json\n");
        sb.append("{\n");
        sb.append("  \"intents\": [\n");
        sb.append("    {\n");
        sb.append("      \"intent_code\": \"意图代码\",\n");
        sb.append("      \"confidence\": 0.85,\n");
        sb.append("      \"extracted_params\": {\"param1\": \"value1\"},\n");
        sb.append("      \"reasoning\": \"判断理由\"\n");
        sb.append("    }\n");
        sb.append("  ],\n");
        sb.append("  \"is_multi_intent\": false,\n");
        sb.append("  \"execution_strategy\": \"parallel\",\n");
        sb.append("  \"overall_confidence\": 0.85,\n");
        sb.append("  \"overall_reasoning\": \"整体判断理由\"\n");
        sb.append("}\n");
        sb.append("```\n\n");

        sb.append("## 重要规则\n");
        sb.append("1. **最多识别 ").append(maxIntents).append(" 个意图**\n");
        sb.append("2. **只返回置信度 >= 0.5 的意图**\n");
        sb.append("3. **仅返回 JSON，不要包含其他文字**\n");

        return sb.toString();
    }

    /**
     * 解析多意图识别响应
     *
     * @param responseJson LLM 响应 JSON
     * @param availableIntents 可用意图列表
     * @return 多意图识别结果
     */
    private MultiIntentResult parseMultiIntentResponse(String responseJson,
                                                        List<AIIntentConfig> availableIntents) {
        try {
            // 1. 提取 JSON
            Pattern pattern = Pattern.compile("\\{[\\s\\S]*\\}");
            Matcher matcher = pattern.matcher(responseJson);

            if (!matcher.find()) {
                log.warn("[Multi-Intent] Could not extract JSON from response: {}",
                        truncate(responseJson, 100));
                return buildEmptyMultiIntentResult("Could not extract JSON from response");
            }

            // 2. 解析 JSON
            JsonNode json = objectMapper.readTree(matcher.group());

            boolean isMultiIntent = json.has("is_multi_intent") && json.get("is_multi_intent").asBoolean();

            List<MultiIntentResult.SingleIntentMatch> intents = new ArrayList<>();
            JsonNode intentsNode = json.get("intents");

            if (intentsNode != null && intentsNode.isArray()) {
                int order = 0;
                for (JsonNode intentNode : intentsNode) {
                    String intentCode = intentNode.has("intent_code") ?
                            intentNode.get("intent_code").asText() : null;
                    double confidence = intentNode.has("confidence") ?
                            intentNode.get("confidence").asDouble() : 0.5;

                    if (intentCode == null || intentCode.isEmpty()) {
                        continue;
                    }

                    // 限制意图数量
                    if (intents.size() >= maxIntents) {
                        log.debug("[Multi-Intent] Reached max intents limit ({}), skipping remaining", maxIntents);
                        break;
                    }

                    // 验证意图存在
                    AIIntentConfig config = findIntentConfig(intentCode, availableIntents);
                    if (config != null) {
                        // 提取参数
                        Map<String, Object> extractedParams = new HashMap<>();
                        if (intentNode.has("extracted_params") && intentNode.get("extracted_params").isObject()) {
                            extractedParams = parseParams(intentNode.get("extracted_params"));
                        }

                        intents.add(MultiIntentResult.SingleIntentMatch.builder()
                                .intentCode(intentCode)
                                .intentName(config.getIntentName())
                                .confidence(Math.max(0.0, Math.min(1.0, confidence)))
                                .extractedParams(extractedParams)
                                .reasoning(getTextOrNull(intentNode, "reasoning"))
                                .executionOrder(order++)
                                .build());
                    } else {
                        log.warn("[Multi-Intent] Intent code not found in available intents: {}", intentCode);
                    }
                }
            }

            // 如果没有识别到任何意图，返回空结果
            if (intents.isEmpty()) {
                return buildEmptyMultiIntentResult("No valid intents recognized");
            }

            // 3. 确定执行策略
            String strategyStr = json.has("execution_strategy") ?
                    json.get("execution_strategy").asText().toUpperCase() : "PARALLEL";
            MultiIntentResult.ExecutionStrategy strategy;
            try {
                strategy = MultiIntentResult.ExecutionStrategy.valueOf(strategyStr);
            } catch (IllegalArgumentException e) {
                log.warn("[Multi-Intent] Invalid execution strategy '{}', defaulting to PARALLEL", strategyStr);
                strategy = MultiIntentResult.ExecutionStrategy.PARALLEL;
            }

            // 4. 计算总体置信度
            double overallConfidence = json.has("overall_confidence") ?
                    json.get("overall_confidence").asDouble() : calculateOverallConfidence(intents);
            overallConfidence = Math.max(0.0, Math.min(1.0, overallConfidence));

            // 5. 根据置信度决定是否需要用户确认
            if (overallConfidence < userConfirmThreshold) {
                strategy = MultiIntentResult.ExecutionStrategy.USER_CONFIRM;
            }

            String overallReasoning = getTextOrNull(json, "overall_reasoning");

            log.info("[Multi-Intent] Classification result: isMultiIntent={}, intentCount={}, strategy={}, confidence={}",
                    isMultiIntent || intents.size() > 1, intents.size(), strategy, overallConfidence);

            return MultiIntentResult.builder()
                    .isMultiIntent(isMultiIntent || intents.size() > 1)
                    .intents(intents)
                    .executionStrategy(strategy)
                    .overallConfidence(overallConfidence)
                    .reasoning(overallReasoning)
                    .build();

        } catch (Exception e) {
            log.error("[Multi-Intent] Failed to parse response: {}", e.getMessage(), e);
            return buildEmptyMultiIntentResult("Failed to parse response: " + e.getMessage());
        }
    }

    /**
     * 从 JsonNode 中提取参数 Map
     */
    private Map<String, Object> parseParams(JsonNode paramsNode) {
        Map<String, Object> params = new HashMap<>();
        if (paramsNode == null || !paramsNode.isObject()) {
            return params;
        }

        paramsNode.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value.isTextual()) {
                params.put(entry.getKey(), value.asText());
            } else if (value.isNumber()) {
                params.put(entry.getKey(), value.numberValue());
            } else if (value.isBoolean()) {
                params.put(entry.getKey(), value.asBoolean());
            } else if (value.isArray() || value.isObject()) {
                params.put(entry.getKey(), value.toString());
            }
        });

        return params;
    }

    /**
     * 从 JsonNode 中安全获取文本值
     */
    private String getTextOrNull(JsonNode node, String fieldName) {
        if (node == null || !node.has(fieldName)) {
            return null;
        }
        JsonNode field = node.get(fieldName);
        return field.isTextual() ? field.asText() : null;
    }

    /**
     * 查找意图配置
     */
    private AIIntentConfig findIntentConfig(String intentCode, List<AIIntentConfig> availableIntents) {
        if (intentCode == null || availableIntents == null) {
            return null;
        }
        return availableIntents.stream()
                .filter(c -> intentCode.equalsIgnoreCase(c.getIntentCode()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 计算总体置信度
     */
    private double calculateOverallConfidence(List<MultiIntentResult.SingleIntentMatch> intents) {
        if (intents == null || intents.isEmpty()) {
            return 0.0;
        }
        // 使用几何平均
        double product = 1.0;
        for (MultiIntentResult.SingleIntentMatch intent : intents) {
            product *= intent.getConfidence();
        }
        return Math.pow(product, 1.0 / intents.size());
    }

    /**
     * 构建空的多意图结果
     */
    private MultiIntentResult buildEmptyMultiIntentResult(String reasoning) {
        return MultiIntentResult.builder()
                .isMultiIntent(false)
                .intents(List.of())
                .executionStrategy(MultiIntentResult.ExecutionStrategy.SEQUENTIAL)
                .overallConfidence(0.0)
                .reasoning(reasoning)
                .build();
    }

    /**
     * 构建单意图回退结果
     */
    private MultiIntentResult buildSingleIntentFallback(String userInput, String factoryId) {
        try {
            // 尝试使用单意图识别
            List<AIIntentConfig> availableIntents = getAvailableIntents(factoryId);
            IntentMatchResult singleResult = classifyIntent(userInput, availableIntents, factoryId, null, null);

            if (singleResult != null && singleResult.getBestMatch() != null) {
                return MultiIntentResult.builder()
                        .isMultiIntent(false)
                        .intents(List.of(MultiIntentResult.SingleIntentMatch.builder()
                                .intentCode(singleResult.getBestMatch().getIntentCode())
                                .intentName(singleResult.getBestMatch().getIntentName())
                                .confidence(singleResult.getConfidence())
                                .extractedParams(new HashMap<>())
                                .reasoning(singleResult.getClarificationQuestion())
                                .executionOrder(0)
                                .build()))
                        .executionStrategy(MultiIntentResult.ExecutionStrategy.SEQUENTIAL)
                        .overallConfidence(singleResult.getConfidence())
                        .reasoning("Single intent fallback")
                        .build();
            }
        } catch (Exception e) {
            log.warn("[Multi-Intent] Single intent fallback failed: {}", e.getMessage());
        }

        return buildEmptyMultiIntentResult("Fallback to empty result");
    }

    /**
     * 获取可用意图列表
     * 使用工厂级隔离查询，返回工厂级意图 + 平台级意图
     */
    private List<AIIntentConfig> getAvailableIntents(String factoryId) {
        if (intentConfigRepository == null) {
            log.warn("[Multi-Intent] Intent config repository not available");
            return List.of();
        }

        try {
            // 使用工厂级隔离查询方法，获取工厂级意图和平台级意图
            List<AIIntentConfig> allIntents = intentConfigRepository.findByFactoryIdOrPlatformLevel(factoryId);

            log.debug("[Multi-Intent] Found {} intents for factoryId: {}", allIntents.size(), factoryId);
            return allIntents;

        } catch (Exception e) {
            log.error("[Multi-Intent] Failed to get available intents: {}", e.getMessage(), e);
            return List.of();
        }
    }

    // ==================== ArenaRL 锦标赛裁决实现 ====================

    /**
     * 使用 ArenaRL 锦标赛进行意图歧义裁决
     *
     * 触发条件: top1-top2 置信度差 < 0.15 且 top1 < 0.85
     *
     * 算法流程:
     * 1. 检查 ArenaRL 是否启用
     * 2. 验证候选数量 >= 2
     * 3. 执行种子单淘汰锦标赛
     * 4. 返回冠军意图
     *
     * @param userInput 用户输入
     * @param candidates 歧义候选意图列表 (已按置信度排序)
     * @param factoryId 工厂ID
     * @return ArenaRL 裁决结果
     */
    @Override
    public ArenaRLResult disambiguateWithArenaRL(String userInput,
                                                  List<CandidateIntent> candidates,
                                                  String factoryId) {
        log.info("[ArenaRL] Starting disambiguation for input: '{}', candidates: {}",
                truncate(userInput, 50), candidates.size());

        // 1. 检查 ArenaRL 是否启用
        if (arenaRLConfig == null || !arenaRLConfig.isIntentDisambiguationEnabled()) {
            log.debug("[ArenaRL] Not enabled, falling back to top candidate");
            if (candidates == null || candidates.isEmpty()) {
                return ArenaRLResult.failure("No candidates provided");
            }
            CandidateIntent top = candidates.get(0);
            return ArenaRLResult.success(top.getIntentCode(), top.getConfidence(),
                    "ArenaRL disabled, using top candidate", 0);
        }

        // 2. 检查 ArenaRL 服务可用性
        if (arenaRLTournamentService == null) {
            log.warn("[ArenaRL] Tournament service not available, falling back to top candidate");
            CandidateIntent top = candidates.get(0);
            return ArenaRLResult.success(top.getIntentCode(), top.getConfidence(),
                    "ArenaRL service unavailable, using top candidate", 0);
        }

        // 3. 验证候选数量
        if (candidates == null || candidates.size() < 2) {
            log.debug("[ArenaRL] Not enough candidates (<2), using top candidate");
            if (candidates != null && !candidates.isEmpty()) {
                CandidateIntent top = candidates.get(0);
                return ArenaRLResult.success(top.getIntentCode(), top.getConfidence(),
                        "Only one candidate, no tournament needed", 0);
            }
            return ArenaRLResult.failure("No candidates provided");
        }

        // 4. 检查是否需要触发锦标赛
        if (!arenaRLTournamentService.shouldTriggerIntentTournament(candidates)) {
            log.debug("[ArenaRL] Trigger conditions not met, using top candidate");
            CandidateIntent top = candidates.get(0);
            return ArenaRLResult.success(top.getIntentCode(), top.getConfidence(),
                    "Ambiguity threshold not met, using top candidate", 0);
        }

        try {
            // 5. 执行锦标赛
            log.info("[ArenaRL] Executing tournament with {} candidates", candidates.size());
            long startTime = System.currentTimeMillis();

            TournamentResult tournamentResult = arenaRLTournamentService.runIntentTournament(
                    userInput, candidates);

            long latencyMs = System.currentTimeMillis() - startTime;

            // 6. 处理锦标赛结果
            if (tournamentResult == null || !Boolean.TRUE.equals(tournamentResult.getSuccess())) {
                log.warn("[ArenaRL] Tournament failed: {}",
                        tournamentResult != null ? tournamentResult.getErrorMessage() : "null result");
                CandidateIntent top = candidates.get(0);
                return ArenaRLResult.builder()
                        .success(true)
                        .winnerIntentCode(top.getIntentCode())
                        .winnerConfidence(top.getConfidence())
                        .reasoning("Tournament failed, falling back to top candidate: " +
                                (tournamentResult != null ? tournamentResult.getErrorMessage() : "null result"))
                        .comparisonCount(0)
                        .totalLatencyMs(latencyMs)
                        .build();
            }

            // 7. 构建成功结果
            log.info("[ArenaRL] Tournament completed: winner={}, confidence={:.2f}, comparisons={}, latency={}ms",
                    tournamentResult.getWinnerId(),
                    tournamentResult.getWinnerConfidence(),
                    tournamentResult.getTotalComparisons(),
                    latencyMs);

            // 构建推理说明
            String reasoning = buildArenaRLReasoning(tournamentResult, candidates);

            return ArenaRLResult.builder()
                    .success(true)
                    .winnerIntentCode(tournamentResult.getWinnerId())
                    .winnerConfidence(tournamentResult.getWinnerConfidence())
                    .reasoning(reasoning)
                    .comparisonCount(tournamentResult.getTotalComparisons())
                    .totalLatencyMs(latencyMs)
                    .tournamentId(tournamentResult.getTournamentId())
                    .build();

        } catch (Exception e) {
            log.error("[ArenaRL] Tournament execution failed", e);
            CandidateIntent top = candidates.get(0);
            return ArenaRLResult.builder()
                    .success(true)
                    .winnerIntentCode(top.getIntentCode())
                    .winnerConfidence(top.getConfidence())
                    .reasoning("Tournament exception, falling back to top candidate: " + e.getMessage())
                    .comparisonCount(0)
                    .build();
        }
    }

    /**
     * 构建 ArenaRL 推理说明
     */
    private String buildArenaRLReasoning(TournamentResult result, List<CandidateIntent> originalCandidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("ArenaRL 锦标赛裁决结果：\n");

        // 原始排名
        sb.append("原始候选排名：");
        for (int i = 0; i < Math.min(3, originalCandidates.size()); i++) {
            CandidateIntent c = originalCandidates.get(i);
            sb.append(String.format("%s(%.2f)", c.getIntentCode(), c.getConfidence()));
            if (i < Math.min(3, originalCandidates.size()) - 1) sb.append(" > ");
        }
        sb.append("\n");

        // 锦标赛结果
        sb.append(String.format("锦标赛冠军：%s (置信度提升至 %.2f)\n",
                result.getWinnerId(), result.getWinnerConfidence()));
        sb.append(String.format("比较次数：%d，总耗时：%dms",
                result.getTotalComparisons(), result.getTotalLatencyMs()));

        // 如果冠军与原始 top1 不同，标注
        if (!originalCandidates.isEmpty() &&
                !originalCandidates.get(0).getIntentCode().equals(result.getWinnerId())) {
            sb.append("\n[注意] 锦标赛结果与原始排名不同，已调整意图选择");
        }

        return sb.toString();
    }
}

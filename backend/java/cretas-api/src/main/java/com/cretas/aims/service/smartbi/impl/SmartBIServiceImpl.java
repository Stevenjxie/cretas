package com.cretas.aims.service.smartbi.impl;

import com.cretas.aims.ai.client.PythonLLMClient;
import com.cretas.aims.ai.dto.ChatCompletionRequest;
import com.cretas.aims.ai.dto.ChatCompletionResponse;
import com.cretas.aims.ai.dto.ChatMessage;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.conversation.ConversationMessage;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.dto.smartbi.*;
import com.cretas.aims.dto.smartbi.ForecastResult;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.smartbi.*;
import com.cretas.aims.entity.smartbi.enums.ActionType;
import com.cretas.aims.entity.smartbi.enums.BillingMode;
import com.cretas.aims.entity.smartbi.enums.SmartBIIntent;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.smartbi.*;
import com.cretas.aims.service.ConversationMemoryService;
import com.cretas.aims.service.IntentExecutorService;
import com.cretas.aims.service.LlmIntentFallbackClient;
import com.cretas.aims.service.smartbi.*;
import com.cretas.aims.util.BusinessDomainUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Collections;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * SmartBI 核心调度服务实现
 *
 * 实现 SmartBI 系统的中央调度功能：
 * - 聚合各分析服务的数据
 * - 统一缓存管理
 * - 配额控制与计费
 * - 自然语言查询处理
 * - AI 洞察生成
 *
 * 设计要点：
 * 1. 缓存优先：减少重复计算，提升响应速度
 * 2. 配额管控：保护系统资源，控制成本
 * 3. 统一入口：门面模式，简化外部调用
 * 4. 可观测性：完整的使用记录和日志
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-18
 */
@Slf4j
@Service
public class SmartBIServiceImpl implements SmartBIService {

    // ==================== 依赖注入 ====================

    private final SalesAnalysisService salesService;
    private final DepartmentAnalysisService deptService;
    private final RegionAnalysisService regionService;
    private final FinanceAnalysisService financeService;
    private final SmartBIIntentService intentService;
    private final SmartBIPromptService promptService;
    private final RecommendationService recommendationService;

    private final SmartBiAnalysisCacheRepository cacheRepository;
    private final SmartBiUsageRecordRepository usageRepository;
    private final SmartBiBillingConfigRepository billingRepository;
    private final SmartBiQueryHistoryRepository queryHistoryRepository;
    private final SmartBiSalesDataRepository salesDataRepository;

    private final ObjectMapper objectMapper;

    // ==================== 新增: AI Chat 能力集成 ====================

    /**
     * LLM Fallback 客户端 - 用于低置信度意图识别
     * 复用 AI Chat 的 LLM 意图分类能力
     */
    @Autowired(required = false)
    private LlmIntentFallbackClient llmFallbackClient;

    /**
     * 对话记忆服务 - 用于多轮对话和指代消解
     * 复用 AI Chat 的会话上下文管理能力
     */
    @Autowired(required = false)
    private ConversationMemoryService conversationMemoryService;

    /**
     * LLM 客户端 - 用于 LLM 洞察生成
     */
    @Autowired(required = false)
    private PythonLLMClient pythonLLMClient;

    /**
     * 预测服务 - 用于 FORECAST 意图处理
     */
    @Autowired(required = false)
    private ForecastService forecastService;

    /**
     * Tool-Skill 意图执行器 (P5.6)
     * 用于把餐饮诊断意图从 legacy SmartBIIntent switch-case 路由到统一的 Tool-Skill 管线.
     * {@code @Lazy} 打破潜在循环依赖: SmartBIServiceImpl → IntentExecutorService → ... → SmartBIService.
     * {@code required = false} 保证: 如果 Tool-Skill 模块下线, 整个 SmartBI 仍可降级到 legacy 路径.
     */
    @Autowired(required = false)
    @Lazy
    private IntentExecutorService intentExecutorService;

    /**
     * Week 5 Agent layer HTTP client — calls Python /api/smartbi/insights/custom.
     * required=false: works even if Python is down (endpoint returns empty list).
     */
    @Autowired(required = false)
    private com.cretas.aims.client.AgentInsightsClient agentInsightsClient;

    /**
     * Apr 25 Phase 9 backlog (I1): factory metadata for tenant-type aware
     * dashboard routing. Restaurant tenants have 0 rows in legacy
     * smart_bi_sales_data; the Apr 22 Gold cutover wired
     * salesService.getSalesOverview to read from Gold. We need to detect
     * RESTAURANT here so /dashboard/executive?period=month skips the legacy
     * salesData precheck and routes directly to the Gold path.
     * required=false: keep existing tests/wiring functional if a downstream
     * harness omits this bean.
     */
    @Autowired(required = false)
    private com.cretas.aims.repository.FactoryRepository factoryRepository;

    // ==================== 配置参数 ====================

    @Value("${smartbi.llm.enabled:true}")
    private boolean llmEnabled;

    @Value("${smartbi.llm.insight-generation:true}")
    private boolean llmInsightEnabled;

    @Value("${smartbi.llm.fallback-threshold:0.7}")
    private double llmFallbackThreshold;

    @Value("${smartbi.conversation.enabled:true}")
    private boolean conversationEnabled;

    // ==================== 构造函数 ====================

    @Autowired
    public SmartBIServiceImpl(
            SalesAnalysisService salesService,
            DepartmentAnalysisService deptService,
            RegionAnalysisService regionService,
            FinanceAnalysisService financeService,
            SmartBIIntentService intentService,
            SmartBIPromptService promptService,
            RecommendationService recommendationService,
            SmartBiAnalysisCacheRepository cacheRepository,
            SmartBiUsageRecordRepository usageRepository,
            SmartBiBillingConfigRepository billingRepository,
            SmartBiQueryHistoryRepository queryHistoryRepository,
            SmartBiSalesDataRepository salesDataRepository,
            ObjectMapper objectMapper) {
        this.salesService = salesService;
        this.deptService = deptService;
        this.regionService = regionService;
        this.financeService = financeService;
        this.intentService = intentService;
        this.promptService = promptService;
        this.recommendationService = recommendationService;
        this.cacheRepository = cacheRepository;
        this.usageRepository = usageRepository;
        this.billingRepository = billingRepository;
        this.queryHistoryRepository = queryHistoryRepository;
        this.salesDataRepository = salesDataRepository;
        this.objectMapper = objectMapper;
    }

    // ==================== 常量定义 ====================

    /** 缓存类型：仪表盘 */
    private static final String CACHE_TYPE_DASHBOARD = "DASHBOARD";
    /** 缓存类型：销售 */
    private static final String CACHE_TYPE_SALES = "SALES";
    /** 缓存类型：部门 */
    private static final String CACHE_TYPE_DEPARTMENT = "DEPARTMENT";
    /** 缓存类型：区域 */
    private static final String CACHE_TYPE_REGION = "REGION";
    /** 缓存类型：财务 */
    private static final String CACHE_TYPE_FINANCE = "FINANCE";
    /** 缓存类型：全部 */
    private static final String CACHE_TYPE_ALL = "ALL";

    /** 默认每日配额 (Apr 15 2026: 50 → 1000, demo 中老板 30 题就到顶, 远低于 SaaS 业界基线) */
    private static final int DEFAULT_DAILY_QUOTA = 1000;

    /**
     * 餐饮诊断查询关键词 (P5.6).
     * 匹配这些关键词的查询走 Tool-Skill 管线 (IntentExecutorService) 而非 legacy switch-case.
     * 关键词来自 V20260411_01 Flyway migration 注册的 14 个 RESTAURANT_* 意图的 keywords 字段.
     * 使用简单正则而非 DB 查询以避免额外 IO 成本, 管线内部会再做精确意图识别.
     */
    private static final Pattern RESTAURANT_DIAGNOSTIC_KEYWORDS = Pattern.compile(
        "(成本刚性|刚性|人工成本占比|为什么亏|营收和人工|成本结构" +
        "|对标|行业基准|行业平均|比别人差|火锅行业|川菜行业|差在哪" +
        "|渠道毛利|堂食外卖|美团抽成|饿了么抽成|团购|渠道贡献" +
        "|时段客流|几点最忙|午市晚市|下午时段|空闲时段|热力图" +
        "|长尾|哪些菜该砍|菜单瘦身|末位淘汰|冗余SKU|冗余菜品" +
        "|菜单归一|重复菜名|SKU精简|菜名清洗|归一后SKU" +
        "|同店同比|同比|环比|比上个月|比去年|YoY|MoM" +
        "|评论|点评|大众点评|美团评论|客户怎么说|差评|好评|评分下降" +
        "|会员分层|RFM|冠军客户|流失客户|召回|复购|客户留存" +
        "|储值卡|充值卡|核销率|兑付余额|储值余额|充卡" +
        "|多店对比|门店排名|17家店|哪家最好|哪家最差|异常店|门店Top" +
        "|校准历史|BOM校准|食材成本偏移|月度异常|什么时候开始掉" +
        "|P&L|利润表|完整财务|一页纸|headline|门店诊断报告" +
        "|BOM精度|数据缺口|还要上传什么|Layer|数据完整度" +
        "|菜单工程|Kasavana|明星菜|问题菜|谜题菜|瘦狗菜|四象限" +
        "|横向对比|同业对比|大盘|跨店对比|跨连锁" +
        "|销售预测|预测下月|预测未来|未来趋势|趋势预测" +
        "|BOM差异|成本差异归因|供应链差异|管理差异|标准成本差异|采购价变动" +
        "|销售计划|月度计划|目标营收|设定目标|计划完成|完成度|目标达成|还差多少|进度追踪" +
        "|人效|人均产出|员工效率|用人多少|人力成本效率|人效比" +
        "|桌位|占有率|餐位|几人位|两人位|四人位|座位配置" +
        "|套餐拆|拆单|套餐统计|套餐销量|单点还是套餐" +
        "|退货异常|反复退货|供应商退货|验收异常|退货率|退货归因" +
        "|竞品分析|竞品对比|别家做得好|竞争对手|同行对比|评分对比|点评对比" +
        "|叫货|自动下单|采购单|叫货单|补货|智能下单" +
        "|日清日结|日盘|今日盘点|库存对账|每日对账|当日损耗" +
        "|采购预测|明天需要多少|下周要多少|备货|节假日备货" +
        "|排班|公式制|全职兼职|班次|工时|排班分析|排班优化" +
        "|计件|提成|迎宾计件|小组计件|绩效提成|按单计算" +
        "|绩效规则|绩效考核|KPI|考核权重|可控利润考核" +
        "|店长KPI|三维度|健康度|门店评分|综合评估)"
    );

    // ==================== 经营驾驶舱 ====================

    /** Shared thread pool for parallel dashboard queries (bounded to avoid connection pool exhaustion) */
    private static final ExecutorService DASHBOARD_EXECUTOR = Executors.newFixedThreadPool(4);

    @PreDestroy
    public void shutdownExecutor() {
        DASHBOARD_EXECUTOR.shutdown();
    }

    @Override
    public DashboardResponse getExecutiveDashboard(String factoryId, String period) {
        log.info("获取经营驾驶舱: factoryId={}, period={}", factoryId, period);
        long startTime = System.currentTimeMillis();

        // 1. 检查缓存
        String cacheKey = buildCacheKey("dashboard", period);
        Optional<Object> cached = getFromCache(factoryId, cacheKey);
        if (cached.isPresent()) {
            log.info("命中驾驶舱缓存: factoryId={}, period={}", factoryId, period);
            recordUsage(factoryId, null, ActionType.DASHBOARD.name(), 0, true);
            return (DashboardResponse) cached.get();
        }

        // ================================================================
        // Apr 25 Phase 9 backlog (I1): RESTAURANT tenants route through Gold
        // ----------------------------------------------------------------
        // Background: legacy 自动检测 (步骤 2.1 below) queries
        // smart_bi_sales_data which restaurants have 0 rows in. The auto-
        // fallback then returns invalid range → range stays at current
        // period → Gold returns empty → empty dashboard. The /custom
        // endpoint works because user explicitly picks a 2025 range.
        //
        // Fix: for RESTAURANT tenants, skip the legacy precheck and route
        // through salesService.getSalesOverview() (which is Gold-aware via
        // SMARTBI_GOLD_READ_PRIMARY_ENABLED, Apr 22 Phase B4 cutover).
        // Add a small fallback chain so first-load with no range still finds
        // the most recent data window (mirrors Trends/Finance UX).
        //
        // Manufacturing tenants (FACTORY/HEADQUARTERS/CENTRAL_KITCHEN) keep
        // the legacy 4-future parallel path unchanged.
        // ================================================================
        if (isRestaurantTenant(factoryId)) {
            DashboardResponse goldResp = computeRestaurantDashboard(factoryId, period);
            if (goldResp != null) {
                Duration ttl = calculateCacheTtl(period);
                saveToCache(factoryId, cacheKey, goldResp, ttl);
                long elapsed = System.currentTimeMillis() - startTime;
                log.info("驾驶舱(RESTAURANT/Gold): factoryId={}, period={}, kpis={}, elapsed={}ms",
                        factoryId, period,
                        goldResp.getKpiCards() == null ? 0 : goldResp.getKpiCards().size(),
                        elapsed);
                recordUsage(factoryId, null, ActionType.DASHBOARD.name(), 0, false);
                return goldResp;
            }
            // null = restaurant + Gold path failed entirely → fall through
            // to legacy (will likely be empty for restaurants but at least
            // keeps the response shape consistent).
            log.warn("Restaurant Gold dashboard returned null, falling back to legacy: factoryId={}", factoryId);
        }

        // 2. 计算日期范围
        DateRange range = calculateDateRange(period);

        // 2.1 检查当前日期范围内是否有数据，如果没有则自动切换到有数据的日期范围
        Long dataCount = salesDataRepository.countByFactoryIdAndDateRange(
                factoryId, range.getStartDate(), range.getEndDate());
        if (dataCount == null || dataCount == 0) {
            log.info("当前日期范围无数据: factoryId={}, period={}, 尝试自动检测数据日期范围", factoryId, period);
            DateRange dataRange = getDataDateRange(factoryId);
            if (dataRange != null && dataRange.isValid()) {
                log.info("自动检测到数据日期范围: {} 至 {}", dataRange.getStartDate(), dataRange.getEndDate());
                range = dataRange;
                cacheKey = buildCacheKey("dashboard", "auto_" + range.getStartDate() + "_" + range.getEndDate());
            } else {
                log.warn("未检测到任何销售数据: factoryId={}", factoryId);
            }
        }

        // 3. 并行获取各维度数据 (每个 service 方法有自己的 @Transactional)
        //
        // RBAC FIX (Jun 2026): RequestContextHolder is thread-local. The child
        // threads spawned by DASHBOARD_EXECUTOR cannot see the caller's request
        // context, so GoldFinanceClient.currentUserRole() returns null in those
        // threads → Python _apply_rbac_strip nulls all revenue → factory
        // dashboards show ¥0. Fix: capture the RequestAttributes on the request
        // thread and bind them into each child thread, then remove after the
        // task completes (prevent context leaks between pooled-thread reuses).
        final RequestAttributes capturedAttrs = RequestContextHolder.getRequestAttributes();

        final DateRange finalRange = range;
        CompletableFuture<DashboardResponse> salesFuture = CompletableFuture.supplyAsync(
                () -> {
                    if (capturedAttrs != null) RequestContextHolder.setRequestAttributes(capturedAttrs, true);
                    try {
                        return salesService.getSalesOverview(factoryId, finalRange.getStartDate(), finalRange.getEndDate());
                    } finally {
                        if (capturedAttrs != null) RequestContextHolder.resetRequestAttributes();
                    }
                },
                DASHBOARD_EXECUTOR);
        CompletableFuture<List<RankingItem>> deptRankingFuture = CompletableFuture.supplyAsync(
                () -> {
                    if (capturedAttrs != null) RequestContextHolder.setRequestAttributes(capturedAttrs, true);
                    try {
                        return deptService.getDepartmentRanking(factoryId, finalRange.getStartDate(), finalRange.getEndDate());
                    } finally {
                        if (capturedAttrs != null) RequestContextHolder.resetRequestAttributes();
                    }
                },
                DASHBOARD_EXECUTOR);
        CompletableFuture<List<RankingItem>> regionRankingFuture = CompletableFuture.supplyAsync(
                () -> {
                    if (capturedAttrs != null) RequestContextHolder.setRequestAttributes(capturedAttrs, true);
                    try {
                        return regionService.getRegionRanking(factoryId, finalRange.getStartDate(), finalRange.getEndDate());
                    } finally {
                        if (capturedAttrs != null) RequestContextHolder.resetRequestAttributes();
                    }
                },
                DASHBOARD_EXECUTOR);
        CompletableFuture<ChartConfig> deptTrendFuture = CompletableFuture.supplyAsync(
                () -> {
                    if (capturedAttrs != null) RequestContextHolder.setRequestAttributes(capturedAttrs, true);
                    try {
                        return deptService.getDepartmentTrendComparison(factoryId, finalRange.getStartDate(), finalRange.getEndDate(), "WEEK");
                    } finally {
                        if (capturedAttrs != null) RequestContextHolder.resetRequestAttributes();
                    }
                },
                DASHBOARD_EXECUTOR);

        // 等待所有查询完成 (join+get wrapped in try-catch for serial fallback)
        DashboardResponse salesDashboard;
        List<RankingItem> deptRankings;
        List<RankingItem> regionRankings;
        ChartConfig deptTrendChart;
        try {
            CompletableFuture.allOf(salesFuture, deptRankingFuture, regionRankingFuture, deptTrendFuture).join();
            salesDashboard = salesFuture.join();
            deptRankings = deptRankingFuture.join();
            regionRankings = regionRankingFuture.join();
            deptTrendChart = deptTrendFuture.join();
        } catch (Exception e) {
            log.error("并行查询异常, 回退到串行: {}", e.getMessage(), e);
            salesDashboard = salesService.getSalesOverview(factoryId, range.getStartDate(), range.getEndDate());
            deptRankings = deptService.getDepartmentRanking(factoryId, range.getStartDate(), range.getEndDate());
            regionRankings = regionService.getRegionRanking(factoryId, range.getStartDate(), range.getEndDate());
            deptTrendChart = deptService.getDepartmentTrendComparison(factoryId, range.getStartDate(), range.getEndDate(), "WEEK");
        }

        // 4. 组装响应 - 合并排名数据
        Map<String, List<RankingItem>> rankingMap = new HashMap<>();
        if (salesDashboard.getRankings() != null) {
            rankingMap.putAll(salesDashboard.getRankings());
        }
        if (deptRankings != null && !deptRankings.isEmpty()) {
            rankingMap.put("department", deptRankings);
        }
        if (regionRankings != null && !regionRankings.isEmpty()) {
            rankingMap.put("region", regionRankings);
        }

        // 合并图表数据
        Map<String, ChartConfig> chartMap = new HashMap<>();
        if (salesDashboard.getCharts() != null) {
            chartMap.putAll(salesDashboard.getCharts());
        }
        if (deptTrendChart != null) {
            chartMap.put("department_trend", deptTrendChart);
        }

        // 5. 规则引擎洞察 (快, <100ms) — LLM 洞察已移至独立端点
        List<AIInsight> aiInsights = new ArrayList<>();
        if (salesDashboard.getAiInsights() != null) {
            aiInsights.addAll(salesDashboard.getAiInsights());
        }
        List<AIInsight> ruleInsights = recommendationService.generateInsightSummary(salesDashboard);
        if (ruleInsights != null) {
            aiInsights.addAll(ruleInsights);
        }

        // 6. 组装最终响应
        DashboardResponse response = DashboardResponse.builder()
                .kpiCards(salesDashboard.getKpiCards())
                .charts(chartMap)
                .rankings(rankingMap)
                .aiInsights(aiInsights)
                .suggestions(salesDashboard.getSuggestions())
                .lastUpdated(LocalDateTime.now())
                .build();

        // 7. 保存缓存
        Duration ttl = calculateCacheTtl(period);
        saveToCache(factoryId, cacheKey, response, ttl);

        // 8. 记录使用
        long elapsed = System.currentTimeMillis() - startTime;
        log.info("驾驶舱数据生成完成: factoryId={}, period={}, elapsed={}ms", factoryId, period, elapsed);
        recordUsage(factoryId, null, ActionType.DASHBOARD.name(), 0, false);

        return response;
    }

    /**
     * Apr 25 Phase 9 backlog (I1): is this factory a restaurant tenant?
     *
     * Restaurant tenants (RESTAURANT, BRANCH) have 0 rows in legacy
     * smart_bi_sales_data and must route through Gold (POS-backed).
     * Defensive: if the FactoryRepository bean is missing or the lookup
     * throws, return false so manufacturing path is preserved (legacy
     * dashboard is the existing behavior, no regression).
     */
    private boolean isRestaurantTenant(String factoryId) {
        if (factoryRepository == null || factoryId == null) {
            return false;
        }
        try {
            return factoryRepository.findById(factoryId)
                    .map(f -> BusinessDomainUtils.isRestaurantDomain(f.getType()))
                    .orElse(false);
        } catch (Exception e) {
            log.warn("Failed to check tenant type for {}: {}", factoryId, e.getMessage());
            return false;
        }
    }

    /**
     * Apr 25 Phase 9 backlog (I1): build dashboard for restaurant tenant via
     * the Gold-aware sales overview path, with a simple fallback chain so
     * first-load (no date picker) still finds the most recent data window.
     *
     * Order: requested period → previous year same month/period → 2024 same period.
     * Returns null if all attempts produce empty (defensive — caller falls
     * through to legacy path which will also be empty but preserves shape).
     */
    private DashboardResponse computeRestaurantDashboard(String factoryId, String period) {
        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.LocalDate[] primary = com.cretas.aims.util.DateRangeUtils.getDateRangeByPeriod(period, today);
        java.time.LocalDate[] lastYear = com.cretas.aims.util.DateRangeUtils.getDateRangeByPeriod(period, today.minusYears(1));
        java.time.LocalDate[] twoYearsAgo = com.cretas.aims.util.DateRangeUtils.getDateRangeByPeriod(period, today.minusYears(2));

        java.time.LocalDate[][] attempts = new java.time.LocalDate[][] { primary, lastYear, twoYearsAgo };
        for (int i = 0; i < attempts.length; i++) {
            java.time.LocalDate start = attempts[i][0];
            java.time.LocalDate end = attempts[i][1];
            try {
                DashboardResponse resp = salesService.getSalesOverview(factoryId, start, end);
                if (resp != null && resp.getKpiCards() != null && !resp.getKpiCards().isEmpty()) {
                    log.info("[restaurant-gold] factory={} period={} attempt={} ({}..{}) returned {} kpis",
                            factoryId, period, i, start, end, resp.getKpiCards().size());
                    return resp;
                }
                log.debug("[restaurant-gold] factory={} attempt {} ({}..{}) empty, trying next",
                        factoryId, i, start, end);
            } catch (Exception e) {
                log.warn("[restaurant-gold] factory={} attempt {} ({}..{}) failed: {}",
                        factoryId, i, start, end, e.getMessage());
            }
        }
        return null;
    }

    @Override
    public List<AIInsight> getDashboardLLMInsights(String factoryId, String period) {
        log.info("获取驾驶舱 LLM 洞察: factoryId={}, period={}", factoryId, period);
        long startTime = System.currentTimeMillis();

        // Resolve actual date range (mirrors getDashboard auto-range logic)
        DateRange range = calculateDateRange(period);
        String effectivePeriod = period;
        Long dataCount = salesDataRepository.countByFactoryIdAndDateRange(
                factoryId, range.getStartDate(), range.getEndDate());
        if (dataCount == null || dataCount == 0) {
            DateRange dataRange = getDataDateRange(factoryId);
            if (dataRange != null && dataRange.isValid()) {
                range = dataRange;
                effectivePeriod = "auto_" + range.getStartDate() + "_" + range.getEndDate();
            }
        }

        // Try cache with effective period (matches dashboard cache key pattern)
        String insightCacheKey = buildCacheKey("dashboard_llm_insights", effectivePeriod);
        Optional<Object> cached = getFromCache(factoryId, insightCacheKey);
        if (cached.isPresent()) {
            log.info("命中 LLM 洞察缓存: factoryId={}", factoryId);
            return (List<AIInsight>) cached.get();
        }

        // Try dashboard cache with effective period
        String dashboardCacheKey = buildCacheKey("dashboard", effectivePeriod);
        Optional<Object> dashboardCached = getFromCache(factoryId, dashboardCacheKey);
        DashboardResponse dashboard;
        if (dashboardCached.isPresent()) {
            dashboard = (DashboardResponse) dashboardCached.get();
        } else {
            dashboard = salesService.getSalesOverview(factoryId, range.getStartDate(), range.getEndDate());
        }

        // 生成 LLM 洞察
        List<AIInsight> llmInsights = generateAIInsights(factoryId, dashboard);

        // 缓存 LLM 洞察
        Duration ttl = calculateCacheTtl(period);
        saveToCache(factoryId, insightCacheKey, llmInsights, ttl);

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("LLM 洞察生成完成: factoryId={}, count={}, elapsed={}ms", factoryId, llmInsights.size(), elapsed);
        return llmInsights;
    }

    @Override
    public List<AIInsight> getDashboardLLMInsightsCustomRange(
            String factoryId, LocalDate startDate, LocalDate endDate) {
        log.info("Agent insights custom: factoryId={} range={}..{}", factoryId, startDate, endDate);

        if (agentInsightsClient == null) {
            log.warn("AgentInsightsClient not wired — returning empty");
            return java.util.Collections.emptyList();
        }

        try {
            Map<String, Object> resp = agentInsightsClient.fetchInsightsCustom(
                    factoryId, startDate, endDate, null);
            String answer = resp.get("answer") instanceof String ? (String) resp.get("answer") : "";
            String source = resp.get("source") instanceof String ? (String) resp.get("source") : "llm";
            if (answer.isBlank()) {
                return java.util.Collections.emptyList();
            }
            // Degraded responses (budget exhausted / LLM down) map to YELLOW so
            // the UI shows them distinctly from fresh LLM answers.
            String level = "degraded".equals(source) ? "YELLOW" : "INFO";
            String category = "cache".equals(source) ? "AI 分析 (缓存)" : "AI 分析";
            AIInsight insight = AIInsight.builder()
                    .level(level)
                    .category(category)
                    .message(answer)
                    .relatedEntity("range:" + startDate + "~" + endDate)
                    .actionSuggestion(null)
                    .build();
            return java.util.Collections.singletonList(insight);
        } catch (IOException e) {
            log.error("Agent insights failed: {}", e.getMessage());
            return java.util.Collections.emptyList();
        } catch (Exception e) {
            log.error("Agent insights unexpected failure", e);
            return java.util.Collections.emptyList();
        }
    }

    // ==================== 自然语言问答 ====================

    @Override
    public NLQueryResponse processQuery(String factoryId, Long userId, NLQueryRequest request) {
        log.info("处理自然语言查询: factoryId={}, userId={}, query={}",
                factoryId, userId, request.getEffectiveQuery());
        long startTime = System.currentTimeMillis();

        // 1. 检查配额 (readOnly query, no transaction needed)
        if (!checkQuota(factoryId)) {
            throw new BusinessException(429, "今日查询配额已用完")
                    .withHint("请明日再试或联系管理员升级套餐");
        }

        // 2. 指代消解 - 解析多轮对话中的指代词（复用 AI Chat 会话记忆能力）
        String resolvedQuery = resolveQueryReferences(request);
        if (!resolvedQuery.equals(request.getEffectiveQuery())) {
            log.info("指代消解: '{}' -> '{}'", request.getEffectiveQuery(), resolvedQuery);
        }

        // 2.5. P5.6 · Tool-Skill 路由分流 —— 餐饮诊断意图绕过 legacy switch-case
        // 关键词命中 + IntentExecutorService 可用时, 把查询交给统一的 Tool-Skill 管线.
        // 管线内部会进行精确意图识别, 若不是真正的 RESTAURANT_* 意图会回落到 legacy 路径.
        NLQueryResponse restaurantRouted = tryRouteRestaurantDiagnostic(
                factoryId, userId, request, resolvedQuery, startTime);
        if (restaurantRouted != null) {
            return restaurantRouted;
        }

        // 3. 意图识别
        IntentResult intentResult = intentService.recognizeIntent(resolvedQuery);
        log.info("意图识别结果: intent={}, confidence={}", intentResult.getIntent(), intentResult.getConfidence());

        // 4. 处理低置信度情况 - LLM Fallback (HTTP call, outside transaction to avoid holding DB connection)
        if (intentResult.isNeedsLLMFallback()) {
            log.info("触发 LLM Fallback: confidence={}, threshold={}",
                    intentResult.getConfidence(), llmFallbackThreshold);
            intentResult = tryLLMFallback(factoryId, userId, resolvedQuery, intentResult);
        }

        // 5. 根据意图执行查询
        Object data = executeIntent(factoryId, intentResult);

        // 6. 生成响应文本
        // FIX BUG #5 (Apr 15 2026): try LLM-summarized analysis first; fall back to template only if LLM fails
        String responseText = generateLLMResponseText(factoryId, request.getEffectiveQuery(), intentResult, data);
        if (responseText == null || responseText.isBlank()) {
            responseText = generateResponseText(intentResult, data);
        }

        // 7. 生成图表配置
        List<ChartConfig> charts = generateChartConfig(intentResult, data);

        // 8. 生成后续问题建议
        List<String> followUpQuestions = generateFollowUpQuestions(intentResult);

        // 9. 更新对话记忆（复用 AI Chat 会话记忆能力）
        updateConversationMemory(request.getSessionId(), request, intentResult, responseText);

        // 10-11. 保存查询历史 + 记录使用 (transactional writes)
        long elapsed = System.currentTimeMillis() - startTime;
        persistQueryRecord(factoryId, userId, request, intentResult, responseText, elapsed);

        // 12. 构建响应
        Map<String, Object> parameters = intentResult.getParameters() != null ?
                intentResult.getParameters() : new HashMap<>();
        if (intentResult.getTimeRange() != null) {
            parameters.put("timeRange", intentResult.getTimeRange());
        }

        return NLQueryResponse.builder()
                .responseText(responseText)
                .intent(intentResult.getIntent().getCode())
                .parameters(parameters)
                .charts(charts)
                .followUpQuestions(followUpQuestions)
                .build();
    }

    // Note: no @Transactional needed — saveQueryHistory and recordUsageWithQuery each use
    // repository.save() which auto-commits independently. Try-catch provides error isolation.
    private void persistQueryRecord(String factoryId, Long userId, NLQueryRequest request,
                                       IntentResult intentResult, String responseText, long elapsed) {
        try {
            saveQueryHistory(factoryId, userId, request, intentResult, responseText);
            recordUsageWithQuery(factoryId, userId, request.getEffectiveQuery(), intentResult.getIntent().getCode(),
                    0, false, (int) elapsed);
        } catch (Exception e) {
            log.warn("保存查询记录失败 (非关键): {}", e.getMessage());
        }
    }

    // ==================== P5.6 · Tool-Skill 路由分流 ====================

    /**
     * 尝试把餐饮诊断意图路由到 Tool-Skill 管线 (P5.6).
     *
     * <p>路由条件 (全部必须满足):
     * <ul>
     *   <li>{@link #intentExecutorService} 已注入 (Tool-Skill 模块可用)</li>
     *   <li>查询文本匹配 {@link #RESTAURANT_DIAGNOSTIC_KEYWORDS} 关键词</li>
     *   <li>IntentExecutorService 返回的 intentCode 以 {@code RESTAURANT_} 开头</li>
     * </ul>
     *
     * <p>不满足任一条件时返回 {@code null}, 调用方 {@link #processQuery} 回落到 legacy switch-case.
     *
     * @return 路由成功时返回填充好的 NLQueryResponse; 路由失败/不适用时返回 {@code null}
     */
    private NLQueryResponse tryRouteRestaurantDiagnostic(String factoryId,
                                                          Long userId,
                                                          NLQueryRequest request,
                                                          String resolvedQuery,
                                                          long startTime) {
        if (intentExecutorService == null) {
            return null;
        }
        if (resolvedQuery == null || resolvedQuery.isBlank()) {
            return null;
        }
        if (!RESTAURANT_DIAGNOSTIC_KEYWORDS.matcher(resolvedQuery).find()) {
            return null;
        }

        log.info("P5.6 · 检测到餐饮诊断关键词, 尝试路由到 Tool-Skill 管线: query='{}'", resolvedQuery);

        try {
            IntentExecuteRequest execRequest = IntentExecuteRequest.builder()
                    .userInput(resolvedQuery)
                    .context(buildToolSkillContext(request))
                    .previewOnly(false)
                    .skipSlotFilling(false)
                    .build();

            // userId=0L 表示匿名/系统调用; IntentExecutorService 会做低敏意图免审批.
            // userRole 默认 restaurant_manager, 管线内部会再基于 factory 权限做校验.
            Long effectiveUserId = userId != null ? userId : 0L;
            IntentExecuteResponse execResponse = intentExecutorService.execute(
                    factoryId, execRequest, effectiveUserId, "restaurant_manager");

            if (execResponse == null
                    || !Boolean.TRUE.equals(execResponse.getIntentRecognized())
                    || execResponse.getIntentCode() == null
                    || !execResponse.getIntentCode().startsWith("RESTAURANT_")) {
                log.info("P5.6 · Tool-Skill 未识别到餐饮意图 (intentCode={}), 回落到 legacy 路径",
                        execResponse != null ? execResponse.getIntentCode() : null);
                return null;
            }

            log.info("P5.6 · Tool-Skill 路由成功: intentCode={}, elapsed={}ms",
                    execResponse.getIntentCode(), System.currentTimeMillis() - startTime);

            return mapIntentExecuteResponse(execResponse);
        } catch (Exception e) {
            log.warn("P5.6 · Tool-Skill 路由失败, 回落到 legacy 路径: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 构造 Tool-Skill 执行上下文 (P5.6).
     *
     * <p>透传 NLQueryRequest.context 中的 subSector / uploadId 等字段, 同时补充 factoryId
     * 和 sessionId. 所有字段都用 {@code String.valueOf} 包装避免 null.
     */
    private Map<String, Object> buildToolSkillContext(NLQueryRequest request) {
        Map<String, Object> context = new HashMap<>();
        if (request.getContext() != null) {
            context.putAll(request.getContext());
        }
        if (request.getSessionId() != null) {
            context.put("sessionId", request.getSessionId());
        }
        if (request.getFactoryId() != null) {
            context.put("factoryId", request.getFactoryId());
        }
        return context;
    }

    /**
     * 把 {@link IntentExecuteResponse} 适配为 web-admin / Mobile RN 期望的
     * {@link NLQueryResponse} 形状 (P5.6).
     *
     * <p>核心映射规则:
     * <ul>
     *   <li>{@code intentCode} ← {@code execResponse.intentCode}</li>
     *   <li>{@code toolName} / {@code skillName} ← 从 {@code metadata} 读取 (IntentExecutor 会填充)</li>
     *   <li>{@code sections[]} ← 从 {@code resultData} map 的 {@code section}/{@code data}
     *       字段抽取, 包装成单元素 SectionPayload 列表 (每次查询对应 1 个 section)</li>
     *   <li>{@code followUpChips} ← {@code resultData} map 的 {@code followUpChips} 字段</li>
     *   <li>{@code responseText} / {@code message} ← {@code execResponse.formattedText}</li>
     *   <li>{@code intent} 保留 legacy 字段, 填充 RESTAURANT_DIAGNOSTIC 占位以避免 NPE</li>
     * </ul>
     *
     * <p>此方法对任意异常都 fail-safe: 返回包含 intentCode + error 的最小 response,
     * 避免前端崩溃. 真正的错误细节写入 log.
     *
     * <p>Pure function (no instance state access) - static for unit test friendliness.
     */
    @SuppressWarnings("unchecked")
    static NLQueryResponse mapIntentExecuteResponse(IntentExecuteResponse execResponse) {
        NLQueryResponse.NLQueryResponseBuilder builder = NLQueryResponse.builder()
                .intent("RESTAURANT_DIAGNOSTIC")  // legacy 兼容字段
                .intentCode(execResponse.getIntentCode())
                .confidence(execResponse.getConfidence() != null ? execResponse.getConfidence() : 1.0)
                .responseText(execResponse.getFormattedText())
                .message(execResponse.getMessage());

        // 从 metadata 读取 toolName / skillName (IntentExecutorServiceImpl 会填充)
        Map<String, Object> metadata = execResponse.getMetadata();
        if (metadata != null) {
            Object toolName = metadata.get("toolName");
            Object skillName = metadata.get("skillName");
            if (toolName instanceof String) {
                builder.toolName((String) toolName);
            }
            if (skillName instanceof String) {
                builder.skillName((String) skillName);
            }
        }

        // 抽取 sections + followUpChips (resultData 是 Tool.formatResult() 返回的 Map)
        Object resultData = execResponse.getResultData();
        if (resultData instanceof Map) {
            Map<String, Object> dataMap = (Map<String, Object>) resultData;

            // 单个 section: 包装成 1 元素列表供前端渲染
            Object sectionName = dataMap.get("section");
            Object sectionData = dataMap.get("data");
            Object sectionWarnings = dataMap.get("warnings");
            if (sectionName instanceof String) {
                Map<String, Object> section = new LinkedHashMap<>();
                section.put("sectionName", sectionName);
                section.put("status", Boolean.TRUE.equals(dataMap.get("success")) ? "ok" : "failed");
                section.put("data", sectionData instanceof Map ? sectionData : Collections.emptyMap());
                section.put("warnings", sectionWarnings instanceof List ? sectionWarnings : Collections.emptyList());
                section.put("fromCache", false);
                section.put("computedAtMs", System.currentTimeMillis());
                builder.sections(Collections.singletonList(section));
            }

            // followUpChips
            Object chips = dataMap.get("followUpChips");
            if (chips instanceof List) {
                builder.followUpChips((List<String>) chips);
            }
        }

        return builder.build();
    }

    // ==================== AI Chat 能力集成方法 ====================

    /**
     * 解析查询中的指代词（复用 ConversationMemoryService）
     *
     * 支持的指代模式：
     * - "这批"、"那批" -> 上一轮提到的批次
     * - "这家"、"那个供应商" -> 上一轮提到的供应商
     * - "这个部门"、"那个区域" -> 上一轮提到的部门/区域
     *
     * @param request 查询请求
     * @return 消解后的查询文本
     */
    private String resolveQueryReferences(NLQueryRequest request) {
        if (!conversationEnabled || conversationMemoryService == null) {
            return request.getEffectiveQuery();
        }

        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            return request.getEffectiveQuery();
        }

        try {
            // 调用 AI Chat 的指代消解能力
            String resolved = conversationMemoryService.resolveReference(sessionId, request.getEffectiveQuery());
            return resolved != null ? resolved : request.getEffectiveQuery();
        } catch (Exception e) {
            log.warn("指代消解失败: sessionId={}, error={}", sessionId, e.getMessage());
            return request.getEffectiveQuery();
        }
    }

    /**
     * 尝试 LLM Fallback 意图识别（复用 LlmIntentFallbackClient）
     *
     * 当规则引擎置信度低于阈值时，调用 LLM 进行意图澄清。
     * 参考 AIIntentServiceImpl:1055 的实现。
     *
     * @param factoryId 工厂ID
     * @param userId 用户ID
     * @param query 查询文本
     * @param ruleResult 规则引擎识别结果
     * @return 最终意图识别结果
     */
    private IntentResult tryLLMFallback(String factoryId, Long userId, String query, IntentResult ruleResult) {
        if (!llmEnabled || llmFallbackClient == null) {
            log.debug("LLM Fallback 未启用或客户端未配置");
            return ruleResult;
        }

        try {
            // 将 SmartBI 意图转换为 AIIntentConfig 列表
            List<AIIntentConfig> intentConfigs = convertToIntentConfigs(SmartBIIntent.values());

            // 调用 LLM 进行意图分类（参考 AIIntentServiceImpl:1055）
            IntentMatchResult llmResult = llmFallbackClient.classifyIntent(
                    query, intentConfigs, factoryId, userId, null);

            if (llmResult != null && llmResult.hasMatch()) {
                log.info("LLM Fallback 成功: intent={}, confidence={}",
                        llmResult.getBestMatch().getIntentCode(), llmResult.getConfidence());

                // 如果 LLM 置信度更高，使用 LLM 结果
                if (llmResult.getConfidence() > ruleResult.getConfidence()) {
                    return convertToSmartBIIntentResult(llmResult, query);
                }
            }
        } catch (Exception e) {
            log.error("LLM Fallback 异常: {}", e.getMessage(), e);
        }

        return ruleResult;
    }

    /**
     * 更新对话记忆（复用 ConversationMemoryService）
     *
     * 参考 IntentExecutorServiceImpl:648 的 updateConversationMemory() 实现。
     *
     * @param sessionId 会话ID
     * @param request 查询请求
     * @param intentResult 意图识别结果
     * @param responseText 响应文本
     */
    private void updateConversationMemory(String sessionId, NLQueryRequest request,
                                          IntentResult intentResult, String responseText) {
        if (!conversationEnabled || conversationMemoryService == null) {
            return;
        }

        if (sessionId == null || sessionId.isEmpty()) {
            return;
        }

        try {
            // 记录用户消息
            conversationMemoryService.addMessage(sessionId,
                    ConversationMessage.user(request.getEffectiveQuery()));

            // 记录助手回复
            conversationMemoryService.addMessage(sessionId,
                    ConversationMessage.assistant(responseText, intentResult.getIntent().getCode()));

            // 更新最后意图
            conversationMemoryService.updateLastIntent(sessionId, intentResult.getIntent().getCode());

            log.debug("对话记忆已更新: sessionId={}, intent={}", sessionId, intentResult.getIntent().getCode());
        } catch (Exception e) {
            log.warn("更新对话记忆失败: sessionId={}, error={}", sessionId, e.getMessage());
        }
    }

    /**
     * 将 SmartBIIntent 枚举转换为 AIIntentConfig 列表
     * 用于 LLM Fallback 调用
     */
    private List<AIIntentConfig> convertToIntentConfigs(SmartBIIntent[] intents) {
        List<AIIntentConfig> configs = new ArrayList<>();
        for (SmartBIIntent intent : intents) {
            if (intent == SmartBIIntent.UNKNOWN) {
                continue;
            }
            AIIntentConfig config = AIIntentConfig.builder()
                    .intentCode(intent.getCode())
                    .intentName(intent.getName())
                    .intentCategory(intent.getCategory())
                    .build();
            configs.add(config);
        }
        return configs;
    }

    /**
     * 将 AI Chat 的 IntentMatchResult 转换为 SmartBI 的 IntentResult
     */
    private IntentResult convertToSmartBIIntentResult(IntentMatchResult matchResult, String originalQuery) {
        SmartBIIntent intent = SmartBIIntent.fromCode(matchResult.getBestMatch().getIntentCode());

        return IntentResult.builder()
                .intent(intent)
                .confidence(matchResult.getConfidence())
                .originalQuery(originalQuery)
                .needsLLMFallback(false)
                .matchMethod("LLM")
                .build();
    }

    // ==================== 数据下钻 ====================

    @Override
    @Transactional  // 移除 readOnly=true，因为 recordUsage() 需要写入数据库
    public Map<String, Object> processDrillDown(String factoryId, DrillDownRequest request) {
        log.info("处理数据下钻: factoryId={}, dimension={}, filterValue={}",
                factoryId, request.getDimension(), request.getFilterValue());

        Map<String, Object> result = new HashMap<>();
        LocalDate startDate = request.getStartDate();
        LocalDate endDate = request.getEndDate();

        // 使用默认日期范围
        if (startDate == null || endDate == null) {
            DateRange defaultRange = DateRange.thisMonth();
            startDate = defaultRange.getStartDate();
            endDate = defaultRange.getEndDate();
        }

        switch (request.getDimension().toLowerCase()) {
            case "region":
                result = processRegionDrillDown(factoryId, request, startDate, endDate);
                break;

            case "department":
                result = processDepartmentDrillDown(factoryId, request, startDate, endDate);
                break;

            case "product":
                result = processProductDrillDown(factoryId, request, startDate, endDate);
                break;

            case "time":
                result = processTimeDrillDown(factoryId, request, startDate, endDate);
                break;

            case "salesperson":
                result = processSalespersonDrillDown(factoryId, request, startDate, endDate);
                break;

            default:
                throw new BusinessException(400, "不支持的下钻维度: " + request.getDimension())
                        .withHint("请选择支持的下钻维度").withHintTarget("dimension");
        }

        result.put("drillPath", request.getDrillPath());
        result.put("level", request.getLevel());
        result.put("dimension", request.getDimension());

        // 记录使用
        recordUsage(factoryId, null, ActionType.DRILLDOWN.name(), 0, false);

        return result;
    }

    // ==================== 缓存管理 ====================

    @Override
    @Transactional(readOnly = true)
    public Optional<Object> getFromCache(String factoryId, String cacheKey) {
        Optional<SmartBiAnalysisCache> cacheOpt = cacheRepository.findByFactoryIdAndCacheKey(factoryId, cacheKey);

        if (cacheOpt.isEmpty()) {
            return Optional.empty();
        }

        SmartBiAnalysisCache cache = cacheOpt.get();

        // 检查是否过期
        if (cache.isExpired()) {
            log.debug("缓存已过期: factoryId={}, cacheKey={}", factoryId, cacheKey);
            return Optional.empty();
        }

        // 反序列化缓存数据
        try {
            if (cacheKey.startsWith("dashboard:")) {
                DashboardResponse response = objectMapper.readValue(cache.getKpiData(), DashboardResponse.class);
                return Optional.of(response);
            }
            if (cacheKey.startsWith("dashboard_llm_insights:")) {
                List<AIInsight> insights = objectMapper.readValue(cache.getKpiData(),
                        new TypeReference<List<AIInsight>>() {});
                return Optional.of(insights);
            }
            // 其他类型的缓存反序列化
            return Optional.of(cache.getKpiData());
        } catch (JsonProcessingException e) {
            log.error("缓存反序列化失败: factoryId={}, cacheKey={}", factoryId, cacheKey, e);
            return Optional.empty();
        }
    }

    @Override
    @Transactional
    public void saveToCache(String factoryId, String cacheKey, Object data, Duration ttl) {
        log.debug("保存缓存: factoryId={}, cacheKey={}, ttl={}", factoryId, cacheKey, ttl);

        try {
            String jsonData = objectMapper.writeValueAsString(data);
            String analysisType = extractAnalysisTypeFromCacheKey(cacheKey);

            // 查找或创建缓存记录
            SmartBiAnalysisCache cache = cacheRepository.findByFactoryIdAndCacheKey(factoryId, cacheKey)
                    .orElse(SmartBiAnalysisCache.builder()
                            .factoryId(factoryId)
                            .cacheKey(cacheKey)
                            .analysisType(analysisType)
                            .analysisDate(LocalDate.now())
                            .build());

            cache.setKpiData(jsonData);
            cache.setExpiresAt(LocalDateTime.now().plus(ttl));

            cacheRepository.save(cache);
        } catch (JsonProcessingException e) {
            log.error("缓存序列化失败: factoryId={}, cacheKey={}", factoryId, cacheKey, e);
        }
    }

    // ==================== 使用记录 ====================

    @Override
    @Transactional
    public void recordUsage(String factoryId, Long userId, String actionType, int tokenCount, boolean cacheHit) {
        SmartBiUsageRecord record = SmartBiUsageRecord.builder()
                .factoryId(factoryId)
                .userId(userId)
                .actionType(ActionType.valueOf(actionType))
                .tokenCount(tokenCount)
                .cacheHit(cacheHit)
                .success(true)
                .build();

        // 计算费用
        BigDecimal cost = calculateCost(factoryId, tokenCount, cacheHit);
        record.setCostAmount(cost);

        usageRepository.save(record);
    }

    /**
     * 记录查询使用（包含查询详情）
     */
    private void recordUsageWithQuery(String factoryId, Long userId, String queryText,
                                       String intentDetected, int tokenCount, boolean cacheHit,
                                       int responseTimeMs) {
        SmartBiUsageRecord record = SmartBiUsageRecord.builder()
                .factoryId(factoryId)
                .userId(userId)
                .actionType(ActionType.QUERY)
                .queryText(queryText)
                .intentDetected(intentDetected)
                .tokenCount(tokenCount)
                .cacheHit(cacheHit)
                .responseTimeMs(responseTimeMs)
                .success(true)
                .build();

        BigDecimal cost = calculateCost(factoryId, tokenCount, cacheHit);
        record.setCostAmount(cost);

        usageRepository.save(record);
    }

    // ==================== 配额检查 ====================

    @Override
    @Transactional(readOnly = true)
    public boolean checkQuota(String factoryId) {
        Optional<SmartBiBillingConfig> configOpt = billingRepository.findByFactoryId(factoryId);

        if (configOpt.isEmpty()) {
            // 使用默认配额
            Long todayUsage = usageRepository.countTodayUsage(factoryId, LocalDate.now());
            return todayUsage < DEFAULT_DAILY_QUOTA;
        }

        SmartBiBillingConfig config = configOpt.get();

        if (!config.getIsActive()) {
            return false;
        }

        if (config.isUnlimitedMode()) {
            return true;
        }

        if (config.isQuotaMode()) {
            Long todayUsage = usageRepository.countTodayUsage(factoryId, LocalDate.now());
            return todayUsage < config.getDailyQuota();
        }

        if (config.isPayAsYouGoMode()) {
            // 检查月度费用是否超限
            LocalDateTime monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay();
            LocalDateTime monthEnd = LocalDateTime.now();
            BigDecimal monthCost = usageRepository.sumCostByPeriod(factoryId, monthStart, monthEnd);
            return monthCost == null || monthCost.compareTo(config.getMonthlyLimit()) < 0;
        }

        return false;
    }

    // ==================== AI 洞察生成 ====================

    @Override
    public List<AIInsight> generateAIInsights(String factoryId, DashboardResponse dashboard) {
        List<AIInsight> insights = new ArrayList<>();

        if (dashboard == null || dashboard.getKpiCards() == null) {
            return insights;
        }

        // 1. 从推荐服务获取规则引擎洞察
        List<AIInsight> recommendationInsights = recommendationService.generateInsightSummary(dashboard);
        if (recommendationInsights != null) {
            insights.addAll(recommendationInsights);
        }

        // 2. LLM 生成洞察（复用 PythonLLMClient）
        if (llmInsightEnabled && pythonLLMClient != null && checkQuota(factoryId)) {
            try {
                List<AIInsight> llmInsights = generateLLMInsights(factoryId, dashboard);
                if (llmInsights != null && !llmInsights.isEmpty()) {
                    insights.addAll(llmInsights);
                    log.info("LLM 洞察生成成功: factoryId={}, count={}", factoryId, llmInsights.size());
                }
            } catch (Exception e) {
                log.warn("LLM 洞察生成失败: factoryId={}, error={}", factoryId, e.getMessage());
            }
        }

        // 3. 去重、限制返回数量，按级别排序
        return insights.stream()
                .distinct()
                .sorted(this::compareInsightLevel)
                .limit(5)
                .collect(Collectors.toList());
    }

    /**
     * 使用 LLM 生成 AI 洞察
     *
     * 调用 PythonLLMClient 分析仪表盘数据，生成 3-5 条关键洞察。
     *
     * @param factoryId 工厂ID
     * @param dashboard 仪表盘数据
     * @return LLM 生成的洞察列表
     */
    private List<AIInsight> generateLLMInsights(String factoryId, DashboardResponse dashboard) {
        if (pythonLLMClient == null) {
            return Collections.emptyList();
        }

        try {
            // 构建分析提示词
            String dataJson = objectMapper.writeValueAsString(dashboard.getKpiCards());
            String prompt = buildInsightPrompt(dashboard, dataJson);

            // 系统提示词
            String systemPrompt = "你是一位专业的BI分析师，请基于给定的业务数据生成3-5条关键洞察。\n" +
                    "要求：\n" +
                    "1. 每条洞察包含：level（级别）、category（分类）、message（洞察内容）、relatedEntity（相关实体）、actionSuggestion（建议）\n" +
                    "2. level取值: RED（严重警告）, YELLOW（注意）, GREEN（良好）, INFO（信息）\n" +
                    "3. 优先发现异常和风险（用RED/YELLOW标记）\n" +
                    "4. 提供可操作的行动建议\n" +
                    "5. 返回JSON数组格式：[{\"level\":\"YELLOW\",\"category\":\"销售分析\",\"message\":\"华东区销售增长放缓\",\"relatedEntity\":\"华东区\",\"actionSuggestion\":\"建议加强华东区促销力度\"}]";

            // 调用 LLM — enableThinking(false) 避免 qwen3.5-plus 的 reasoning tokens
            // 撑爆 30s readTimeout (历史事故 2026-04-15: 驾驶舱洞察每次 93s 3x 重试失败)
            ChatCompletionRequest request = ChatCompletionRequest.builder()
                    .messages(List.of(
                            ChatMessage.system(systemPrompt),
                            ChatMessage.user(prompt)
                    ))
                    .temperature(0.3)
                    .maxTokens(1024)
                    .enableThinking(false)
                    .build();

            ChatCompletionResponse response = pythonLLMClient.chatCompletion(request);

            if (response != null && response.getChoices() != null && !response.getChoices().isEmpty()) {
                String content = response.getChoices().get(0).getMessage().getContent();
                return parseLLMInsights(content);
            }
        } catch (Exception e) {
            log.error("LLM 洞察生成异常: {}", e.getMessage(), e);
        }

        return Collections.emptyList();
    }

    /**
     * 构建 LLM 洞察分析提示词
     */
    private String buildInsightPrompt(DashboardResponse dashboard, String kpiJson) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请分析以下业务数据并生成洞察：\n\n");

        // KPI 数据
        prompt.append("【KPI指标】\n").append(kpiJson).append("\n\n");

        // 排名数据
        if (dashboard.getRankings() != null && !dashboard.getRankings().isEmpty()) {
            prompt.append("【排名数据】\n");
            dashboard.getRankings().forEach((key, rankings) -> {
                prompt.append(key).append(": ");
                if (rankings != null && !rankings.isEmpty()) {
                    prompt.append(rankings.stream()
                            .limit(3)
                            .map(r -> r.getName() + "(" + r.getValue() + ")")
                            .collect(Collectors.joining(", ")));
                }
                prompt.append("\n");
            });
        }

        prompt.append("\n请返回JSON数组格式的洞察列表。");
        return prompt.toString();
    }

    /**
     * 解析 LLM 返回的洞察 JSON
     */
    private List<AIInsight> parseLLMInsights(String llmResponse) {
        List<AIInsight> insights = new ArrayList<>();

        try {
            // 提取 JSON 数组（处理可能的 markdown 代码块）
            String jsonContent = llmResponse;
            if (jsonContent.contains("```json")) {
                jsonContent = jsonContent.substring(jsonContent.indexOf("```json") + 7);
                int closeIdx = jsonContent.indexOf("```");
                jsonContent = jsonContent.substring(0, closeIdx >= 0 ? closeIdx : jsonContent.length());
            } else if (jsonContent.contains("```")) {
                jsonContent = jsonContent.substring(jsonContent.indexOf("```") + 3);
                int closeIdx = jsonContent.indexOf("```");
                jsonContent = jsonContent.substring(0, closeIdx >= 0 ? closeIdx : jsonContent.length());
            }
            jsonContent = jsonContent.trim();

            // 解析 JSON 数组
            List<Map<String, Object>> insightMaps = objectMapper.readValue(
                    jsonContent, new TypeReference<List<Map<String, Object>>>() {});

            for (Map<String, Object> map : insightMaps) {
                // AIInsight 字段: level, category, message, relatedEntity, actionSuggestion
                AIInsight insight = AIInsight.builder()
                        .level((String) map.getOrDefault("level", "INFO"))
                        .category((String) map.getOrDefault("category", "LLM_INSIGHT"))
                        .message((String) map.getOrDefault("content",
                                (String) map.getOrDefault("message", "")))
                        .relatedEntity((String) map.getOrDefault("relatedEntity",
                                (String) map.getOrDefault("title", "")))
                        .actionSuggestion((String) map.getOrDefault("actionSuggestion",
                                (String) map.getOrDefault("suggestion", "")))
                        .build();
                insights.add(insight);
            }
        } catch (Exception e) {
            log.warn("解析 LLM 洞察响应失败: {}", e.getMessage());
        }

        return insights;
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 构建缓存键
     */
    private String buildCacheKey(String type, String... params) {
        return type + ":" + String.join(":", params);
    }

    /**
     * 从缓存键提取分析类型
     */
    private String extractAnalysisTypeFromCacheKey(String cacheKey) {
        if (cacheKey.startsWith("dashboard:")) {
            return CACHE_TYPE_DASHBOARD;
        } else if (cacheKey.startsWith("sales:")) {
            return CACHE_TYPE_SALES;
        } else if (cacheKey.startsWith("department:")) {
            return CACHE_TYPE_DEPARTMENT;
        } else if (cacheKey.startsWith("region:")) {
            return CACHE_TYPE_REGION;
        } else if (cacheKey.startsWith("finance:")) {
            return CACHE_TYPE_FINANCE;
        }
        return CACHE_TYPE_DASHBOARD;
    }

    /**
     * 计算日期范围
     */
    private DateRange calculateDateRange(String period) {
        switch (period.toLowerCase()) {
            case "today":
                return DateRange.today();
            case "yesterday":
                return DateRange.yesterday();
            case "week":
            case "this_week":
                return DateRange.thisWeek();
            case "last_week":
                return DateRange.lastWeek();
            case "month":
            case "this_month":
                return DateRange.thisMonth();
            case "last_month":
                return DateRange.lastMonth();
            case "quarter":
            case "this_quarter":
                return DateRange.thisQuarter();
            case "year":
            case "this_year":
                return DateRange.thisYear();
            case "last_year":
                return DateRange.lastYear();
            default:
                return DateRange.thisMonth();
        }
    }

    /**
     * 计算缓存 TTL
     */
    private Duration calculateCacheTtl(String period) {
        switch (period.toLowerCase()) {
            case "today":
                return Duration.ofMinutes(15);
            case "week":
            case "this_week":
            case "month":
            case "this_month":
                return Duration.ofHours(1);
            case "quarter":
            case "this_quarter":
            case "year":
            case "this_year":
                return Duration.ofHours(4);
            default:
                return Duration.ofHours(1);
        }
    }

    /**
     * 根据意图执行查询.
     *
     * <p><strong>P6 TECH DEBT — legacy switch-case.</strong> This method hard-codes
     * the routing for ~15 {@link SmartBIIntent} enum cases (sales / finance /
     * department / forecast etc). It does <em>not</em> go through
     * {@link com.cretas.aims.service.IntentExecutorService} and therefore does
     * not participate in the Tool-Skill pipeline. Adding a new intent here
     * requires: (a) extending {@link SmartBIIntent} enum, (b) adding a case
     * branch here, (c) wiring a dedicated analysis Service. The rest of the
     * system (AI Chat, mobile app, CRM etc.) uses Tool-Skill via
     * {@code IntentExecutorService.execute()} and can add intents via a Flyway
     * migration + {@code @Component Tool} class alone.
     *
     * <p>Restaurant diagnostic intents ({@code RESTAURANT_*}) are routed
     * through the Tool-Skill pipeline via
     * {@link #tryRouteRestaurantDiagnostic} before reaching this method —
     * that's the P5.6 patch. For a full fix, the right move is:
     * <ol>
     *   <li>Convert all 15 legacy handlers (salesService / financeService /
     *       etc) into {@code AbstractBusinessTool} implementations with
     *       {@code @Component} registration</li>
     *   <li>Add Flyway migrations binding each {@link SmartBIIntent} enum code
     *       to its new tool_name in {@code ai_intent_configs}</li>
     *   <li>Replace this method with a single
     *       {@code intentExecutorService.execute()} call + NLQueryResponse
     *       adapter (same pattern as {@link #mapIntentExecuteResponse})</li>
     *   <li>Delete the {@link SmartBIIntent} enum and this method</li>
     * </ol>
     * Estimated effort: 2-3 weeks, ~2000 LOC touched. Tracked in P6 plan.
     */
    private Object executeIntent(String factoryId, IntentResult intentResult) {
        DateRange range = intentResult.getTimeRange();
        if (range == null) {
            range = DateRange.thisMonth();
        }

        SmartBIIntent intent = intentResult.getIntent();
        LocalDate startDate = range.getStartDate();
        LocalDate endDate = range.getEndDate();

        switch (intent) {
            case QUERY_SALES_OVERVIEW:
                return salesService.getSalesOverview(factoryId, startDate, endDate);

            case QUERY_SALES_RANKING:
                return salesService.getSalespersonRanking(factoryId, startDate, endDate);

            case QUERY_SALES_TREND:
                return salesService.getSalesTrendChart(factoryId, startDate, endDate, "DAY");

            case QUERY_DEPARTMENT_PERFORMANCE:
                return deptService.getDepartmentRanking(factoryId, startDate, endDate);

            case QUERY_REGION_ANALYSIS:
                return regionService.getRegionRanking(factoryId, startDate, endDate);

            case QUERY_FINANCE_OVERVIEW:
                return financeService.getFinanceOverview(factoryId, startDate, endDate);

            case QUERY_PROFIT_ANALYSIS:
                return financeService.getProfitMetrics(factoryId, startDate, endDate);

            case QUERY_COST_ANALYSIS:
                return financeService.getCostStructureChart(factoryId, startDate, endDate);

            case QUERY_RECEIVABLE:
                return financeService.getReceivableMetrics(factoryId, endDate);

            case QUERY_PRODUCT_ANALYSIS:
                return salesService.getProductRanking(factoryId, startDate, endDate);

            case COMPARE_PERIOD:
                return handlePeriodComparison(factoryId, intentResult);

            case COMPARE_DEPARTMENT:
                return handleDepartmentComparison(factoryId, intentResult, startDate, endDate);

            case COMPARE_REGION:
                return handleRegionComparison(factoryId, intentResult, startDate, endDate);

            case DRILL_DOWN:
                return handleDrillDownIntent(factoryId, intentResult);

            case FORECAST:
                return handleForecastIntent(factoryId, intentResult, startDate, endDate);

            default:
                // P6: 未知 SmartBIIntent → 尝试 Tool-Skill 管线作为 fallback
                // 这样新增的 RESTAURANT_* Tool 不需要在 switch-case 添加 case
                if (intentExecutorService != null) {
                    log.info("P6 fallback: SmartBIIntent={} 无对应 case, 尝试 Tool-Skill 管线", intent);
                    try {
                        IntentExecuteRequest fallbackReq = IntentExecuteRequest.builder()
                                .userInput(intentResult.getOriginalQuery() != null
                                        ? intentResult.getOriginalQuery() : intent.getName())
                                .previewOnly(false).skipSlotFilling(false).build();
                        IntentExecuteResponse fallbackResp = intentExecutorService.execute(
                                factoryId, fallbackReq, 0L, "restaurant_manager");
                        if (fallbackResp != null && Boolean.TRUE.equals(fallbackResp.getIntentRecognized())) {
                            log.info("P6 fallback 成功: intentCode={}", fallbackResp.getIntentCode());
                            return fallbackResp.getResultData() != null
                                    ? fallbackResp.getResultData()
                                    : Map.of("message", fallbackResp.getMessage());
                        }
                    } catch (Exception e) {
                        log.warn("P6 fallback 失败: {}", e.getMessage());
                    }
                }
                log.warn("未支持的意图类型: {}", intent);
                throw new BusinessException(400, "暂不支持该查询类型: " + intent.getName())
                        .withHint("请尝试其他查询方式, 或联系管理员添加该查询类型支持");
        }
    }

    /**
     * 处理预测分析意图
     */
    private Object handleForecastIntent(String factoryId, IntentResult intentResult,
                                        LocalDate startDate, LocalDate endDate) {
        if (forecastService == null) {
            throw new BusinessException(503, "预测服务未配置")
                    .withHint("请联系管理员开启预测服务");
        }

        // 默认预测未来7天
        int forecastDays = 7;
        Object forecastDaysParam = intentResult.getParameters() != null ?
                intentResult.getParameters().get("forecastDays") : null;
        if (forecastDaysParam instanceof Number) {
            forecastDays = ((Number) forecastDaysParam).intValue();
        }

        // 获取预测指标类型，默认为销售额
        String metricType = intentResult.getStringParameter("metricType");
        if (metricType == null || metricType.isEmpty()) {
            metricType = "SALES";
        }

        log.info("执行预测分析: factoryId={}, metricType={}, forecastDays={}",
                factoryId, metricType, forecastDays);

        // 调用预测服务
        if ("SALES".equalsIgnoreCase(metricType)) {
            return forecastService.forecastSales(factoryId, startDate, endDate, forecastDays);
        } else {
            return forecastService.forecastMetric(factoryId, metricType, startDate, endDate, forecastDays);
        }
    }

    /**
     * 处理时期对比
     */
    private Object handlePeriodComparison(String factoryId, IntentResult intentResult) {
        // 获取当期和对比期数据
        DateRange currentRange = intentResult.getTimeRange() != null ?
                intentResult.getTimeRange() : DateRange.thisMonth();
        DateRange previousRange = DateRange.lastMonth();

        Map<String, Object> result = new HashMap<>();
        result.put("current", salesService.getSalesOverview(
                factoryId, currentRange.getStartDate(), currentRange.getEndDate()));
        result.put("previous", salesService.getSalesOverview(
                factoryId, previousRange.getStartDate(), previousRange.getEndDate()));
        result.put("currentPeriod", currentRange.getOriginalExpression());
        result.put("previousPeriod", previousRange.getOriginalExpression());

        return result;
    }

    /**
     * 处理部门对比
     */
    private Object handleDepartmentComparison(String factoryId, IntentResult intentResult,
                                               LocalDate startDate, LocalDate endDate) {
        List<String> departments = intentResult.getEntities();
        if (departments == null || departments.isEmpty()) {
            // 返回所有部门排名
            return deptService.getDepartmentRanking(factoryId, startDate, endDate);
        }
        // 返回部门详情对比
        Map<String, Object> result = new HashMap<>();
        for (String dept : departments) {
            result.put(dept, deptService.getDepartmentDetail(factoryId, dept, startDate, endDate));
        }
        return result;
    }

    /**
     * 处理区域对比
     */
    private Object handleRegionComparison(String factoryId, IntentResult intentResult,
                                           LocalDate startDate, LocalDate endDate) {
        List<String> regions = intentResult.getEntities();
        if (regions == null || regions.isEmpty()) {
            return regionService.getRegionRanking(factoryId, startDate, endDate);
        }
        Map<String, Object> result = new HashMap<>();
        for (String region : regions) {
            result.put(region, regionService.getRegionDetail(factoryId, region, startDate, endDate));
        }
        return result;
    }

    /**
     * 处理下钻意图
     */
    private Object handleDrillDownIntent(String factoryId, IntentResult intentResult) {
        String dimension = intentResult.getDimension();
        DateRange range = intentResult.getTimeRange() != null ?
                intentResult.getTimeRange() : DateRange.thisMonth();

        DrillDownRequest request = DrillDownRequest.builder()
                .dimension(dimension != null ? dimension : "region")
                .filterValue(intentResult.getEntities() != null && !intentResult.getEntities().isEmpty() ?
                        intentResult.getEntities().get(0) : null)
                .startDate(range.getStartDate())
                .endDate(range.getEndDate())
                .build();

        return processDrillDown(factoryId, request);
    }

    /**
     * BUG #5 FIX (Apr 15 2026): generate analytical responseText via LLM instead of static template.
     * Returns null on any failure so caller falls back to {@link #generateResponseText}.
     *
     * Performance: ~3-8s LLM round-trip (qwen3.5-plus). Timeout protection inside PythonLLMClient.
     * Quota guard: shares the same daily quota check as insights pipeline.
     */
    private String generateLLMResponseText(String factoryId, String userQuery, IntentResult intentResult, Object data) {
        if (!llmInsightEnabled || pythonLLMClient == null) return null;
        if (data == null) return null;
        if (!checkQuota(factoryId)) return null;

        try {
            String dataJson;
            try {
                dataJson = objectMapper.writeValueAsString(data);
            } catch (Exception e) {
                return null;
            }
            // Avoid huge payloads — truncate if needed
            if (dataJson.length() > 6000) {
                dataJson = dataJson.substring(0, 6000) + "...(truncated)";
            }

            String intentName = intentResult.getIntent() != null ? intentResult.getIntent().name() : "UNKNOWN";
            String systemPrompt = "你是青花椒餐饮老板的 AI 经营助手, 用 60-200 字直接回答老板的问题。\n" +
                    "要求:\n" +
                    "1. 引用具体业务数字 (金额/百分比/门店名/菜品名), 不要泛泛而谈\n" +
                    "2. 找出 1-2 个关键发现, 给具体可执行建议\n" +
                    "3. 用口语化中文, 不要 markdown 标题\n" +
                    "4. 如果数据为空或不足以回答, 直接说\"暂无足够数据回答, 请确认上传了 X 报表\"\n" +
                    "5. 不要说\"以下是 XX 分析\"这种空话开头";
            String userPrompt = String.format(
                    "老板问: %s\n意图: %s\n数据片段(JSON): %s\n请直接回答。",
                    userQuery, intentName, dataJson
            );

            ChatCompletionRequest request = ChatCompletionRequest.builder()
                    .messages(List.of(
                            ChatMessage.system(systemPrompt),
                            ChatMessage.user(userPrompt)
                    ))
                    .temperature(0.4)
                    .maxTokens(400)
                    .enableThinking(false)
                    .build();

            ChatCompletionResponse response = pythonLLMClient.chatCompletion(request);
            if (response != null && response.getChoices() != null && !response.getChoices().isEmpty()) {
                String content = response.getChoices().get(0).getMessage().getContent();
                if (content != null && !content.isBlank() && content.length() > 30) {
                    log.info("LLM responseText 生成成功: factoryId={}, intent={}, len={}",
                            factoryId, intentName, content.length());
                    return content.trim();
                }
            }
        } catch (Exception e) {
            log.warn("LLM responseText 生成失败, 回退模板: factoryId={}, error={}", factoryId, e.getMessage());
        }
        return null;
    }

    /**
     * 生成响应文本
     */
    private String generateResponseText(IntentResult intentResult, Object data) {
        SmartBIIntent intent = intentResult.getIntent();
        DateRange range = intentResult.getTimeRange();
        String periodDesc = range != null ? range.getOriginalExpression() : "本月";

        // 根据意图类型生成不同的响应文本
        switch (intent) {
            case QUERY_SALES_OVERVIEW:
                return String.format("以下是%s的销售概览数据：", periodDesc);

            case QUERY_SALES_RANKING:
                return String.format("以下是%s的销售员排名：", periodDesc);

            case QUERY_SALES_TREND:
                return String.format("以下是%s的销售趋势分析：", periodDesc);

            case QUERY_DEPARTMENT_PERFORMANCE:
                return String.format("以下是%s各部门的业绩表现：", periodDesc);

            case QUERY_REGION_ANALYSIS:
                return String.format("以下是%s各区域的销售分析：", periodDesc);

            case QUERY_FINANCE_OVERVIEW:
                return String.format("以下是%s的财务概览：", periodDesc);

            case QUERY_PROFIT_ANALYSIS:
                return String.format("以下是%s的利润分析：", periodDesc);

            case COMPARE_PERIOD:
                return "以下是环比/同比对比分析结果：";

            case COMPARE_DEPARTMENT:
                return "以下是部门对比分析结果：";

            case COMPARE_REGION:
                return "以下是区域对比分析结果：";

            default:
                return "以下是查询结果：";
        }
    }

    /**
     * 生成图表配置
     */
    private List<ChartConfig> generateChartConfig(IntentResult intentResult, Object data) {
        List<ChartConfig> charts = new ArrayList<>();

        // 如果数据本身包含图表，直接返回
        if (data instanceof DashboardResponse) {
            DashboardResponse dashboard = (DashboardResponse) data;
            if (dashboard.getCharts() != null) {
                return new ArrayList<>(dashboard.getCharts().values());
            }
        }

        if (data instanceof ChartConfig) {
            charts.add((ChartConfig) data);
            return charts;
        }

        // 根据意图类型生成默认图表
        SmartBIIntent intent = intentResult.getIntent();
        if (intent.isQueryIntent() && data instanceof List) {
            // 生成排名类图表
            ChartConfig barChart = ChartConfig.builder()
                    .chartType("BAR")
                    .title(intent.getName())
                    .xAxisField("name")
                    .yAxisField("value")
                    .build();
            charts.add(barChart);
        }

        return charts;
    }

    /**
     * 生成后续问题建议
     */
    private List<String> generateFollowUpQuestions(IntentResult intentResult) {
        List<String> questions = new ArrayList<>();
        SmartBIIntent intent = intentResult.getIntent();

        switch (intent) {
            case QUERY_SALES_OVERVIEW:
                questions.add("各部门的销售情况如何？");
                questions.add("哪个销售员表现最好？");
                questions.add("和上月相比增长了多少？");
                break;

            case QUERY_SALES_RANKING:
                questions.add("第一名的详细数据是什么？");
                questions.add("按部门分组看排名");
                questions.add("最近的销售趋势如何？");
                break;

            case QUERY_DEPARTMENT_PERFORMANCE:
                questions.add("哪个部门完成率最低？");
                questions.add("部门之间的对比如何？");
                questions.add("人均产出是多少？");
                break;

            case QUERY_REGION_ANALYSIS:
                questions.add("华东区域的详细数据");
                questions.add("各省份的销售排名");
                questions.add("哪个区域增长最快？");
                break;

            default:
                questions.add("查看销售概览");
                questions.add("查看部门业绩");
                questions.add("查看区域分析");
        }

        return questions.stream().limit(3).collect(Collectors.toList());
    }

    /**
     * 保存查询历史
     */
    private void saveQueryHistory(String factoryId, Long userId, NLQueryRequest request,
                                   IntentResult intentResult, String responseText) {
        try {
            SmartBiQueryHistory history = SmartBiQueryHistory.builder()
                    .factoryId(factoryId)
                    .userId(userId)
                    .sessionId(request.getSessionId())
                    .queryText(request.getEffectiveQuery())
                    .intent(intentResult.getIntent().getCode())
                    .parameters(objectMapper.writeValueAsString(intentResult.getParameters()))
                    .context(request.getContext() != null ?
                            objectMapper.writeValueAsString(request.getContext()) : null)
                    .responseText(responseText)
                    .build();

            queryHistoryRepository.save(history);
        } catch (JsonProcessingException e) {
            log.error("保存查询历史失败", e);
        }
    }

    /**
     * 计算费用
     */
    private BigDecimal calculateCost(String factoryId, int tokenCount, boolean cacheHit) {
        if (cacheHit) {
            return BigDecimal.ZERO;
        }

        Optional<SmartBiBillingConfig> configOpt = billingRepository.findByFactoryId(factoryId);
        if (configOpt.isEmpty()) {
            return BigDecimal.ZERO;
        }

        SmartBiBillingConfig config = configOpt.get();
        if (config.isUnlimitedMode()) {
            return BigDecimal.ZERO;
        }

        return config.getPricePerQuery();
    }

    /**
     * 处理区域下钻
     */
    private Map<String, Object> processRegionDrillDown(String factoryId, DrillDownRequest request,
                                                         LocalDate startDate, LocalDate endDate) {
        Map<String, Object> result = new HashMap<>();

        if (request.getFilterValue() == null || request.getFilterValue().isEmpty()) {
            // 第一层：大区
            result.put("data", regionService.getRegionRanking(factoryId, startDate, endDate));
            result.put("nextLevel", "province");
        } else if (request.getLevel() == null || request.getLevel() <= 1) {
            // 第二层：省份
            result.put("data", regionService.getProvinceRanking(
                    factoryId, request.getFilterValue(), startDate, endDate));
            result.put("nextLevel", "city");
        } else {
            // 第三层：城市
            result.put("data", regionService.getCityRanking(
                    factoryId, request.getFilterValue(), startDate, endDate));
            result.put("nextLevel", null);
        }

        return result;
    }

    /**
     * 处理部门下钻
     */
    private Map<String, Object> processDepartmentDrillDown(String factoryId, DrillDownRequest request,
                                                             LocalDate startDate, LocalDate endDate) {
        Map<String, Object> result = new HashMap<>();

        if (request.getFilterValue() == null || request.getFilterValue().isEmpty()) {
            // 第一层：部门
            result.put("data", deptService.getDepartmentRanking(factoryId, startDate, endDate));
            result.put("nextLevel", "salesperson");
        } else {
            // 第二层：部门详情（包含销售员）
            result.put("data", deptService.getDepartmentDetail(
                    factoryId, request.getFilterValue(), startDate, endDate));
            result.put("nextLevel", null);
        }

        return result;
    }

    /**
     * 处理产品下钻
     */
    private Map<String, Object> processProductDrillDown(String factoryId, DrillDownRequest request,
                                                          LocalDate startDate, LocalDate endDate) {
        Map<String, Object> result = new HashMap<>();

        // 产品排名
        result.put("data", salesService.getProductRanking(factoryId, startDate, endDate));
        result.put("chart", salesService.getProductDistributionChart(factoryId, startDate, endDate));
        result.put("nextLevel", null);

        return result;
    }

    /**
     * 处理时间下钻
     */
    private Map<String, Object> processTimeDrillDown(String factoryId, DrillDownRequest request,
                                                       LocalDate startDate, LocalDate endDate) {
        Map<String, Object> result = new HashMap<>();

        String period = "DAY";
        if (request.getLevel() != null) {
            switch (request.getLevel()) {
                case 1:
                    period = "MONTH";
                    break;
                case 2:
                    period = "WEEK";
                    break;
                default:
                    period = "DAY";
            }
        }

        result.put("data", salesService.getSalesTrendChart(factoryId, startDate, endDate, period));
        result.put("period", period);

        return result;
    }

    /**
     * 处理销售员下钻
     */
    private Map<String, Object> processSalespersonDrillDown(String factoryId, DrillDownRequest request,
                                                              LocalDate startDate, LocalDate endDate) {
        Map<String, Object> result = new HashMap<>();

        if (request.getFilterValue() == null || request.getFilterValue().isEmpty()) {
            result.put("data", salesService.getSalespersonRanking(factoryId, startDate, endDate));
        } else {
            result.put("data", salesService.getSalespersonMetrics(
                    factoryId, request.getFilterValue(), startDate, endDate));
        }

        return result;
    }

    /**
     * 比较洞察级别（用于排序）
     */
    private int compareInsightLevel(AIInsight a, AIInsight b) {
        Map<String, Integer> levelOrder = Map.of(
                "RED", 0,
                "YELLOW", 1,
                "GREEN", 2,
                "INFO", 3
        );
        int orderA = levelOrder.getOrDefault(a.getLevel(), 4);
        int orderB = levelOrder.getOrDefault(b.getLevel(), 4);
        return Integer.compare(orderA, orderB);
    }

    // ==================== 数据日期范围检测 ====================

    @Override
    @Transactional(readOnly = true)
    public DateRange getDataDateRange(String factoryId) {
        log.debug("检测数据日期范围: factoryId={}", factoryId);

        try {
            Object result = salesDataRepository.findDateRangeByFactoryId(factoryId);
            log.debug("查询结果类型: {}, 值: {}", result != null ? result.getClass().getName() : "null", result);

            if (result == null) {
                log.debug("未找到销售数据日期范围: factoryId={}", factoryId);
                return null;
            }

            // 处理不同的返回格式
            LocalDate minDate = null;
            LocalDate maxDate = null;

            if (result instanceof Object[]) {
                Object[] dateRange = (Object[]) result;
                log.debug("Object[] 长度: {}", dateRange.length);

                // 检查是否为嵌套数组 [[minDate, maxDate]]
                if (dateRange.length == 1 && dateRange[0] instanceof Object[]) {
                    Object[] innerArray = (Object[]) dateRange[0];
                    log.debug("嵌套 Object[] 长度: {}", innerArray.length);
                    if (innerArray.length >= 2 && innerArray[0] != null && innerArray[1] != null) {
                        minDate = convertToLocalDate(innerArray[0]);
                        maxDate = convertToLocalDate(innerArray[1]);
                    }
                } else if (dateRange.length >= 2 && dateRange[0] != null && dateRange[1] != null) {
                    // 直接 [minDate, maxDate] 格式
                    minDate = convertToLocalDate(dateRange[0]);
                    maxDate = convertToLocalDate(dateRange[1]);
                }
            } else if (result instanceof java.util.List) {
                // JPA 可能返回 List<Object[]>
                java.util.List<?> list = (java.util.List<?>) result;
                if (!list.isEmpty()) {
                    Object first = list.get(0);
                    if (first instanceof Object[]) {
                        Object[] dateRange = (Object[]) first;
                        if (dateRange.length >= 2 && dateRange[0] != null && dateRange[1] != null) {
                            minDate = convertToLocalDate(dateRange[0]);
                            maxDate = convertToLocalDate(dateRange[1]);
                        }
                    }
                }
            }

            if (minDate == null || maxDate == null) {
                log.debug("未找到有效的日期范围: factoryId={}", factoryId);
                return null;
            }

            log.info("检测到数据日期范围: factoryId={}, minDate={}, maxDate={}", factoryId, minDate, maxDate);

            return DateRange.builder()
                    .startDate(minDate)
                    .endDate(maxDate)
                    .granularity(DateRange.custom(minDate, maxDate).getGranularity())
                    .originalExpression("数据范围 " + minDate + " 至 " + maxDate)
                    .relative(false)
                    .build();
        } catch (Exception e) {
            log.error("检测数据日期范围失败: factoryId={}, error={}", factoryId, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 将数据库返回的日期对象转换为 LocalDate
     */
    private LocalDate convertToLocalDate(Object dateObj) {
        if (dateObj == null) return null;
        if (dateObj instanceof LocalDate) return (LocalDate) dateObj;
        if (dateObj instanceof java.sql.Date) return ((java.sql.Date) dateObj).toLocalDate();
        if (dateObj instanceof java.util.Date) {
            return ((java.util.Date) dateObj).toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        }
        log.warn("未知的日期类型: {}", dateObj.getClass().getName());
        return null;
    }
}

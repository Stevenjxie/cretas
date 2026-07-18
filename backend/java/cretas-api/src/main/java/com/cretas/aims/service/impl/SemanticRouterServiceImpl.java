package com.cretas.aims.service.impl;

import com.cretas.aims.dto.intent.RouteDecision;
import com.cretas.aims.dto.intent.RouteDecision.CandidateMatch;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.repository.config.AIIntentConfigRepository;
import com.cretas.aims.service.EmbeddingClient;
import com.cretas.aims.service.IntentEmbeddingCacheService;
import com.cretas.aims.service.RequestScopedEmbeddingCache;
import com.cretas.aims.service.SemanticRouterService;
import com.cretas.aims.ai.tool.NegationTwinPolicy;
import com.cretas.aims.util.VectorUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 语义路由器服务实现
 *
 * 在 AIIntentServiceImpl 前置一层语义路由器，使用向量相似度做快速决策:
 *
 * 路由策略:
 * - score >= 0.92: DirectExecute - 直接执行，跳过LLM，节省约 500-1000ms
 * - score >= 0.75: NeedReranking - 走关键词+LLM Reranking 确认
 * - score < 0.75: NeedFullLLM - 走完整 LLM Fallback 流程
 *
 * 性能优化:
 * - 启动时预加载所有意图的向量表示 (复用 IntentEmbeddingCacheService)
 * - 使用请求级 Embedding 缓存，避免重复计算
 * - 使用 ConcurrentHashMap 存储意图向量，支持高并发
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-22
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticRouterServiceImpl implements SemanticRouterService {

    private final IntentEmbeddingCacheService embeddingCacheService;
    private final AIIntentConfigRepository intentConfigRepository;
    private final EmbeddingClient embeddingClient;
    private final RequestScopedEmbeddingCache requestScopedCache;
    /** W1b: 单一事实源孪生表(消重,本类原 READ_WRITE_TWIN_PAIRS 已删,委托此服务)。 */
    private final NegationTwinPolicy negationTwinPolicy;

    /**
     * 意图向量缓存: factoryId -> (intentCode -> CachedIntent)
     */
    private final Map<String, Map<String, CachedIntent>> intentVectorCache = new ConcurrentHashMap<>();

    // ==================== 路由阈值配置 ====================

    /**
     * 直接执行阈值 (默认 0.88) - v12.0 降低阈值增加语义路由使用率
     * 经测试，0.88 可以在准确性和效率间取得平衡
     */
    @Value("${cretas.router.threshold.direct-execute:0.88}")
    private double directExecuteThreshold;

    /**
     * Reranking 阈值 (默认 0.70) - v12.0 降低阈值
     * 中等置信度走Reranking，低于此值走完整LLM
     */
    @Value("${cretas.router.threshold.reranking:0.70}")
    private double rerankingThreshold;

    /**
     * 默认返回的候选数量
     */
    @Value("${cretas.router.default-top-n:5}")
    private int defaultTopN;

    // ==================== 统计计数器 ====================

    // Wave-7c: 语义黑洞意图守卫 — 这些意图向量位于语义空间中心，易吸引不相关输入
    // 匹配到这些意图时强制降级为 NEED_RERANKING (走LLM二次确认)
    private static final Set<String> SEMANTIC_GUARD_INTENTS = Set.of(
            "QUALITY_CHECK_CREATE", "SYSTEM_HELP", "MATERIAL_BATCH_RELEASE",
            // v32.4: 扩展黑洞列表 — E2E数据验证的高误吸引率意图
            "SYSTEM_SWITCH_FACTORY", "CUSTOMER_BY_TYPE", "SHIPMENT_STATS",
            "ISAPI_QUERY_CAPABILITIES", "TRACE_PUBLIC",
            // Wave-8: cosine 1.00 误匹配
            "ATTENDANCE_TODAY", "SYSTEM_FEEDBACK", "SHIPMENT_CREATE",
            "FOOD_KNOWLEDGE_QUERY",
            // Wave-9: prod E2E分析 — Tier 1 黑洞 (>60%误匹配率)
            "MATERIAL_BATCH_QUERY",        // 4 wrong, 2 correct
            "TASK_ASSIGN_WORKER",          // 2 wrong, 0 correct
            "CUSTOMER_STATS",              // 2 wrong, 0 correct
            // Wave-9 Tier 2: 50% false positive
            "REPORT_PRODUCTION",           // 2 wrong, 3 correct
            "PROCESSING_BATCH_CREATE",     // 2 wrong, 2 correct
            // Wave-10b: E2E 73-miss 分析 — 高频语义黑洞
            "RESTAURANT_DISH_DELETE",      // 4 wrong, 0 correct — 写入意图不应语义直达
            "RESTAURANT_DISH_UPDATE",      // 3 wrong, 0 correct — 写入意图不应语义直达
            "RESTAURANT_PROCUREMENT_SUGGESTION", // 3 wrong — 泛化吸引
            "CAMERA_SUBSCRIBE",            // 3 wrong, 0 correct — 硬件订阅
            "RESTAURANT_REVENUE_TREND",    // 2 wrong — 趋势类泛化
            "ORDER_TODAY",                 // 2 wrong — 日期类泛化
            "ORDER_LIST",                  // 2 wrong — 通用查询泛化
            "RESTAURANT_ORDER_STATISTICS", // 吸引"创建时间"等无关输入
            "RESTAURANT_DAILY_REVENUE",    // 吸引"今天入了多少出了多少"
            "RESTAURANT_DISH_SALES_RANKING" // 吸引"经营状况总览"
    );

    /** W0: read/write confusion twins margin. The pair table itself moved to NegationTwinPolicy (单一事实源, W1b). */
    private static final double READ_WRITE_TWIN_MARGIN = 0.08;

    /** W1b: 委托 NegationTwinPolicy 的单一事实源孪生表(原本地 READ_WRITE_TWIN_PAIRS 已删)。
     *  对称匹配:a→b 或 b→a 任一是 write→read 孪生即为孪生对。 */
    private boolean isTwinPair(String a, String b) {
        if (a == null || b == null) return false;
        return b.equals(negationTwinPolicy.readTwinOf(a)) || a.equals(negationTwinPolicy.readTwinOf(b));
    }

    // Wave-10: 完全排除意图 — 从语义路由器缓存中彻底移除，不参与任何语义匹配
    // 这些意图的embedding向量存在异常(cosine 1.00命中率100%错误)，GUARD降级不够，需完全排除
    private static final Set<String> SEMANTIC_EXCLUDE_INTENTS = Set.of(
            "CAMERA_UNSUBSCRIBE",          // 3 wrong, 0 correct — cosine 1.00 吸引所有不相关输入
            "SCALE_PROTOCOL_DETECT",       // 秤协议检测 — 硬件专用，不应参与语义路由
            "SCALE_CALIBRATE",             // 秤校准 — 硬件专用
            "SCALE_TROUBLESHOOT",          // Wave-10b: 4次错误命中(FOB/pb/英文/溯源) — 秤故障排查不应语义匹配
            "CAMERA_STREAMS"               // 摄像头流 — 硬件专用
    );

    private final AtomicLong totalRoutes = new AtomicLong(0);
    private final AtomicLong directExecuteCount = new AtomicLong(0);
    private final AtomicLong needRerankingCount = new AtomicLong(0);
    private final AtomicLong needFullLLMCount = new AtomicLong(0);
    private final AtomicLong totalLatencyMs = new AtomicLong(0);

    @PostConstruct
    public void init() {
        log.info("SemanticRouterService initialized with thresholds: directExecute={}, reranking={}",
                directExecuteThreshold, rerankingThreshold);
        // StartupWarmupCoordinator refreshes IntentEmbeddingCacheService-backed vectors
        // after ApplicationReadyEvent.
        // Doing it here encoded every intent on the Spring main thread and then
        // repeated the same work in the shared startup warmup lane.
    }

    @Override
    public RouteDecision route(String factoryId, String userInput) {
        return route(factoryId, userInput, defaultTopN);
    }

    @Override
    public RouteDecision route(String factoryId, String userInput, int topN) {
        long startTime = System.currentTimeMillis();

        // 参数校验
        if (userInput == null || userInput.trim().isEmpty()) {
            log.warn("Empty user input for routing");
            return RouteDecision.needFullLLM(0.0, Collections.emptyList(), userInput, 0);
        }

        // 检查 Embedding 服务是否可用
        if (!embeddingClient.isAvailable()) {
            log.warn("Embedding client not available, falling back to full LLM");
            return RouteDecision.needFullLLM(0.0, Collections.emptyList(), userInput, 0);
        }

        try {
            // 1. 获取用户输入的 Embedding (使用请求级缓存)
            float[] inputEmbedding = requestScopedCache.getOrCompute(userInput);

            // 2. 获取合并的意图向量缓存 (全局 + 工厂特定)
            Map<String, CachedIntent> combinedCache = getCombinedCache(factoryId);

            if (combinedCache.isEmpty()) {
                log.warn("No intent vectors cached for factory: {}, refreshing cache", factoryId);
                refreshCache(factoryId);
                combinedCache = getCombinedCache(factoryId);

                if (combinedCache.isEmpty()) {
                    log.error("Still no intent vectors after refresh, falling back to full LLM");
                    return RouteDecision.needFullLLM(0.0, Collections.emptyList(), userInput, 0);
                }
            }

            // 3. 计算与所有意图的余弦相似度
            List<ScoredIntent> scoredIntents = new ArrayList<>();
            for (Map.Entry<String, CachedIntent> entry : combinedCache.entrySet()) {
                CachedIntent cached = entry.getValue();
                double similarity = VectorUtils.cosineSimilarity(inputEmbedding, cached.embedding);
                scoredIntents.add(new ScoredIntent(entry.getKey(), cached.intent, similarity));
            }

            // 4. 按相似度降序排序
            scoredIntents.sort((a, b) -> Double.compare(b.score, a.score));

            // 5. 获取 Top-N 候选
            List<CandidateMatch> candidates = scoredIntents.stream()
                    .limit(topN)
                    .map(si -> CandidateMatch.fromConfig(si.intent, si.score))
                    .collect(Collectors.toList());

            // 6. 获取最高分数和对应意图
            double topScore = scoredIntents.isEmpty() ? 0.0 : scoredIntents.get(0).score;
            AIIntentConfig bestIntent = scoredIntents.isEmpty() ? null : scoredIntents.get(0).intent;

            long latencyMs = System.currentTimeMillis() - startTime;
            totalLatencyMs.addAndGet(latencyMs);
            totalRoutes.incrementAndGet();

            // 7. 三级路由决策 (委托给 decideFromScored 以便单元测试)
            RouteDecision decision = decideFromScored(scoredIntents, candidates, userInput, latencyMs);

            return decision;

        } catch (Exception e) {
            log.error("SemanticRouter error for '{}': {}", truncate(userInput, 50), e.getMessage(), e);
            long latencyMs = System.currentTimeMillis() - startTime;
            return RouteDecision.needFullLLM(0.0, Collections.emptyList(), userInput, latencyMs);
        }
    }

    @Override
    public RouteDecision route(String factoryId, String userInput, int topN, Set<String> bertHintIntents) {
        if (bertHintIntents == null || bertHintIntents.isEmpty()) {
            return route(factoryId, userInput, topN);
        }

        long startTime = System.currentTimeMillis();

        if (userInput == null || userInput.trim().isEmpty() || !embeddingClient.isAvailable()) {
            return route(factoryId, userInput, topN);
        }

        try {
            float[] inputEmbedding = requestScopedCache.getOrCompute(userInput);
            Map<String, CachedIntent> combinedCache = getCombinedCache(factoryId);

            if (combinedCache.isEmpty()) {
                return route(factoryId, userInput, topN);
            }

            // Wave-12: 先在 BERT 候选意图中搜索
            List<ScoredIntent> hintScored = new ArrayList<>();
            for (String hintIntent : bertHintIntents) {
                CachedIntent cached = combinedCache.get(hintIntent);
                if (cached != null) {
                    double similarity = VectorUtils.cosineSimilarity(inputEmbedding, cached.embedding);
                    hintScored.add(new ScoredIntent(hintIntent, cached.intent, similarity));
                }
            }

            if (!hintScored.isEmpty()) {
                hintScored.sort((a, b) -> Double.compare(b.score, a.score));
                double bestHintScore = hintScored.get(0).score;

                // 如果 BERT 候选中有 >= rerankingThreshold 的匹配，优先使用
                if (bestHintScore >= rerankingThreshold) {
                    long latencyMs = System.currentTimeMillis() - startTime;
                    totalLatencyMs.addAndGet(latencyMs);
                    totalRoutes.incrementAndGet();

                    List<CandidateMatch> candidates = hintScored.stream()
                            .limit(topN)
                            .map(si -> CandidateMatch.fromConfig(si.intent, si.score))
                            .collect(Collectors.toList());

                    AIIntentConfig bestIntent = hintScored.get(0).intent;

                    if (SEMANTIC_GUARD_INTENTS.contains(bestIntent.getIntentCode())) {
                        needRerankingCount.incrementAndGet();
                        log.info("Wave-12 BERT-Hint GUARD_DOWNGRADE: '{}' -> {} (score={}, hints={})",
                                truncate(userInput, 50), bestIntent.getIntentCode(), bestHintScore, bertHintIntents.size());
                        return RouteDecision.needReranking(bestIntent, bestHintScore, candidates, userInput, latencyMs);
                    }

                    if (bestHintScore >= directExecuteThreshold) {
                        directExecuteCount.incrementAndGet();
                        log.info("Wave-12 BERT-Hint DIRECT: '{}' -> {} (score={}, hints={})",
                                truncate(userInput, 50), bestIntent.getIntentCode(), bestHintScore, bertHintIntents.size());
                        return RouteDecision.directExecute(bestIntent, bestHintScore, candidates, userInput, latencyMs);
                    } else {
                        needRerankingCount.incrementAndGet();
                        log.info("Wave-12 BERT-Hint RERANKING: '{}' -> {} (score={}, hints={})",
                                truncate(userInput, 50), bestIntent.getIntentCode(), bestHintScore, bertHintIntents.size());
                        return RouteDecision.needReranking(bestIntent, bestHintScore, candidates, userInput, latencyMs);
                    }
                }
            }

            // BERT 候选中没有好的匹配，回退到全量搜索
            log.debug("Wave-12 BERT-Hint fallback to full search: hints={}, bestHintScore={}",
                    bertHintIntents.size(), hintScored.isEmpty() ? "N/A" : hintScored.get(0).score);
            return route(factoryId, userInput, topN);

        } catch (Exception e) {
            log.error("Wave-12 BERT-Hint route error: {}", e.getMessage(), e);
            return route(factoryId, userInput, topN);
        }
    }

    /**
     * W0: 核心路由决策逻辑 — 从已排序的 scoredIntents 做三级决策。
     *
     * 提取为 package-private 方法以便单元测试 (无需 Spring 容器或 embedding 服务)。
     *
     * @param scoredIntents 已按 score 降序排序的意图列表
     * @param candidates    Top-N 候选列表 (已构建好)
     * @param userInput     原始用户输入 (用于日志)
     * @param latencyMs     路由耗时 (用于日志)
     * @return 路由决策
     */
    RouteDecision decideFromScored(List<ScoredIntent> scoredIntents,
                                   List<CandidateMatch> candidates,
                                   String userInput,
                                   long latencyMs) {
        double topScore = scoredIntents.isEmpty() ? 0.0 : scoredIntents.get(0).score;
        AIIntentConfig bestIntent = scoredIntents.isEmpty() ? null : scoredIntents.get(0).intent;

        if (topScore >= directExecuteThreshold && bestIntent != null) {
            // W0 twin-pair margin guard: narrow margin between read/write confusion twin → downgrade
            if (scoredIntents.size() >= 2) {
                double margin = scoredIntents.get(0).score - scoredIntents.get(1).score;
                String c1 = scoredIntents.get(0).intentCode;
                String c2 = scoredIntents.get(1).intentCode;
                if (margin < READ_WRITE_TWIN_MARGIN && isTwinPair(c1, c2)) {
                    needRerankingCount.incrementAndGet();
                    log.info("SemanticRouter: TWIN_MARGIN_DOWNGRADE '{}' -> {} vs {} (margin={}, latency={}ms)",
                            truncate(userInput, 50), c1, c2, String.format("%.3f", margin), latencyMs);
                    return RouteDecision.needReranking(bestIntent, topScore, candidates, userInput, latencyMs);
                }
            }
            if (SEMANTIC_GUARD_INTENTS.contains(bestIntent.getIntentCode())) {
                // 黑洞意图降级: 强制走 RERANKING (LLM二次确认)
                needRerankingCount.incrementAndGet();
                log.info("SemanticRouter: GUARD_DOWNGRADE for '{}' -> {} (score={}, latency={}ms) — 黑洞意图降级为RERANKING",
                        truncate(userInput, 50), bestIntent.getIntentCode(), topScore, latencyMs);
                return RouteDecision.needReranking(bestIntent, topScore, candidates, userInput, latencyMs);
            } else {
                // 高置信度: 直接执行
                directExecuteCount.incrementAndGet();
                log.info("SemanticRouter: DIRECT_EXECUTE for '{}' -> {} (score={}, latency={}ms)",
                        truncate(userInput, 50), bestIntent.getIntentCode(), topScore, latencyMs);
                return RouteDecision.directExecute(bestIntent, topScore, candidates, userInput, latencyMs);
            }
        } else if (topScore >= rerankingThreshold) {
            // 中等置信度: 需要 Reranking
            needRerankingCount.incrementAndGet();
            log.info("SemanticRouter: NEED_RERANKING for '{}' -> {} (score={}, latency={}ms)",
                    truncate(userInput, 50),
                    bestIntent != null ? bestIntent.getIntentCode() : "null",
                    topScore, latencyMs);
            return RouteDecision.needReranking(bestIntent, topScore, candidates, userInput, latencyMs);
        } else {
            // 低置信度: 需要完整 LLM
            needFullLLMCount.incrementAndGet();
            log.info("SemanticRouter: NEED_FULL_LLM for '{}' (topScore={}, latency={}ms)",
                    truncate(userInput, 50), topScore, latencyMs);
            return RouteDecision.needFullLLM(topScore, candidates, userInput, latencyMs);
        }
    }

    @Override
    public void refreshCache(String factoryId) {
        log.info("Refreshing semantic router cache for factory: {}", factoryId);

        try {
            // 从数据库加载意图配置
            List<AIIntentConfig> intents = intentConfigRepository.findByFactoryIdAndEnabled(factoryId, true);

            Map<String, CachedIntent> factoryCache = new ConcurrentHashMap<>();

            for (AIIntentConfig intent : intents) {
                // Wave-10: 完全排除的意图不加入语义缓存
                if (SEMANTIC_EXCLUDE_INTENTS.contains(intent.getIntentCode())) {
                    continue;
                }

                // 从 IntentEmbeddingCacheService 获取预计算的 embedding
                Optional<float[]> embeddingOpt = embeddingCacheService.getIntentEmbedding(factoryId, intent.getIntentCode());

                if (embeddingOpt.isPresent()) {
                    factoryCache.put(intent.getIntentCode(), new CachedIntent(intent, embeddingOpt.get()));
                } else {
                    // 如果缓存中没有，尝试计算 (作为 fallback)
                    String keywordText = buildKeywordText(intent);
                    if (!keywordText.isEmpty() && embeddingClient.isAvailable()) {
                        try {
                            float[] embedding = embeddingClient.encode(keywordText);
                            factoryCache.put(intent.getIntentCode(), new CachedIntent(intent, embedding));
                        } catch (Exception e) {
                            log.warn("Failed to compute embedding for intent {}: {}", intent.getIntentCode(), e.getMessage());
                        }
                    }
                }
            }

            intentVectorCache.put(factoryId, factoryCache);
            log.info("Cached {} intent vectors for factory: {}", factoryCache.size(), factoryId);

        } catch (Exception e) {
            log.error("Failed to refresh cache for factory {}: {}", factoryId, e.getMessage(), e);
        }
    }

    @Override
    public void refreshAllCache() {
        log.info("Refreshing semantic router cache for all factories...");

        try {
            // 1. 加载全局意图 (factoryId = null)
            loadGlobalIntents();

            // 2. 获取所有有意图配置的工厂
            List<String> factoryIds = intentConfigRepository.findDistinctFactoryIds();
            for (String factoryId : factoryIds) {
                if (factoryId != null) {
                    refreshCache(factoryId);
                }
            }

            log.info("Semantic router cache refresh completed");

        } catch (Exception e) {
            log.error("Failed to refresh all cache: {}", e.getMessage(), e);
        }
    }

    @Override
    public RouterStatistics getStatistics() {
        int cachedIntentCount = 0;
        for (Map<String, CachedIntent> factoryCache : intentVectorCache.values()) {
            cachedIntentCount += factoryCache.size();
        }

        double avgLatency = totalRoutes.get() > 0 ?
                (double) totalLatencyMs.get() / totalRoutes.get() : 0.0;

        return new RouterStatisticsImpl(
                totalRoutes.get(),
                directExecuteCount.get(),
                needRerankingCount.get(),
                needFullLLMCount.get(),
                cachedIntentCount,
                avgLatency
        );
    }

    @Override
    public boolean isAvailable() {
        return embeddingClient.isAvailable() && !intentVectorCache.isEmpty();
    }

    // ==================== Private Methods ====================

    /**
     * 加载全局意图 (factoryId = null)
     */
    private void loadGlobalIntents() {
        try {
            List<AIIntentConfig> globalIntents = intentConfigRepository.findGlobalIntents();

            Map<String, CachedIntent> globalCache = new ConcurrentHashMap<>();

            for (AIIntentConfig intent : globalIntents) {
                // Wave-10: 完全排除的意图不加入语义缓存
                if (SEMANTIC_EXCLUDE_INTENTS.contains(intent.getIntentCode())) {
                    continue;
                }

                // 从 IntentEmbeddingCacheService 获取预计算的 embedding
                // 全局意图存储在 "*" key 下
                Optional<float[]> embeddingOpt = embeddingCacheService.getIntentEmbedding("*", intent.getIntentCode());

                if (embeddingOpt.isPresent()) {
                    globalCache.put(intent.getIntentCode(), new CachedIntent(intent, embeddingOpt.get()));
                } else {
                    String keywordText = buildKeywordText(intent);
                    if (!keywordText.isEmpty() && embeddingClient.isAvailable()) {
                        try {
                            float[] embedding = embeddingClient.encode(keywordText);
                            globalCache.put(intent.getIntentCode(), new CachedIntent(intent, embedding));
                        } catch (Exception e) {
                            log.warn("Failed to compute embedding for global intent {}: {}", intent.getIntentCode(), e.getMessage());
                        }
                    }
                }
            }

            intentVectorCache.put("*", globalCache);
            log.info("Cached {} global intent vectors", globalCache.size());

        } catch (Exception e) {
            log.error("Failed to load global intents: {}", e.getMessage(), e);
        }
    }

    /**
     * 获取合并的意图缓存 (全局 + 工厂特定)
     */
    private Map<String, CachedIntent> getCombinedCache(String factoryId) {
        Map<String, CachedIntent> combined = new HashMap<>();

        // 1. 先加载全局意图
        Map<String, CachedIntent> globalCache = intentVectorCache.get("*");
        if (globalCache != null) {
            combined.putAll(globalCache);
        }

        // 2. 再加载工厂特定意图 (会覆盖同名全局意图)
        if (factoryId != null) {
            Map<String, CachedIntent> factoryCache = intentVectorCache.get(factoryId);
            if (factoryCache != null) {
                combined.putAll(factoryCache);
            }
        }

        return combined;
    }

    /**
     * 构建关键词文本用于 Embedding
     */
    private String buildKeywordText(AIIntentConfig intent) {
        StringBuilder sb = new StringBuilder();

        // 添加意图描述
        if (intent.getDescription() != null && !intent.getDescription().isEmpty()) {
            sb.append(intent.getDescription()).append(" ");
        }

        // 添加关键词
        List<String> keywords = intent.getKeywordsList();
        if (keywords != null && !keywords.isEmpty()) {
            sb.append(String.join(" ", keywords));
        }

        return sb.toString().trim();
    }

    /**
     * 截断字符串
     */
    private String truncate(String str, int maxLength) {
        if (str == null) return "";
        return str.length() > maxLength ? str.substring(0, maxLength) + "..." : str;
    }

    // ==================== Inner Classes ====================

    /**
     * 缓存的意图信息
     */
    private static class CachedIntent {
        final AIIntentConfig intent;
        final float[] embedding;

        CachedIntent(AIIntentConfig intent, float[] embedding) {
            this.intent = intent;
            this.embedding = embedding;
        }
    }

    /**
     * 带分数的意图 (package-private for unit testing)
     */
    static class ScoredIntent {
        final String intentCode;
        final AIIntentConfig intent;
        final double score;

        ScoredIntent(String intentCode, AIIntentConfig intent, double score) {
            this.intentCode = intentCode;
            this.intent = intent;
            this.score = score;
        }
    }

    /**
     * 路由统计实现类
     */
    @RequiredArgsConstructor
    private static class RouterStatisticsImpl implements RouterStatistics {
        private final long totalRoutes;
        private final long directExecuteCount;
        private final long needRerankingCount;
        private final long needFullLLMCount;
        private final int cachedIntentCount;
        private final double averageLatencyMs;

        @Override
        public long getTotalRoutes() { return totalRoutes; }

        @Override
        public long getDirectExecuteCount() { return directExecuteCount; }

        @Override
        public long getNeedRerankingCount() { return needRerankingCount; }

        @Override
        public long getNeedFullLLMCount() { return needFullLLMCount; }

        @Override
        public int getCachedIntentCount() { return cachedIntentCount; }

        @Override
        public double getAverageLatencyMs() { return averageLatencyMs; }
    }
}

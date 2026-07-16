package com.cretas.aims.service.impl;

import com.cretas.aims.dto.intent.UnifiedSemanticMatch;
import com.cretas.aims.entity.cache.SemanticCacheConfig;
import com.cretas.aims.util.VectorUtils;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.learning.LearnedExpression;
import com.cretas.aims.repository.SemanticCacheConfigRepository;
import com.cretas.aims.repository.config.AIIntentConfigRepository;
import com.cretas.aims.repository.learning.LearnedExpressionRepository;
import com.cretas.aims.service.EmbeddingClient;
import com.cretas.aims.service.IntentEmbeddingCacheService;
import com.cretas.aims.service.RequestScopedEmbeddingCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 意图 Embedding 缓存服务实现
 *
 * 在启动时预计算所有意图的关键词向量，存储在内存中。
 * 当用户输入时，直接与缓存的向量比对，避免重复计算。
 *
 * 性能优化：
 * - 预计算：启动时一次性计算，后台定时刷新
 * - 内存存储：ConcurrentHashMap，O(1) 查找
 * - 惰性更新：意图变更时只更新单个缓存项
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-05
 */
@Slf4j
@Service
public class IntentEmbeddingCacheServiceImpl implements IntentEmbeddingCacheService {

    private final AIIntentConfigRepository intentConfigRepository;
    private final SemanticCacheConfigRepository cacheConfigRepository;
    private final EmbeddingClient embeddingClient;
    private final LearnedExpressionRepository expressionRepository;
    private final RequestScopedEmbeddingCache requestScopedCache;

    // 意图缓存结构: factoryId -> (intentCode -> embedding)
    private volatile Map<String, Map<String, CachedIntentEmbedding>> intentCache = new ConcurrentHashMap<>();

    // 表达缓存结构: factoryId -> (expressionId -> embedding)
    private volatile Map<String, Map<String, CachedExpressionEmbedding>> expressionCache = new ConcurrentHashMap<>();

    // 统计计数器
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);
    private final AtomicLong totalMatchLatencyMs = new AtomicLong(0);
    private final AtomicLong matchCount = new AtomicLong(0);

    // 默认最低相似度阈值 (优化: 0.60 → 0.72)
    private static final double DEFAULT_MIN_SIMILARITY = 0.72;

    @Autowired
    public IntentEmbeddingCacheServiceImpl(
            AIIntentConfigRepository intentConfigRepository,
            SemanticCacheConfigRepository cacheConfigRepository,
            EmbeddingClient embeddingClient,
            LearnedExpressionRepository expressionRepository,
            RequestScopedEmbeddingCache requestScopedCache) {
        this.intentConfigRepository = intentConfigRepository;
        this.cacheConfigRepository = cacheConfigRepository;
        this.embeddingClient = embeddingClient;
        this.expressionRepository = expressionRepository;
        this.requestScopedCache = requestScopedCache;
    }

    @Override
    public synchronized void initializeCache() {
        if (!embeddingClient.isAvailable()) {
            throw new IllegalStateException("Embedding client unavailable during intent cache warmup");
        }

        log.info("Initializing intent embedding cache...");
        long startTime = System.currentTimeMillis();

        List<AIIntentConfig> allIntents = intentConfigRepository.findAllEnabled();
        List<AIIntentConfig> candidates = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (AIIntentConfig intent : allIntents) {
            String keywordText = buildKeywordText(intent);
            if (!keywordText.isEmpty()) {
                candidates.add(intent);
                texts.add(keywordText);
            }
        }

        List<float[]> vectors = texts.isEmpty() ? List.of() : embeddingClient.encodeBatch(texts);
        if (vectors.size() != candidates.size()) {
            throw new IllegalStateException("Embedding batch size mismatch: expected "
                    + candidates.size() + ", got " + vectors.size());
        }

        Map<String, Map<String, CachedIntentEmbedding>> nextIntentCache = new ConcurrentHashMap<>();
        for (int i = 0; i < candidates.size(); i++) {
            AIIntentConfig intent = candidates.get(i);
            String factoryId = intent.getFactoryId() == null ? "*" : intent.getFactoryId();
            nextIntentCache.computeIfAbsent(factoryId, ignored -> new ConcurrentHashMap<>())
                    .put(intent.getIntentCode(), new CachedIntentEmbedding(intent, vectors.get(i)));
        }

        Map<String, Map<String, CachedExpressionEmbedding>> nextExpressionCache = buildExpressionCache();
        intentCache = nextIntentCache;
        expressionCache = nextExpressionCache;

        long duration = System.currentTimeMillis() - startTime;
        log.info("Intent embedding cache initialized atomically: {} intents cached in {}ms",
                candidates.size(), duration);
    }

    /**
     * 初始化表达 embedding 缓存
     */
    private void initializeExpressionCache() {
        log.info("Initializing expression embedding cache...");
        long startTime = System.currentTimeMillis();
        Map<String, Map<String, CachedExpressionEmbedding>> next = buildExpressionCache();
        expressionCache = next;
        int count = next.values().stream().mapToInt(Map::size).sum();
        log.info("Expression embedding cache initialized: {} expressions cached in {}ms",
                count, System.currentTimeMillis() - startTime);
    }

    private Map<String, Map<String, CachedExpressionEmbedding>> buildExpressionCache() {
        Map<String, Map<String, CachedExpressionEmbedding>> next = new ConcurrentHashMap<>();
        for (LearnedExpression expression : expressionRepository.findByIsActiveTrue()) {
            if (!expression.hasEmbedding()) {
                continue;
            }
            String factoryId = expression.getFactoryId() == null ? "*" : expression.getFactoryId();
            next.computeIfAbsent(factoryId, ignored -> new ConcurrentHashMap<>())
                    .put(expression.getId(), new CachedExpressionEmbedding(
                            expression.getId(), expression.getIntentCode(), expression.getExpression(),
                            expression.getEmbeddingAsFloatArray(),
                            expression.getHitCount() != null ? expression.getHitCount() : 0,
                            Boolean.TRUE.equals(expression.getIsVerified())));
        }
        return next;
    }

    @Scheduled(cron = "0 0 4 * * ?") // 每天凌晨4点刷新
    public void refreshAllCache() {
        log.info("Scheduled refresh of intent embedding cache...");
        initializeCache();
    }

    @Override
    public void refreshFactoryCache(String factoryId) {
        log.debug("Refreshing cache for factory: {}", factoryId);
        intentCache.remove(factoryId);
        expressionCache.remove(factoryId);

        List<AIIntentConfig> intents = intentConfigRepository.findByFactoryIdAndEnabled(factoryId, true);
        for (AIIntentConfig intent : intents) {
            cacheIntent(intent);
        }

        // 同时刷新表达缓存
        refreshExpressionCache(factoryId);
    }

    @Override
    public void refreshIntentCache(String factoryId, String intentCode) {
        intentConfigRepository.findByFactoryIdAndIntentCode(factoryId, intentCode)
            .ifPresent(this::cacheIntent);
    }

    @Override
    public Optional<float[]> getIntentEmbedding(String factoryId, String intentCode) {
        Map<String, CachedIntentEmbedding> factoryCache = intentCache.get(factoryId);
        if (factoryCache == null) {
            return Optional.empty();
        }

        CachedIntentEmbedding cached = factoryCache.get(intentCode);
        if (cached == null) {
            return Optional.empty();
        }

        return Optional.of(cached.embedding);
    }

    @Override
    public List<SemanticMatchResult> matchIntents(String factoryId, String userInput) {
        long startTime = System.currentTimeMillis();

        if (!embeddingClient.isAvailable()) {
            return Collections.emptyList();
        }

        try {
            // 获取配置
            SemanticCacheConfig config = getEffectiveConfig(factoryId);

            // 计算用户输入的 embedding (使用请求级缓存)
            float[] inputEmbedding = requestScopedCache.getOrCompute(userInput);

            // 合并全局缓存 (*) 和工厂特定缓存
            Map<String, CachedIntentEmbedding> combinedCache = new HashMap<>();

            // 1. 先加载全局意图 (factoryId = null 存储为 "*")
            Map<String, CachedIntentEmbedding> globalCache = intentCache.get("*");
            if (globalCache != null) {
                combinedCache.putAll(globalCache);
            }

            // 2. 再加载工厂特定意图 (会覆盖同名全局意图，实现工厂级定制)
            Map<String, CachedIntentEmbedding> factoryCache = intentCache.get(factoryId);
            if (factoryCache != null) {
                combinedCache.putAll(factoryCache);
            }

            // 如果合并后仍为空，尝试初始化
            if (combinedCache.isEmpty()) {
                cacheMisses.incrementAndGet();
                return Collections.emptyList();
            }

            cacheHits.incrementAndGet();

            // 计算相似度并排序
            List<SemanticMatchResult> results = new ArrayList<>();
            double highThreshold = config.getSimilarityThresholdAsDouble();
            double mediumThreshold = config.getMediumThresholdAsDouble();

            for (Map.Entry<String, CachedIntentEmbedding> entry : combinedCache.entrySet()) {
                CachedIntentEmbedding cached = entry.getValue();
                double similarity = VectorUtils.cosineSimilarity(inputEmbedding, cached.embedding);

                MatchLevel level;
                if (similarity >= highThreshold) {
                    level = MatchLevel.HIGH;
                } else if (similarity >= mediumThreshold) {
                    level = MatchLevel.MEDIUM;
                } else if (similarity >= 0.60) {
                    level = MatchLevel.LOW;
                } else {
                    continue; // 太低，跳过
                }

                results.add(new SemanticMatchResultImpl(
                    entry.getKey(),
                    cached.intent,
                    similarity,
                    level
                ));
            }

            // 按相似度降序排序
            results.sort((a, b) -> Double.compare(b.getSimilarity(), a.getSimilarity()));

            long latency = System.currentTimeMillis() - startTime;
            totalMatchLatencyMs.addAndGet(latency);
            matchCount.incrementAndGet();

            log.debug("Semantic match completed for factory={}, searched {} intents, found {} matches in {}ms",
                factoryId, combinedCache.size(), results.size(), latency);

            return results;

        } catch (Exception e) {
            log.error("Semantic matching failed: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    @Override
    public void clearFactoryCache(String factoryId) {
        intentCache.remove(factoryId);
        expressionCache.remove(factoryId);
        log.debug("Cleared cache for factory: {}", factoryId);
    }

    @Override
    public CacheStatistics getStatistics() {
        int totalIntents = 0;
        int cachedIntents = 0;

        for (Map<String, CachedIntentEmbedding> factoryCache : intentCache.values()) {
            cachedIntents += factoryCache.size();
        }

        // 添加表达缓存统计
        int cachedExpressions = 0;
        for (Map<String, CachedExpressionEmbedding> factoryExprCache : expressionCache.values()) {
            cachedExpressions += factoryExprCache.size();
        }

        try {
            totalIntents = (int) intentConfigRepository.countEnabled();
        } catch (Exception e) {
            log.warn("Failed to count intents: {}", e.getMessage());
        }

        double avgLatency = matchCount.get() > 0 ?
            (double) totalMatchLatencyMs.get() / matchCount.get() : 0.0;

        log.trace("Cache stats: {} intents, {} expressions", cachedIntents, cachedExpressions);

        return new CacheStatisticsImpl(
            totalIntents,
            cachedIntents,
            cacheHits.get(),
            cacheMisses.get(),
            avgLatency
        );
    }

    // ========== 统一语义搜索 (意图配置 + 已学习表达) ==========

    @Override
    public List<UnifiedSemanticMatch> matchIntentsWithExpressions(String factoryId, String userInput, double minSimilarity) {
        long startTime = System.currentTimeMillis();

        if (!embeddingClient.isAvailable()) {
            log.debug("Embedding client not available, returning empty results");
            return Collections.emptyList();
        }

        try {
            // 计算用户输入的 embedding (使用请求级缓存)
            float[] inputEmbedding = requestScopedCache.getOrCompute(userInput);
            List<UnifiedSemanticMatch> results = new ArrayList<>();

            // 1. 搜索意图配置 embedding
            Map<String, CachedIntentEmbedding> combinedIntentCache = getCombinedIntentCache(factoryId);
            for (Map.Entry<String, CachedIntentEmbedding> entry : combinedIntentCache.entrySet()) {
                CachedIntentEmbedding cached = entry.getValue();
                double similarity = VectorUtils.cosineSimilarity(inputEmbedding, cached.embedding);

                if (similarity >= minSimilarity) {
                    results.add(UnifiedSemanticMatch.fromIntent(
                        entry.getKey(),
                        similarity,
                        cached.intent.getDescription()
                    ));
                }
            }

            // 2. 搜索已学习表达 embedding
            Map<String, CachedExpressionEmbedding> combinedExprCache = getCombinedExpressionCache(factoryId);
            for (Map.Entry<String, CachedExpressionEmbedding> entry : combinedExprCache.entrySet()) {
                CachedExpressionEmbedding cached = entry.getValue();
                double similarity = VectorUtils.cosineSimilarity(inputEmbedding, cached.embedding);

                if (similarity >= minSimilarity) {
                    results.add(UnifiedSemanticMatch.fromExpression(
                        entry.getKey(),
                        cached.intentCode,
                        similarity,
                        cached.expression,
                        cached.hitCount,
                        cached.verified
                    ));
                }
            }

            // 3. 按优先级排序 (使用 UnifiedSemanticMatch.compare)
            results.sort(UnifiedSemanticMatch::compare);

            long latency = System.currentTimeMillis() - startTime;
            log.debug("Unified semantic match: factory={}, input='{}', " +
                      "searched {} intents + {} expressions, found {} matches in {}ms",
                factoryId, userInput.substring(0, Math.min(30, userInput.length())),
                combinedIntentCache.size(), combinedExprCache.size(), results.size(), latency);

            return results;

        } catch (Exception e) {
            log.error("Unified semantic matching failed: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    @Override
    public void cacheExpression(LearnedExpression expression) {
        if (expression == null || !expression.hasEmbedding()) {
            return;
        }
        cacheExpressionInternal(expression);
    }

    @Override
    public void cacheExpressions(List<LearnedExpression> expressions) {
        if (expressions == null || expressions.isEmpty()) {
            return;
        }
        for (LearnedExpression expr : expressions) {
            if (expr.hasEmbedding()) {
                cacheExpressionInternal(expr);
            }
        }
    }

    @Override
    public void refreshExpressionCache(String factoryId) {
        log.debug("Refreshing expression cache for factory: {}", factoryId);
        expressionCache.remove(factoryId);

        List<LearnedExpression> expressions = expressionRepository.findByFactoryIdAndIsActiveTrue(factoryId);
        for (LearnedExpression expr : expressions) {
            if (expr.hasEmbedding()) {
                cacheExpressionInternal(expr);
            }
        }
    }

    @Override
    public void removeExpressionCache(String expressionId) {
        // 遍历所有工厂缓存，移除指定表达
        for (Map<String, CachedExpressionEmbedding> factoryCache : expressionCache.values()) {
            factoryCache.remove(expressionId);
        }
        log.trace("Removed expression from cache: {}", expressionId);
    }

    // ========== Private Methods ==========

    private void cacheIntent(AIIntentConfig intent) {
        if (!embeddingClient.isAvailable()) {
            return;
        }

        String factoryId = intent.getFactoryId();
        if (factoryId == null) {
            factoryId = "*"; // 全局意图
        }

        // 构建关键词文本
        String keywordText = buildKeywordText(intent);
        if (keywordText.isEmpty()) {
            return;
        }

        try {
            float[] embedding = embeddingClient.encode(keywordText);

            intentCache.computeIfAbsent(factoryId, k -> new ConcurrentHashMap<>())
                .put(intent.getIntentCode(), new CachedIntentEmbedding(intent, embedding));

            log.trace("Cached intent: {} / {}", factoryId, intent.getIntentCode());

        } catch (Exception e) {
            log.warn("Failed to compute embedding for intent {}: {}",
                intent.getIntentCode(), e.getMessage());
        }
    }

    /**
     * 缓存表达 embedding (内部方法)
     */
    private void cacheExpressionInternal(LearnedExpression expression) {
        String factoryId = expression.getFactoryId();
        if (factoryId == null) {
            factoryId = "*"; // 全局表达
        }

        float[] embedding = expression.getEmbeddingAsFloatArray();
        if (embedding == null) {
            return;
        }

        expressionCache.computeIfAbsent(factoryId, k -> new ConcurrentHashMap<>())
            .put(expression.getId(), new CachedExpressionEmbedding(
                expression.getId(),
                expression.getIntentCode(),
                expression.getExpression(),
                embedding,
                expression.getHitCount() != null ? expression.getHitCount() : 0,
                Boolean.TRUE.equals(expression.getIsVerified())
            ));

        log.trace("Cached expression: {} / {}", factoryId, expression.getId());
    }

    /**
     * 获取合并的意图缓存 (全局 + 工厂特定)
     */
    private Map<String, CachedIntentEmbedding> getCombinedIntentCache(String factoryId) {
        Map<String, CachedIntentEmbedding> combined = new HashMap<>();

        // 1. 先加载全局意图
        Map<String, CachedIntentEmbedding> globalCache = intentCache.get("*");
        if (globalCache != null) {
            combined.putAll(globalCache);
        }

        // 2. 再加载工厂特定意图 (会覆盖同名全局意图)
        Map<String, CachedIntentEmbedding> factoryCache = intentCache.get(factoryId);
        if (factoryCache != null) {
            combined.putAll(factoryCache);
        }

        return combined;
    }

    /**
     * 获取合并的表达缓存 (全局 + 工厂特定)
     */
    private Map<String, CachedExpressionEmbedding> getCombinedExpressionCache(String factoryId) {
        Map<String, CachedExpressionEmbedding> combined = new HashMap<>();

        // 1. 先加载全局表达
        Map<String, CachedExpressionEmbedding> globalCache = expressionCache.get("*");
        if (globalCache != null) {
            combined.putAll(globalCache);
        }

        // 2. 再加载工厂特定表达
        Map<String, CachedExpressionEmbedding> factoryCache = expressionCache.get(factoryId);
        if (factoryCache != null) {
            combined.putAll(factoryCache);
        }

        return combined;
    }

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

    private SemanticCacheConfig getEffectiveConfig(String factoryId) {
        return cacheConfigRepository.findByFactoryId(factoryId)
            .or(() -> cacheConfigRepository.findGlobalConfig())
            .orElseGet(SemanticCacheConfig::defaultConfig);
    }

    // ========== Inner Classes ==========

    private static class CachedIntentEmbedding {
        final AIIntentConfig intent;
        final float[] embedding;

        CachedIntentEmbedding(AIIntentConfig intent, float[] embedding) {
            this.intent = intent;
            this.embedding = embedding;
        }
    }

    /**
     * 已学习表达 Embedding 缓存项
     */
    private static class CachedExpressionEmbedding {
        final String id;
        final String intentCode;
        final String expression;
        final float[] embedding;
        final int hitCount;
        final boolean verified;

        CachedExpressionEmbedding(String id, String intentCode, String expression,
                                  float[] embedding, int hitCount, boolean verified) {
            this.id = id;
            this.intentCode = intentCode;
            this.expression = expression;
            this.embedding = embedding;
            this.hitCount = hitCount;
            this.verified = verified;
        }
    }

    @RequiredArgsConstructor
    private static class SemanticMatchResultImpl implements SemanticMatchResult {
        private final String intentCode;
        private final AIIntentConfig intent;
        private final double similarity;
        private final MatchLevel matchLevel;

        @Override
        public String getIntentCode() { return intentCode; }

        @Override
        public AIIntentConfig getIntent() { return intent; }

        @Override
        public double getSimilarity() { return similarity; }

        @Override
        public MatchLevel getMatchLevel() { return matchLevel; }
    }

    @RequiredArgsConstructor
    private static class CacheStatisticsImpl implements CacheStatistics {
        private final int totalIntents;
        private final int cachedIntents;
        private final long cacheHits;
        private final long cacheMisses;
        private final double averageMatchLatencyMs;

        @Override
        public int getTotalIntents() { return totalIntents; }

        @Override
        public int getCachedIntents() { return cachedIntents; }

        @Override
        public long getCacheHits() { return cacheHits; }

        @Override
        public long getCacheMisses() { return cacheMisses; }

        @Override
        public double getAverageMatchLatencyMs() { return averageMatchLatencyMs; }
    }
}

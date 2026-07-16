package com.cretas.aims.service;

import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.SemanticMatchResult;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 语义意图匹配器
 *
 * 使用 Embedding 向量相似度替代精确匹配，提高意图识别的泛化能力。
 *
 * <p>核心功能:</p>
 * <ul>
 *   <li>启动时预计算所有短语的向量并缓存</li>
 *   <li>使用余弦相似度进行语义匹配</li>
 *   <li>支持 Top-K 相似结果返回</li>
 *   <li>降级处理当 Embedding 服务不可用时</li>
 * </ul>
 *
 * <p>使用示例:</p>
 * <pre>
 * "销量最高" 语义匹配到 "销售排名" -> REPORT_KPI
 * "卖得最好" 语义匹配到 "销售冠军" -> REPORT_KPI
 * </pre>
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-24
 */
@Slf4j
@Service
public class SemanticIntentMatcher {

    private final IntentKnowledgeBase knowledgeBase;

    @Autowired(required = false)
    private EmbeddingClient embeddingClient;

    /**
     * 短语向量缓存
     * Key: 短语文本, Value: 向量
     */
    private volatile PhraseSnapshot phraseSnapshot = PhraseSnapshot.empty();

    /**
     * 用户输入向量缓存（LRU，避免重复计算频繁输入）
     */
    private Cache<String, float[]> inputVectorCache;

    /**
     * 语义匹配阈值（默认 0.75）
     */
    @Value("${cretas.ai.semantic.threshold:0.75}")
    private double similarityThreshold;

    /**
     * 是否启用语义匹配（默认启用）
     */
    @Value("${cretas.ai.semantic.enabled:true}")
    private boolean semanticEnabled;

    /**
     * Top-K 返回结果数（默认 3）
     */
    @Value("${cretas.ai.semantic.topK:3}")
    private int topK;

    /**
     * 向量缓存大小（默认 1000）
     */
    @Value("${cretas.ai.semantic.cache.size:1000}")
    private int cacheSize;

    /**
     * 是否已初始化
     */
    private volatile boolean initialized = false;

    /**
     * 初始化失败原因
     */
    private String initFailureReason = null;

    @Autowired
    public SemanticIntentMatcher(IntentKnowledgeBase knowledgeBase) {
        this.knowledgeBase = knowledgeBase;
    }

    /**
     * 初始化：将 IntentKnowledgeBase 中的所有短语向量化并缓存
     */
    @PostConstruct
    public void initializeLocalCache() {

        // 初始化输入向量缓存
        inputVectorCache = Caffeine.newBuilder()
                .maximumSize(cacheSize)
                .expireAfterAccess(30, TimeUnit.MINUTES)
                .build();

    }

    /** Expensive remote warmup, invoked by the serial startup coordinator. */
    public synchronized void initializePhraseVectors() {
        boolean previouslyInitialized = initialized;
        Map<String, String> nextMappings = new LinkedHashMap<>();

        // 复制短语映射 (工厂 + 餐饮)
        Map<String, String> phraseMappings = knowledgeBase.getPhraseToIntentMapping();
        if (phraseMappings != null && !phraseMappings.isEmpty()) {
            nextMappings.putAll(phraseMappings);
            log.info("Loaded {} factory phrase mappings from IntentKnowledgeBase", nextMappings.size());
        } else {
            log.warn("No phrase mappings found in IntentKnowledgeBase");
            initFailureReason = "No phrase mappings available";
            throw new IllegalStateException(initFailureReason);
        }

        // Wave-10: 加载餐饮短语映射，提升餐饮意图语义匹配能力
        Map<String, String> restaurantMappings = knowledgeBase.getRestaurantPhraseMapping();
        if (restaurantMappings != null && !restaurantMappings.isEmpty()) {
            nextMappings.putAll(restaurantMappings);
            log.info("Loaded {} restaurant phrase mappings (total: {})",
                    restaurantMappings.size(), nextMappings.size());
        }

        // 检查 Embedding 服务是否可用
        if (embeddingClient == null) {
            log.warn("EmbeddingClient is not available. Semantic matching will be disabled.");
            initFailureReason = "EmbeddingClient not injected";
            throw new IllegalStateException(initFailureReason);
        }

        if (!embeddingClient.isAvailable()) {
            log.warn("Embedding service is not available at startup. Will retry on first use.");
            initFailureReason = "Embedding service unavailable";
            throw new IllegalStateException(initFailureReason);
        }

        // 批量向量化所有短语
        try {
            List<String> phrases = new ArrayList<>(nextMappings.keySet());
            Map<String, float[]> nextVectors = new HashMap<>();
            log.info("Vectorizing {} phrases...", phrases.size());

            long startTime = System.currentTimeMillis();

            // 分批处理，避免一次性请求过大
            int batchSize = 50;
            for (int i = 0; i < phrases.size(); i += batchSize) {
                int endIndex = Math.min(i + batchSize, phrases.size());
                List<String> batch = phrases.subList(i, endIndex);

                List<float[]> vectors = embeddingClient.encodeBatch(batch);
                if (vectors.size() != batch.size()) {
                    throw new IllegalStateException("Embedding batch size mismatch: expected "
                            + batch.size() + ", got " + vectors.size());
                }

                for (int j = 0; j < batch.size(); j++) {
                    nextVectors.put(batch.get(j), vectors.get(j));
                }

                log.debug("Vectorized batch {}/{}", endIndex, phrases.size());
            }

            long elapsed = System.currentTimeMillis() - startTime;
            phraseSnapshot = new PhraseSnapshot(Map.copyOf(nextVectors), Map.copyOf(nextMappings));
            initialized = true;
            initFailureReason = null;
            log.info("SemanticIntentMatcher initialized successfully. " +
                    "Vectorized {} phrases in {}ms. Model: {}",
                    phraseSnapshot.vectors().size(), elapsed, embeddingClient.getModelName());

        } catch (Exception e) {
            log.error("Failed to initialize phrase vectors: {}", e.getMessage(), e);
            initFailureReason = "Vectorization failed: " + e.getMessage();
            initialized = previouslyInitialized;
            throw e;
        }
    }

    /**
     * 语义匹配：返回最相似的意图
     *
     * @param userInput 用户输入
     * @param threshold 相似度阈值（如果为 null，使用默认阈值）
     * @return 匹配结果
     */
    public SemanticMatchResult matchBySimilarity(String userInput, Double threshold) {
        if (userInput == null || userInput.trim().isEmpty()) {
            return SemanticMatchResult.noMatch();
        }

        double actualThreshold = threshold != null ? threshold : similarityThreshold;
        long startTime = System.currentTimeMillis();

        // 检查是否可用
        if (!isSemanticMatchingAvailable()) {
            log.debug("Semantic matching not available. Reason: {}",
                    initFailureReason != null ? initFailureReason : "Unknown");
            return SemanticMatchResult.noMatch();
        }

        try {
            // 获取或计算用户输入的向量
            float[] inputVector = getInputVector(userInput);
            if (inputVector == null) {
                log.warn("Failed to get vector for input: {}", userInput);
                return SemanticMatchResult.noMatch();
            }

            // 计算与所有短语的相似度
            String bestPhrase = null;
            double bestSimilarity = 0.0;

            PhraseSnapshot snapshot = phraseSnapshot;
            for (Map.Entry<String, float[]> entry : snapshot.vectors().entrySet()) {
                double similarity = cosineSimilarity(inputVector, entry.getValue());
                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity;
                    bestPhrase = entry.getKey();
                }
            }

            long matchTimeMs = System.currentTimeMillis() - startTime;

            // 检查是否超过阈值
            if (bestSimilarity >= actualThreshold && bestPhrase != null) {
                String intentCode = snapshot.mappings().get(bestPhrase);
                log.info("Semantic match found: '{}' -> '{}' (similarity: {:.4f}, intent: {}, time: {}ms)",
                        userInput, bestPhrase, bestSimilarity, intentCode, matchTimeMs);

                return SemanticMatchResult.semanticMatch(
                        intentCode, bestSimilarity, bestPhrase, matchTimeMs);
            }

            log.debug("No semantic match found for '{}'. Best: '{}' (similarity: {:.4f}, threshold: {})",
                    userInput, bestPhrase, bestSimilarity, actualThreshold);
            return SemanticMatchResult.noMatch();

        } catch (EmbeddingClient.EmbeddingException e) {
            log.error("Embedding error during semantic matching: {}", e.getMessage());
            return SemanticMatchResult.noMatch();
        } catch (Exception e) {
            log.error("Unexpected error during semantic matching: {}", e.getMessage(), e);
            return SemanticMatchResult.noMatch();
        }
    }

    /**
     * 查找 Top-K 最相似的意图
     *
     * @param userInput 用户输入
     * @param k 返回数量
     * @param threshold 相似度阈值
     * @return 排序后的匹配结果列表
     */
    public List<SemanticMatchResult> findTopKMatches(String userInput, int k, double threshold) {
        if (userInput == null || userInput.trim().isEmpty()) {
            return Collections.emptyList();
        }

        if (!isSemanticMatchingAvailable()) {
            return Collections.emptyList();
        }

        try {
            float[] inputVector = getInputVector(userInput);
            if (inputVector == null) {
                return Collections.emptyList();
            }

            long startTime = System.currentTimeMillis();

            // 计算所有相似度并排序
            List<Map.Entry<String, Double>> similarities = new ArrayList<>();
            PhraseSnapshot snapshot = phraseSnapshot;
            for (Map.Entry<String, float[]> entry : snapshot.vectors().entrySet()) {
                double similarity = cosineSimilarity(inputVector, entry.getValue());
                if (similarity >= threshold) {
                    similarities.add(new AbstractMap.SimpleEntry<>(entry.getKey(), similarity));
                }
            }

            // 按相似度降序排序
            similarities.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

            long matchTimeMs = System.currentTimeMillis() - startTime;

            // 取 Top-K
            return similarities.stream()
                    .limit(k)
                    .map(entry -> SemanticMatchResult.semanticMatch(
                            snapshot.mappings().get(entry.getKey()),
                            entry.getValue(),
                            entry.getKey(),
                            matchTimeMs))
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Error finding top-K matches: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 计算余弦相似度
     *
     * @param v1 向量1
     * @param v2 向量2
     * @return 余弦相似度 (-1.0 到 1.0)
     */
    private double cosineSimilarity(float[] v1, float[] v2) {
        if (v1 == null || v2 == null || v1.length != v2.length) {
            return 0.0;
        }

        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;

        for (int i = 0; i < v1.length; i++) {
            dotProduct += v1[i] * v2[i];
            norm1 += v1[i] * v1[i];
            norm2 += v2[i] * v2[i];
        }

        double denominator = Math.sqrt(norm1) * Math.sqrt(norm2);
        if (denominator == 0) {
            return 0.0;
        }

        return dotProduct / denominator;
    }

    /**
     * 获取用户输入的向量（带缓存）
     */
    private float[] getInputVector(String input) {
        // 先从缓存获取
        float[] cached = inputVectorCache.getIfPresent(input);
        if (cached != null) {
            return cached;
        }

        // 缓存未命中，调用 Embedding 服务
        try {
            float[] vector = embeddingClient.encode(input);
            inputVectorCache.put(input, vector);
            return vector;
        } catch (EmbeddingClient.EmbeddingException e) {
            log.warn("Failed to encode input '{}': {}", input, e.getMessage());
            return null;
        }
    }

    /**
     * 检查语义匹配是否可用
     */
    public boolean isSemanticMatchingAvailable() {
        if (!semanticEnabled) {
            return false;
        }

        if (embeddingClient == null) {
            return false;
        }

        // 如果尚未初始化，尝试延迟初始化
        return initialized && embeddingClient.isAvailable();
    }

    /**
     * 获取当前阈值
     */
    public double getSimilarityThreshold() {
        return similarityThreshold;
    }

    /**
     * 动态设置阈值
     */
    public void setSimilarityThreshold(double threshold) {
        if (threshold >= 0.0 && threshold <= 1.0) {
            this.similarityThreshold = threshold;
            log.info("Similarity threshold updated to: {}", threshold);
        }
    }

    /**
     * 获取缓存统计信息
     */
    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("phraseVectorCacheSize", phraseSnapshot.vectors().size());
        stats.put("inputVectorCacheSize", inputVectorCache.estimatedSize());
        stats.put("initialized", initialized);
        stats.put("semanticEnabled", semanticEnabled);
        stats.put("similarityThreshold", similarityThreshold);
        stats.put("embeddingAvailable", embeddingClient != null && embeddingClient.isAvailable());
        stats.put("modelName", embeddingClient != null ? embeddingClient.getModelName() : "N/A");
        if (initFailureReason != null) {
            stats.put("initFailureReason", initFailureReason);
        }
        return stats;
    }

    /**
     * 刷新短语向量缓存
     * 当 IntentKnowledgeBase 更新后调用此方法
     */
    public void refreshPhraseVectors() {
        log.info("Refreshing phrase vectors...");
        initializePhraseVectors();
    }

    /**
     * 预热特定短语（用于动态添加新短语）
     */
    public synchronized void warmupPhrase(String phrase, String intentCode) {
        if (phrase == null || intentCode == null) {
            return;
        }

        if (!isSemanticMatchingAvailable()) {
            log.warn("Cannot warmup phrase, semantic matching not available");
            return;
        }

        try {
            float[] vector = embeddingClient.encode(phrase);
            PhraseSnapshot current = phraseSnapshot;
            Map<String, float[]> nextVectors = new HashMap<>(current.vectors());
            Map<String, String> nextMappings = new HashMap<>(current.mappings());
            nextVectors.put(phrase, vector);
            nextMappings.put(phrase, intentCode);
            phraseSnapshot = new PhraseSnapshot(Map.copyOf(nextVectors), Map.copyOf(nextMappings));
            log.info("Warmed up new phrase: '{}' -> {}", phrase, intentCode);
        } catch (Exception e) {
            log.error("Failed to warmup phrase '{}': {}", phrase, e.getMessage());
        }
    }

    private record PhraseSnapshot(Map<String, float[]> vectors, Map<String, String> mappings) {
        private static PhraseSnapshot empty() {
            return new PhraseSnapshot(Map.of(), Map.of());
        }
    }
}

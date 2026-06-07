package com.cretas.aims.service;

import com.cretas.aims.entity.learning.LearnedExpression;
import com.cretas.aims.entity.learning.TrainingSample;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 表达学习服务接口
 *
 * 提供完整表达的学习、匹配和管理功能。
 * 与关键词学习不同，保留完整语义上下文。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-05
 *
 * @deprecated Phase 2B-β: replaced by Python {@code backend/python/ai/learning/expression_learner.py}.
 * This interface still ships in Bucket B for now (no functional change),
 * but no new callers should be added. Phase 3 will remove this entirely.
 */
@Deprecated
public interface ExpressionLearningService {

    // ========== 表达学习 ==========

    /**
     * 学习完整表达
     *
     * @param factoryId   工厂ID
     * @param intentCode  意图代码
     * @param expression  完整表达
     * @param confidence  置信度
     * @param sourceType  来源类型
     * @return 学习的表达实体，如果已存在则返回null
     */
    LearnedExpression learnExpression(String factoryId, String intentCode,
                                       String expression, double confidence,
                                       LearnedExpression.SourceType sourceType);

    /**
     * 批量学习表达
     */
    int learnExpressions(String factoryId, String intentCode,
                         List<String> expressions, double confidence,
                         LearnedExpression.SourceType sourceType);

    /**
     * 学习表达（简化版，用于用户反馈学习）
     * 使用默认置信度 0.9 和 USER_FEEDBACK 来源类型
     *
     * @param factoryId  工厂ID
     * @param expression 完整表达
     * @param intentCode 意图代码
     */
    default void learnExpression(String factoryId, String expression, String intentCode) {
        learnExpression(factoryId, intentCode, expression, 0.9, LearnedExpression.SourceType.USER_FEEDBACK);
    }

    // ========== 表达匹配 ==========

    /**
     * 精确匹配表达 (hash查表, O(1))
     *
     * @param factoryId 工厂ID
     * @param input     用户输入
     * @return 匹配的意图代码
     */
    Optional<ExpressionMatchResult> matchExactExpression(String factoryId, String input);

    /**
     * 相似表达匹配 (编辑距离)
     *
     * @param factoryId  工厂ID
     * @param input      用户输入
     * @param maxResults 最大结果数
     * @param minScore   最小相似度 (0-1)
     * @return 相似表达列表
     */
    List<ExpressionMatchResult> matchSimilarExpressions(String factoryId, String input,
                                                         int maxResults, double minScore);

    // ========== 反馈与验证 ==========

    /**
     * 验证表达 (人工确认)
     */
    boolean verifyExpression(String expressionId, boolean isCorrect);

    /**
     * 禁用表达
     */
    boolean deactivateExpression(String expressionId);

    /**
     * 记录表达命中
     */
    void recordHit(String expressionId);

    // ========== 训练样本收集 ==========

    /**
     * 记录训练样本
     */
    TrainingSample recordSample(String factoryId, String userInput,
                                 String intentCode, TrainingSample.MatchMethod method,
                                 double confidence, String sessionId);

    /**
     * 记录用户反馈（基于样本ID）
     */
    boolean recordFeedback(Long sampleId, boolean isCorrect, String correctIntentCode);

    /**
     * 记录用户反馈（用于意图识别学习）
     *
     * @param factoryId         工厂ID
     * @param userInput         原始用户输入
     * @param matchedIntentCode 系统识别的意图代码
     * @param correctIntentCode 用户认为正确的意图代码
     * @param isCorrect         识别是否正确
     * @param sessionId         会话ID（可选）
     */
    void recordFeedback(String factoryId, String userInput, String matchedIntentCode,
                        String correctIntentCode, Boolean isCorrect, String sessionId);

    // ========== 清理与维护 ==========

    /**
     * 清理低效表达
     *
     * @param factoryId     工厂ID
     * @param minHits       最小命中次数
     * @param daysThreshold 天数阈值
     * @return 清理的表达数量
     */
    int cleanupIneffectiveExpressions(String factoryId, int minHits, int daysThreshold);

    // ========== 置信度分层 / 二次分析 promote ==========

    /**
     * 将置信度达标的 staged 行 (is_active=false, LLM 来源) 升为 active。
     *
     * <p>promote 条件（确定性，无 LLM 调用）：
     * <ol>
     *   <li>is_active = false（staged 状态）</li>
     *   <li>source_type IN (LLM_FALLBACK, LLM_RERANKING)</li>
     *   <li>confidence >= minConfidence（默认 0.90，即已被多次高置信度 LLM 确认）</li>
     *   <li>hit_count >= minHits（默认 3，已有实际命中积累）</li>
     * </ol>
     *
     * @param factoryId     工厂ID（null = 全局）
     * @param minConfidence 最低置信度阈值（推荐 0.90）
     * @param minHits       最低命中次数（推荐 3）
     * @return promote 的行数
     */
    int promoteStaged(String factoryId, double minConfidence, int minHits);

    // ========== NULL 存量审计 ==========

    /**
     * 审计 is_active=NULL 的存量行。
     *
     * <p>返回结构：{@code nullCount}、{@code sample}（最多 30 行快照用于 Opus 人工审查）。
     * 不执行任何写操作。
     *
     * @param sampleSize 采样行数（推荐 30）
     * @return 审计结果（nullCount + sample list）
     */
    NullAuditResult auditNullIsActive(int sampleSize);

    /**
     * NULL 存量审计结果。
     */
    class NullAuditResult {
        private final long nullCount;
        private final List<LearnedExpression> sample;

        public NullAuditResult(long nullCount, List<LearnedExpression> sample) {
            this.nullCount = nullCount;
            this.sample = sample;
        }

        public long getNullCount() { return nullCount; }
        public List<LearnedExpression> getSample() { return sample; }
    }

    /**
     * 清理过期的未反馈样本
     */
    int cleanupExpiredSamples(int daysThreshold);

    // ========== 统计 ==========

    /**
     * 获取学习统计
     */
    LearningStatistics getStatistics(String factoryId, int days);

    /**
     * 获取意图的表达列表
     */
    List<LearnedExpression> getExpressionsByIntent(String factoryId, String intentCode);

    // ========== 内部类 ==========

    /**
     * 表达匹配结果
     */
    class ExpressionMatchResult {
        private String expressionId;
        private String intentCode;
        private String expression;
        private double score;
        private String matchType; // EXACT / SIMILAR

        public ExpressionMatchResult() {}

        public ExpressionMatchResult(String expressionId, String intentCode,
                                      String expression, double score, String matchType) {
            this.expressionId = expressionId;
            this.intentCode = intentCode;
            this.expression = expression;
            this.score = score;
            this.matchType = matchType;
        }

        // Getters and Setters
        public String getExpressionId() { return expressionId; }
        public void setExpressionId(String expressionId) { this.expressionId = expressionId; }
        public String getIntentCode() { return intentCode; }
        public void setIntentCode(String intentCode) { this.intentCode = intentCode; }
        public String getExpression() { return expression; }
        public void setExpression(String expression) { this.expression = expression; }
        public double getScore() { return score; }
        public void setScore(double score) { this.score = score; }
        public String getMatchType() { return matchType; }
        public void setMatchType(String matchType) { this.matchType = matchType; }
    }

    /**
     * 学习统计数据
     */
    class LearningStatistics {
        private long totalExpressions;
        private long activeExpressions;
        private long verifiedExpressions;
        private long totalSamples;
        private long feedbackCount;
        private long positiveCount;
        private long negativeCount;
        private double accuracyRate;
        private double llmFallbackRate;
        private Map<String, Long> expressionsBySource;
        private Map<String, Long> samplesByMethod;

        // Getters and Setters
        public long getTotalExpressions() { return totalExpressions; }
        public void setTotalExpressions(long totalExpressions) { this.totalExpressions = totalExpressions; }
        public long getActiveExpressions() { return activeExpressions; }
        public void setActiveExpressions(long activeExpressions) { this.activeExpressions = activeExpressions; }
        public long getVerifiedExpressions() { return verifiedExpressions; }
        public void setVerifiedExpressions(long verifiedExpressions) { this.verifiedExpressions = verifiedExpressions; }
        public long getTotalSamples() { return totalSamples; }
        public void setTotalSamples(long totalSamples) { this.totalSamples = totalSamples; }
        public long getFeedbackCount() { return feedbackCount; }
        public void setFeedbackCount(long feedbackCount) { this.feedbackCount = feedbackCount; }
        public long getPositiveCount() { return positiveCount; }
        public void setPositiveCount(long positiveCount) { this.positiveCount = positiveCount; }
        public long getNegativeCount() { return negativeCount; }
        public void setNegativeCount(long negativeCount) { this.negativeCount = negativeCount; }
        public double getAccuracyRate() { return accuracyRate; }
        public void setAccuracyRate(double accuracyRate) { this.accuracyRate = accuracyRate; }
        public double getLlmFallbackRate() { return llmFallbackRate; }
        public void setLlmFallbackRate(double llmFallbackRate) { this.llmFallbackRate = llmFallbackRate; }
        public Map<String, Long> getExpressionsBySource() { return expressionsBySource; }
        public void setExpressionsBySource(Map<String, Long> expressionsBySource) { this.expressionsBySource = expressionsBySource; }
        public Map<String, Long> getSamplesByMethod() { return samplesByMethod; }
        public void setSamplesByMethod(Map<String, Long> samplesByMethod) { this.samplesByMethod = samplesByMethod; }
    }
}

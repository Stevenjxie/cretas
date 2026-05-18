package com.cretas.aims.entity.learning;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 训练样本实体
 *
 * 收集所有意图识别样本，用于：
 * 1. 分析识别效果
 * 2. 收集用户反馈
 * 3. 未来模型微调数据
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-05
 */
@Entity
@Table(name = "ai_training_samples", indexes = {
    @Index(name = "idx_ats_factory", columnList = "factory_id"),
    @Index(name = "idx_ats_intent", columnList = "matched_intent_code"),
    @Index(name = "idx_ats_correct", columnList = "is_correct"),
    @Index(name = "idx_ats_method", columnList = "match_method"),
    @Index(name = "idx_ats_created", columnList = "created_at"),
    @Index(name = "idx_ats_source", columnList = "source"),
    @Index(name = "idx_ats_generation", columnList = "generation")
})
@org.hibernate.annotations.DynamicInsert
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainingSample {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 工厂ID
     */
    @Column(name = "factory_id", length = 50)
    private String factoryId;

    /**
     * 原始用户输入
     */
    @Column(name = "user_input", columnDefinition = "TEXT", nullable = false)
    private String userInput;

    /**
     * 匹配的意图代码
     */
    @Column(name = "matched_intent_code", length = 100)
    private String matchedIntentCode;

    /**
     * 匹配方法
     */
    @Column(name = "match_method", length = 20)
    @Enumerated(EnumType.STRING)
    private MatchMethod matchMethod;

    /**
     * 匹配置信度
     */
    @Column(name = "confidence", precision = 5, scale = 4)
    private BigDecimal confidence;

    /**
     * LLM 返回的意图 (用于对比)
     */
    @Column(name = "llm_response_intent", length = 100)
    private String llmResponseIntent;

    /**
     * 用户反馈: 匹配正确?
     * null = 未反馈
     * true = 正确
     * false = 错误
     */
    @Column(name = "is_correct")
    private Boolean isCorrect;

    /**
     * 正确的意图 (若 isCorrect=false)
     */
    @Column(name = "correct_intent_code", length = 100)
    private String correctIntentCode;

    /**
     * 反馈时间
     */
    @Column(name = "feedback_at")
    private LocalDateTime feedbackAt;

    /**
     * Embedding 向量 (可选，用于微调)
     * 768维 * 4字节 = 3072字节
     */
    // columnDefinition was "BLOB" (MySQL-only) — PG-equivalent is BYTEA. Prod
    // table already uses BYTEA via db/migration-pg-converted/V2026_01_05_30.
    // See LearnedExpression.embeddingVector comment for full context.
    @Column(name = "embedding_blob", columnDefinition = "BYTEA")
    private byte[] embeddingBlob;

    /**
     * 会话ID (用于上下文追踪)
     */
    @Column(name = "session_id", length = 100)
    private String sessionId;

    // ========== EnvScaler 合成数据字段 ==========

    /**
     * 样本来源: REAL (真实用户交互) 或 SYNTHETIC (程序化合成)
     */
    @Column(name = "source", length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private SampleSource source = SampleSource.REAL;

    /**
     * 合成世代: 真实数据为0, 合成数据为1
     * 约束: generation <= 1 (防止递归合成导致模型崩溃)
     */
    @Column(name = "generation")
    @Builder.Default
    private Integer generation = 0;

    /**
     * 合成样本的生成器置信度 (0.0-1.0)
     * 表示骨架+槽位填充的确定性
     */
    @Column(name = "synthetic_confidence", precision = 5, scale = 4)
    private BigDecimal syntheticConfidence;

    /**
     * GRAPE 筛选分数 (0.0-1.0)
     * 当前模型对该样本的认可程度，用于筛选分布偏移风险低的样本
     */
    @Column(name = "grape_score", precision = 5, scale = 4)
    private BigDecimal grapeScore;

    /**
     * 骨架ID (用于追溯合成来源)
     */
    @Column(name = "skeleton_id", length = 100)
    private String skeletonId;

    /**
     * 是否为强信号 (高置信度匹配)
     * 注意: insertable=false 让 Hibernate 不插入此列，使用数据库默认值 0
     */
    @Column(name = "is_strong_signal", nullable = false, insertable = false)
    @Builder.Default
    private boolean isStrongSignal = false;

    /**
     * 创建时间
     */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        // isStrongSignal 现在是原始类型，不需要检查 null
    }

    /**
     * 匹配方法枚举
     */
    public enum MatchMethod {
        /** 精确表达匹配 */
        EXACT,
        /** 短语映射匹配 */
        PHRASE_MATCH,
        /** 正则匹配 */
        REGEX,
        /** 关键词匹配 */
        KEYWORD,
        /** 语义匹配 */
        SEMANTIC,
        /** BERT 分类器 */
        CLASSIFIER,
        /** 融合匹配 */
        FUSION,
        /** LLM Fallback */
        LLM,
        /** LLM Reranking (中置信度区间确认) */
        LLM_RERANKING,
        /** 相似表达匹配 */
        SIMILAR_EXPRESSION,
        /** 用户反馈 */
        USER_FEEDBACK,
        /** 域默认 */
        DOMAIN_DEFAULT,
        /** 被拒绝 */
        REJECTED,
        /** 未知 */
        UNKNOWN
    }

    /**
     * 记录用户反馈
     */
    public void recordFeedback(boolean correct, String correctIntent) {
        this.isCorrect = correct;
        this.correctIntentCode = correct ? null : correctIntent;
        this.feedbackAt = LocalDateTime.now();
    }

    /**
     * 设置 embedding (从 float[] 转换)
     */
    public void setEmbeddingFromFloats(float[] embedding) {
        if (embedding == null) {
            this.embeddingBlob = null;
            return;
        }
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(embedding.length * 4);
        for (float f : embedding) {
            buffer.putFloat(f);
        }
        this.embeddingBlob = buffer.array();
    }

    /**
     * 获取 embedding (转换为 float[])
     */
    public float[] getEmbeddingAsFloats() {
        if (embeddingBlob == null || embeddingBlob.length == 0) {
            return null;
        }
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(embeddingBlob);
        float[] result = new float[embeddingBlob.length / 4];
        for (int i = 0; i < result.length; i++) {
            result[i] = buffer.getFloat();
        }
        return result;
    }

    /**
     * 是否适合用于训练
     * 条件: 有反馈 + 置信度 >= 0.7
     */
    public boolean isTrainingReady() {
        return isCorrect != null &&
               confidence != null &&
               confidence.doubleValue() >= 0.7;
    }

    /**
     * 获取训练标签
     */
    public String getTrainingLabel() {
        if (isCorrect == null) {
            return null;
        }
        if (isCorrect) {
            return matchedIntentCode;
        } else {
            return correctIntentCode;
        }
    }

    /**
     * 创建 LLM fallback 样本
     */
    public static TrainingSample createFromLlm(String factoryId, String userInput,
                                                String intentCode, double confidence,
                                                String sessionId) {
        return TrainingSample.builder()
            .factoryId(factoryId)
            .userInput(userInput)
            .matchedIntentCode(intentCode)
            .matchMethod(MatchMethod.LLM)
            .confidence(BigDecimal.valueOf(confidence))
            .llmResponseIntent(intentCode)
            .sessionId(sessionId)
            .isStrongSignal(false)
            .build();
    }

    /**
     * 创建匹配样本
     */
    public static TrainingSample create(String factoryId, String userInput,
                                         String intentCode, MatchMethod method,
                                         double confidence, String sessionId) {
        return TrainingSample.builder()
            .factoryId(factoryId)
            .userInput(userInput)
            .matchedIntentCode(intentCode)
            .matchMethod(method)
            .confidence(BigDecimal.valueOf(confidence))
            .sessionId(sessionId)
            .source(SampleSource.REAL)
            .generation(0)
            .isStrongSignal(false)
            .build();
    }

    // ========== EnvScaler 合成数据方法 ==========

    /**
     * 创建合成样本 (EnvScaler)
     *
     * @param factoryId 工厂ID
     * @param userInput 合成的用户输入
     * @param intentCode 意图代码
     * @param syntheticConfidence 生成器置信度
     * @param skeletonId 骨架ID
     * @return 合成样本
     */
    public static TrainingSample createSynthetic(String factoryId, String userInput,
                                                  String intentCode, double syntheticConfidence,
                                                  String skeletonId) {
        return TrainingSample.builder()
            .factoryId(factoryId)
            .userInput(userInput)
            .matchedIntentCode(intentCode)
            .matchMethod(MatchMethod.UNKNOWN)  // 合成样本暂无匹配方法
            .source(SampleSource.SYNTHETIC)
            .generation(1)  // 合成数据世代为1
            .syntheticConfidence(BigDecimal.valueOf(syntheticConfidence))
            .confidence(BigDecimal.valueOf(syntheticConfidence))  // 同步设置 confidence，使其可被训练查询
            .skeletonId(skeletonId)
            .isCorrect(true)  // 合成数据假定正确
            .isStrongSignal(false)  // 合成数据默认非强信号
            .build();
    }

    /**
     * 是否为合成样本
     */
    public boolean isSynthetic() {
        return SampleSource.SYNTHETIC.equals(this.source);
    }

    /**
     * 是否为真实样本
     */
    public boolean isReal() {
        return SampleSource.REAL.equals(this.source) || this.source == null;
    }

    /**
     * 设置 GRAPE 分数
     */
    public void setGrapeScoreValue(double score) {
        this.grapeScore = BigDecimal.valueOf(score);
    }

    /**
     * 是否通过 GRAPE 筛选 (需要分数 > 0)
     */
    public boolean hasGrapeScore() {
        return grapeScore != null && grapeScore.doubleValue() > 0;
    }

    /**
     * 获取合成样本的有效训练权重
     * 真实样本: 1.0
     * 合成样本: 基于 syntheticConfidence 和 grapeScore 的综合权重
     */
    public double getTrainingWeight(double syntheticBaseWeight) {
        if (isReal()) {
            return 1.0;
        }
        // 合成样本权重 = 基础权重 * 生成器置信度 * GRAPE分数
        double conf = syntheticConfidence != null ? syntheticConfidence.doubleValue() : 0.5;
        double grape = grapeScore != null ? grapeScore.doubleValue() : 0.5;
        return syntheticBaseWeight * conf * grape;
    }
}

package com.cretas.aims.entity.intent;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 工厂级AI学习配置实体
 * 每个工厂可独立配置AI学习参数
 */
@Entity
@Table(name = "factory_ai_learning_config",
    indexes = {
        @Index(name = "idx_learning_phase", columnList = "learning_phase"),
        @Index(name = "idx_auto_learn", columnList = "auto_learn_enabled")
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FactoryAILearningConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", nullable = false, unique = true, length = 50)
    private String factoryId;

    // 自动学习配置
    @Column(name = "auto_learn_enabled")
    @Builder.Default
    private Boolean autoLearnEnabled = true;

    @Column(name = "confidence_threshold", precision = 3, scale = 2)
    @Builder.Default
    private BigDecimal confidenceThreshold = BigDecimal.valueOf(0.90);

    @Column(name = "max_keywords_per_intent")
    @Builder.Default
    private Integer maxKeywordsPerIntent = 50;

    // 学习阶段
    @Column(name = "learning_phase", length = 20)
    @Builder.Default
    private String learningPhase = LearningPhase.LEARNING;

    @Column(name = "phase_transition_date")
    private LocalDate phaseTransitionDate;

    @Column(name = "mature_threshold_days")
    @Builder.Default
    private Integer matureThresholdDays = 90;

    // 清理配置
    @Column(name = "cleanup_enabled")
    @Builder.Default
    private Boolean cleanupEnabled = true;

    @Column(name = "cleanup_threshold", precision = 3, scale = 2)
    @Builder.Default
    private BigDecimal cleanupThreshold = BigDecimal.valueOf(0.30);

    @Column(name = "cleanup_min_negative")
    @Builder.Default
    private Integer cleanupMinNegative = 5;

    // 晋升配置
    @Column(name = "promotion_enabled")
    @Builder.Default
    private Boolean promotionEnabled = true;

    @Column(name = "promotion_min_effectiveness", precision = 3, scale = 2)
    @Builder.Default
    private BigDecimal promotionMinEffectiveness = BigDecimal.valueOf(0.80);

    // Specificity 配置
    @Column(name = "specificity_recalc_enabled")
    @Builder.Default
    private Boolean specificityRecalcEnabled = true;

    @Column(name = "last_specificity_recalc_at")
    private LocalDateTime lastSpecificityRecalcAt;

    // LLM Fallback 配置
    @Column(name = "llm_fallback_enabled")
    @Builder.Default
    private Boolean llmFallbackEnabled = true;

    @Column(name = "llm_new_keyword_weight", precision = 3, scale = 2)
    @Builder.Default
    private BigDecimal llmNewKeywordWeight = BigDecimal.valueOf(0.80);

    // ========== EnvScaler 合成数据配置 ==========

    /**
     * 是否启用合成数据生成
     */
    @Column(name = "synthetic_enabled")
    @Builder.Default
    private Boolean syntheticEnabled = true;

    /**
     * 合成数据被禁用的原因 (熔断时填写)
     */
    @Column(name = "synthetic_disabled_reason", length = 500)
    private String syntheticDisabledReason;

    /**
     * 合成数据被禁用的时间
     */
    @Column(name = "synthetic_disabled_at")
    private LocalDateTime syntheticDisabledAt;

    /**
     * 合成数据占比上限 (默认0.8)
     */
    @Column(name = "synthetic_max_ratio", precision = 3, scale = 2)
    @Builder.Default
    private BigDecimal syntheticMaxRatio = BigDecimal.valueOf(0.80);

    /**
     * 合成数据训练权重 (默认0.5)
     */
    @Column(name = "synthetic_weight", precision = 3, scale = 2)
    @Builder.Default
    private BigDecimal syntheticWeight = BigDecimal.valueOf(0.50);

    // 审计字段
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * 检查是否应该转换到成熟阶段
     *
     * @return true 如果应该转换
     */
    public boolean shouldTransitionToMature() {
        if (!LearningPhase.LEARNING.equals(learningPhase)) {
            return false;
        }
        if (createdAt == null) {
            return false;
        }
        long daysSinceCreation = java.time.temporal.ChronoUnit.DAYS.between(
            createdAt.toLocalDate(), LocalDate.now());
        return daysSinceCreation >= matureThresholdDays;
    }

    /**
     * 转换到成熟阶段
     */
    public void transitionToMature() {
        this.learningPhase = LearningPhase.MATURE;
        this.phaseTransitionDate = LocalDate.now();
    }

    /**
     * 获取有效的置信度阈值
     * 学习阶段使用更低的阈值
     *
     * @return 有效阈值
     */
    public BigDecimal getEffectiveConfidenceThreshold() {
        if (LearningPhase.LEARNING.equals(learningPhase)) {
            // 学习阶段：降低 0.05 以鼓励学习
            return confidenceThreshold.subtract(BigDecimal.valueOf(0.05));
        }
        return confidenceThreshold;
    }

    /**
     * 创建默认配置
     *
     * @param factoryId 工厂ID
     * @return 默认配置
     */
    public static FactoryAILearningConfig createDefault(String factoryId) {
        return FactoryAILearningConfig.builder()
            .factoryId(factoryId)
            .autoLearnEnabled(true)
            .confidenceThreshold(BigDecimal.valueOf(0.90))
            .maxKeywordsPerIntent(50)
            .learningPhase(LearningPhase.LEARNING)
            .matureThresholdDays(90)
            .cleanupEnabled(true)
            .cleanupThreshold(BigDecimal.valueOf(0.30))
            .cleanupMinNegative(5)
            .promotionEnabled(true)
            .promotionMinEffectiveness(BigDecimal.valueOf(0.80))
            .specificityRecalcEnabled(true)
            .llmFallbackEnabled(true)
            .llmNewKeywordWeight(BigDecimal.valueOf(0.80))
            // EnvScaler 默认配置
            .syntheticEnabled(true)
            .syntheticMaxRatio(BigDecimal.valueOf(0.80))
            .syntheticWeight(BigDecimal.valueOf(0.50))
            .build();
    }

    // ========== EnvScaler 合成数据方法 ==========

    /**
     * 禁用合成数据生成
     *
     * @param reason 禁用原因
     */
    public void disableSynthetic(String reason) {
        this.syntheticEnabled = false;
        this.syntheticDisabledReason = reason;
        this.syntheticDisabledAt = LocalDateTime.now();
    }

    /**
     * 重新启用合成数据生成
     */
    public void enableSynthetic() {
        this.syntheticEnabled = true;
        this.syntheticDisabledReason = null;
        this.syntheticDisabledAt = null;
    }

    /**
     * 检查合成数据是否启用
     */
    public boolean isSyntheticEnabled() {
        return Boolean.TRUE.equals(syntheticEnabled);
    }

    /**
     * 获取合成数据训练权重
     */
    public double getSyntheticWeightValue() {
        return syntheticWeight != null ? syntheticWeight.doubleValue() : 0.5;
    }

    /**
     * 获取合成数据最大占比
     */
    public double getSyntheticMaxRatioValue() {
        return syntheticMaxRatio != null ? syntheticMaxRatio.doubleValue() : 0.8;
    }

    /**
     * 学习阶段常量
     */
    public static class LearningPhase {
        public static final String LEARNING = "LEARNING";
        public static final String MATURE = "MATURE";
    }
}

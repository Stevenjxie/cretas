package com.cretas.aims.entity.intent;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 关键词效果追踪实体
 * 记录每个关键词的匹配反馈和效果评分
 */
@Entity
@Table(name = "keyword_effectiveness",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_factory_intent_keyword",
        columnNames = {"factory_id", "intent_code", "keyword"}
    ),
    indexes = {
        @Index(name = "idx_effectiveness", columnList = "effectiveness_score"),
        @Index(name = "idx_intent_code", columnList = "intent_code"),
        @Index(name = "idx_factory_id", columnList = "factory_id"),
        @Index(name = "idx_keyword", columnList = "keyword")
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KeywordEffectiveness {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "intent_code", nullable = false, length = 100)
    private String intentCode;

    @Column(name = "keyword", nullable = false, length = 255)
    private String keyword;

    // 反馈统计
    @Column(name = "positive_count")
    @Builder.Default
    private Integer positiveCount = 0;

    @Column(name = "negative_count")
    @Builder.Default
    private Integer negativeCount = 0;

    // 效果评分
    @Column(name = "effectiveness_score", precision = 5, scale = 4)
    @Builder.Default
    private BigDecimal effectivenessScore = BigDecimal.ONE;

    @Column(name = "weight", precision = 3, scale = 2)
    @Builder.Default
    private BigDecimal weight = BigDecimal.ONE;

    @Column(name = "specificity", precision = 5, scale = 4)
    @Builder.Default
    private BigDecimal specificity = BigDecimal.ONE;

    // 元数据
    @Column(name = "source", length = 50)
    @Builder.Default
    private String source = "MANUAL";

    @Column(name = "is_auto_learned")
    @Builder.Default
    private Boolean isAutoLearned = false;

    @Column(name = "last_matched_at")
    private LocalDateTime lastMatchedAt;

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
     * 计算 Wilson Score Lower Bound
     * 用于评估关键词效果的置信下界
     *
     * @return Wilson Score (0-1)
     */
    public BigDecimal calculateWilsonScore() {
        int n = positiveCount + negativeCount;
        if (n == 0) {
            return BigDecimal.ONE;
        }

        double p = (double) positiveCount / n;
        double z = 1.96; // 95% 置信度
        double denominator = 1 + z * z / n;
        double center = p + z * z / (2 * n);
        double spread = z * Math.sqrt((p * (1 - p) + z * z / (4 * n)) / n);

        double lowerBound = (center - spread) / denominator;
        return BigDecimal.valueOf(Math.max(0, lowerBound));
    }

    /**
     * 记录正向反馈（用户确认匹配正确）
     */
    public void recordPositiveFeedback() {
        this.positiveCount++;
        this.effectivenessScore = calculateWilsonScore();
        this.lastMatchedAt = LocalDateTime.now();
        updateWeight();
    }

    /**
     * 记录负向反馈（用户选择其他意图）
     */
    public void recordNegativeFeedback() {
        this.negativeCount++;
        this.effectivenessScore = calculateWilsonScore();
        updateWeight();
    }

    /**
     * 根据效果评分更新权重
     */
    private void updateWeight() {
        double score = effectivenessScore.doubleValue();
        if (score >= 0.8) {
            // 高效关键词，提升权重到 1.5
            this.weight = BigDecimal.valueOf(1.5);
        } else if (score < 0.5) {
            // 低效关键词，降低权重到 0.5
            this.weight = BigDecimal.valueOf(0.5);
        } else {
            // 中等效果，保持正常权重
            this.weight = BigDecimal.ONE;
        }
    }

    /**
     * 检查是否应该被清理（低效关键词）
     *
     * @param threshold 清理阈值
     * @param minNegative 最小负反馈数
     * @return true 如果应该被清理
     */
    public boolean shouldCleanup(double threshold, int minNegative) {
        return effectivenessScore.doubleValue() < threshold
            && negativeCount >= minNegative;
    }

    /**
     * 来源枚举
     */
    public static class Source {
        public static final String MANUAL = "MANUAL";
        public static final String AUTO_LEARNED = "AUTO_LEARNED";
        public static final String PROMOTED = "PROMOTED";
        public static final String FEEDBACK_LEARNED = "FEEDBACK_LEARNED";
    }
}

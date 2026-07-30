package com.cretas.aims.entity.learning;

import lombok.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Model Performance Log Entity
 *
 * Records AI model performance metrics over time periods for:
 * - Monitoring system health
 * - Identifying performance trends
 * - Triggering alerts for degradation
 * - Planning model improvements
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-24
 */
@Entity
@Table(name = "model_performance_log",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_factory_period",
        columnNames = {"factory_id", "period_type", "period_start"}
    ),
    indexes = {
        @Index(name = "idx_period_type", columnList = "period_type"),
        @Index(name = "idx_period_start", columnList = "period_start"),
        @Index(name = "idx_accuracy", columnList = "accuracy_rate DESC")
    })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ModelPerformanceLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    // ==================== Time Window ====================

    @Column(name = "period_type", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private PeriodType periodType;

    @Column(name = "period_start", nullable = false)
    private LocalDateTime periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDateTime periodEnd;

    // ==================== Basic Metrics ====================

    @Column(name = "total_requests")
    @Builder.Default
    private Long totalRequests = 0L;

    @Column(name = "matched_requests")
    @Builder.Default
    private Long matchedRequests = 0L;

    @Column(name = "high_confidence_count")
    @Builder.Default
    private Long highConfidenceCount = 0L;

    @Column(name = "low_confidence_count")
    @Builder.Default
    private Long lowConfidenceCount = 0L;

    // ==================== Match Method Distribution ====================

    @Column(name = "exact_match_count")
    @Builder.Default
    private Long exactMatchCount = 0L;

    @Column(name = "keyword_match_count")
    @Builder.Default
    private Long keywordMatchCount = 0L;

    @Column(name = "semantic_match_count")
    @Builder.Default
    private Long semanticMatchCount = 0L;

    @Column(name = "llm_fallback_count")
    @Builder.Default
    private Long llmFallbackCount = 0L;

    @Column(name = "unmatched_count")
    @Builder.Default
    private Long unmatchedCount = 0L;

    // ==================== Quality Metrics ====================

    @Column(name = "accuracy_rate", precision = 5, scale = 4)
    private BigDecimal accuracyRate;

    @Column(name = "precision_rate", precision = 5, scale = 4)
    private BigDecimal precisionRate;

    @Column(name = "recall_rate", precision = 5, scale = 4)
    private BigDecimal recallRate;

    @Column(name = "f1_score", precision = 5, scale = 4)
    private BigDecimal f1Score;

    // ==================== Confidence Distribution ====================

    @Column(name = "avg_confidence", precision = 5, scale = 4)
    private BigDecimal avgConfidence;

    @Column(name = "median_confidence", precision = 5, scale = 4)
    private BigDecimal medianConfidence;

    @Column(name = "confidence_std_dev", precision = 5, scale = 4)
    private BigDecimal confidenceStdDev;

    // ==================== User Feedback ====================

    @Column(name = "user_confirmed_count")
    @Builder.Default
    private Long userConfirmedCount = 0L;

    @Column(name = "user_rejected_count")
    @Builder.Default
    private Long userRejectedCount = 0L;

    @Column(name = "no_feedback_count")
    @Builder.Default
    private Long noFeedbackCount = 0L;

    // ==================== Error Attribution ====================

    @Column(name = "rule_miss_count")
    @Builder.Default
    private Long ruleMissCount = 0L;

    @Column(name = "ambiguous_count")
    @Builder.Default
    private Long ambiguousCount = 0L;

    @Column(name = "false_positive_count")
    @Builder.Default
    private Long falsePositiveCount = 0L;

    // ==================== Response Time ====================

    @Column(name = "avg_response_time_ms")
    private Integer avgResponseTimeMs;

    @Column(name = "p95_response_time_ms")
    private Integer p95ResponseTimeMs;

    @Column(name = "p99_response_time_ms")
    private Integer p99ResponseTimeMs;

    // ==================== Audit Fields ====================

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    /**
     * Calculate match rate
     */
    public BigDecimal getMatchRate() {
        if (totalRequests == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf((double) matchedRequests / totalRequests);
    }

    /**
     * Calculate LLM fallback rate
     */
    public BigDecimal getLlmFallbackRate() {
        if (totalRequests == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf((double) llmFallbackCount / totalRequests);
    }

    /**
     * Calculate user confirmation rate
     */
    public BigDecimal getUserConfirmationRate() {
        long totalFeedback = userConfirmedCount + userRejectedCount;
        if (totalFeedback == 0) {
            return null; // No feedback data
        }
        return BigDecimal.valueOf((double) userConfirmedCount / totalFeedback);
    }

    /**
     * Calculate F1 score from precision and recall
     */
    public void calculateF1Score() {
        if (precisionRate == null || recallRate == null) {
            return;
        }
        double precision = precisionRate.doubleValue();
        double recall = recallRate.doubleValue();
        if (precision + recall == 0) {
            this.f1Score = BigDecimal.ZERO;
            return;
        }
        double f1 = 2 * precision * recall / (precision + recall);
        this.f1Score = BigDecimal.valueOf(f1);
    }

    /**
     * Check if performance is degrading compared to previous period
     *
     * @param previous Previous period's log
     * @param threshold Degradation threshold (e.g., 0.05 for 5%)
     * @return true if performance degraded
     */
    public boolean isPerformanceDegraded(ModelPerformanceLog previous, double threshold) {
        if (previous == null || previous.getMatchRate() == null) {
            return false;
        }

        double currentRate = getMatchRate().doubleValue();
        double previousRate = previous.getMatchRate().doubleValue();

        return (previousRate - currentRate) > threshold;
    }

    /**
     * Period type enum
     */
    public enum PeriodType {
        HOURLY,
        DAILY,
        WEEKLY,
        MONTHLY
    }

    /**
     * Create new log for a period
     */
    public static ModelPerformanceLog createForPeriod(
            String factoryId,
            PeriodType periodType,
            LocalDateTime periodStart,
            LocalDateTime periodEnd) {
        return ModelPerformanceLog.builder()
            .factoryId(factoryId)
            .periodType(periodType)
            .periodStart(periodStart)
            .periodEnd(periodEnd)
            .build();
    }
}

package com.cretas.aims.entity.learning;

import lombok.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Learning Suggestion Entity
 *
 * Stores system-generated learning suggestions from active learning analysis:
 * - New keywords discovered from clustering
 * - New expressions to add
 * - Intent splitting/merging recommendations
 * - Keyword deprecation suggestions
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-24
 */
@Entity
@Table(name = "learning_suggestions",
    indexes = {
        @Index(name = "idx_factory", columnList = "factory_id"),
        @Index(name = "idx_type", columnList = "suggestion_type"),
        @Index(name = "idx_status", columnList = "status"),
        @Index(name = "idx_intent", columnList = "intent_code"),
        @Index(name = "idx_priority_status", columnList = "priority DESC, status"),
        @Index(name = "idx_cluster", columnList = "cluster_id")
    })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LearningSuggestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    // ==================== Suggestion Type ====================

    @Column(name = "suggestion_type", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private SuggestionType suggestionType;

    @Column(name = "intent_code", length = 50)
    private String intentCode;

    // ==================== Suggestion Content ====================

    @Column(name = "content", nullable = false, length = 500)
    private String content;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    // ==================== Supporting Data ====================

    @Column(name = "supporting_samples", columnDefinition = "JSON")
    private String supportingSamples;

    @Column(name = "sample_count")
    @Builder.Default
    private Integer sampleCount = 0;

    @Column(name = "confidence_score", precision = 5, scale = 4)
    private BigDecimal confidenceScore;

    // ==================== Clustering Info ====================

    @Column(name = "cluster_id", length = 50)
    private String clusterId;

    @Column(name = "cluster_size")
    private Integer clusterSize;

    // ==================== Status ====================

    @Column(name = "status", length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private SuggestionStatus status = SuggestionStatus.PENDING;

    @Column(name = "priority")
    @Builder.Default
    private Integer priority = 5;

    // ==================== Review Info ====================

    @Column(name = "reviewed_by", length = 50)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "review_notes", columnDefinition = "TEXT")
    private String reviewNotes;

    // ==================== Application Info ====================

    @Column(name = "applied_at")
    private LocalDateTime appliedAt;

    @Column(name = "applied_by", length = 50)
    private String appliedBy;

    // ==================== Effectiveness Tracking ====================

    @Column(name = "effectiveness_before", precision = 5, scale = 4)
    private BigDecimal effectivenessBefore;

    @Column(name = "effectiveness_after", precision = 5, scale = 4)
    private BigDecimal effectivenessAfter;

    @Column(name = "effectiveness_delta", precision = 5, scale = 4)
    private BigDecimal effectivenessDelta;

    // ==================== Expiration ====================

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    // ==================== Audit Fields ====================

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        // Default expiration: 30 days
        if (expiresAt == null) {
            expiresAt = LocalDateTime.now().plusDays(30);
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * Approve the suggestion
     */
    public void approve(String reviewer, String notes) {
        this.status = SuggestionStatus.APPROVED;
        this.reviewedBy = reviewer;
        this.reviewedAt = LocalDateTime.now();
        this.reviewNotes = notes;
    }

    /**
     * Reject the suggestion
     */
    public void reject(String reviewer, String notes) {
        this.status = SuggestionStatus.REJECTED;
        this.reviewedBy = reviewer;
        this.reviewedAt = LocalDateTime.now();
        this.reviewNotes = notes;
    }

    /**
     * Apply the suggestion
     */
    public void apply(String applier, BigDecimal effectivenessBefore) {
        this.status = SuggestionStatus.APPLIED;
        this.appliedBy = applier;
        this.appliedAt = LocalDateTime.now();
        this.effectivenessBefore = effectivenessBefore;
    }

    /**
     * Record effectiveness after applying
     */
    public void recordEffectivenessAfter(BigDecimal effectivenessAfter) {
        this.effectivenessAfter = effectivenessAfter;
        if (this.effectivenessBefore != null && effectivenessAfter != null) {
            this.effectivenessDelta = effectivenessAfter.subtract(effectivenessBefore);
        }
    }

    /**
     * Mark as expired
     */
    public void expire() {
        this.status = SuggestionStatus.EXPIRED;
    }

    /**
     * Check if suggestion is expired
     */
    public boolean isExpired() {
        return expiresAt != null && LocalDateTime.now().isAfter(expiresAt);
    }

    /**
     * Check if suggestion improved effectiveness
     */
    public boolean isEffective() {
        if (effectivenessDelta == null) {
            return false;
        }
        return effectivenessDelta.compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * Suggestion type enum
     */
    public enum SuggestionType {
        NEW_KEYWORD,        // Add new keyword to intent
        NEW_EXPRESSION,     // Add new expression to intent
        NEW_INTENT,         // Create new intent
        MERGE_INTENT,       // Merge similar intents
        SPLIT_INTENT,       // Split broad intent
        DEPRECATE_KEYWORD,  // Remove ineffective keyword
        UPDATE_WEIGHT       // Update keyword weight
    }

    /**
     * Suggestion status enum
     */
    public enum SuggestionStatus {
        PENDING,    // Awaiting review
        APPROVED,   // Approved but not applied
        REJECTED,   // Rejected
        APPLIED,    // Applied to system
        EXPIRED     // Expired without action
    }

    /**
     * Create a new keyword suggestion
     */
    public static LearningSuggestion createKeywordSuggestion(
            String factoryId,
            String intentCode,
            String keyword,
            String reason,
            int sampleCount,
            BigDecimal confidence) {
        return LearningSuggestion.builder()
            .factoryId(factoryId)
            .suggestionType(SuggestionType.NEW_KEYWORD)
            .intentCode(intentCode)
            .content(keyword)
            .description("Add new keyword '" + keyword + "' to intent " + intentCode)
            .reason(reason)
            .sampleCount(sampleCount)
            .confidenceScore(confidence)
            .priority(calculatePriority(sampleCount, confidence))
            .build();
    }

    /**
     * Create a new expression suggestion
     */
    public static LearningSuggestion createExpressionSuggestion(
            String factoryId,
            String intentCode,
            String expression,
            String clusterId,
            int clusterSize,
            BigDecimal confidence) {
        return LearningSuggestion.builder()
            .factoryId(factoryId)
            .suggestionType(SuggestionType.NEW_EXPRESSION)
            .intentCode(intentCode)
            .content(expression)
            .description("Add new expression '" + expression + "' to intent " + intentCode)
            .reason("Discovered from cluster analysis")
            .clusterId(clusterId)
            .clusterSize(clusterSize)
            .sampleCount(clusterSize)
            .confidenceScore(confidence)
            .priority(calculatePriority(clusterSize, confidence))
            .build();
    }

    /**
     * Calculate priority based on sample count and confidence
     */
    private static int calculatePriority(int sampleCount, BigDecimal confidence) {
        int priority = 5;

        // More samples = higher priority
        if (sampleCount >= 20) priority += 2;
        else if (sampleCount >= 10) priority += 1;

        // Higher confidence = higher priority
        if (confidence != null) {
            double conf = confidence.doubleValue();
            if (conf >= 0.9) priority += 2;
            else if (conf >= 0.8) priority += 1;
        }

        return Math.min(10, priority);
    }
}

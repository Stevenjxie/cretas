package com.cretas.aims.entity.learning;

import lombok.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;

/**
 * Cross-Factory Knowledge Entity
 *
 * Stores high-effectiveness keywords and expressions that can be shared
 * across factories. Uses Wilson Score for effectiveness evaluation.
 *
 * Promotion flow:
 * 1. LOCAL: Knowledge discovered in one factory
 * 2. CANDIDATE: High effectiveness, waiting for more adoptions
 * 3. GLOBAL: Adopted by multiple factories with high effectiveness
 * 4. DEPRECATED: Low effectiveness across factories
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-24
 */
@Entity
@Table(name = "cross_factory_knowledge",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_type_intent_content_hash",
        columnNames = {"knowledge_type", "intent_code", "content_hash"}
    ),
    indexes = {
        @Index(name = "idx_knowledge_type", columnList = "knowledge_type"),
        @Index(name = "idx_intent_code", columnList = "intent_code"),
        @Index(name = "idx_promotion_status", columnList = "promotion_status"),
        @Index(name = "idx_effectiveness", columnList = "effectiveness_score DESC"),
        @Index(name = "idx_adoption_count", columnList = "adoption_count DESC")
    })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CrossFactoryKnowledge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ==================== Knowledge Content ====================

    @Column(name = "knowledge_type", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private KnowledgeType knowledgeType;

    @Column(name = "intent_code", nullable = false, length = 50)
    private String intentCode;

    @Column(name = "content", nullable = false, length = 500)
    private String content;

    @Column(name = "content_hash", length = 64)
    private String contentHash;

    // ==================== Source Info ====================

    @Column(name = "source_factory_id", nullable = false, length = 50)
    private String sourceFactoryId;

    @Column(name = "discovered_at")
    private LocalDateTime discoveredAt;

    // ==================== Effectiveness Evaluation ====================

    @Column(name = "effectiveness_score", precision = 5, scale = 4)
    @Builder.Default
    private BigDecimal effectivenessScore = BigDecimal.valueOf(0.5);

    @Column(name = "adoption_count")
    @Builder.Default
    private Integer adoptionCount = 1;

    @Column(name = "positive_feedback_count")
    @Builder.Default
    private Integer positiveFeedbackCount = 0;

    @Column(name = "negative_feedback_count")
    @Builder.Default
    private Integer negativeFeedbackCount = 0;

    // ==================== Promotion Status ====================

    @Column(name = "promotion_status", length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private PromotionStatus promotionStatus = PromotionStatus.LOCAL;

    @Column(name = "promotion_threshold", precision = 3, scale = 2)
    @Builder.Default
    private BigDecimal promotionThreshold = BigDecimal.valueOf(0.80);

    @Column(name = "min_adoption_count")
    @Builder.Default
    private Integer minAdoptionCount = 3;

    // ==================== Quality Control ====================

    @Column(name = "is_verified")
    @Builder.Default
    private Boolean isVerified = false;

    @Column(name = "verified_by", length = 50)
    private String verifiedBy;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    // ==================== Audit Fields ====================

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "promoted_at")
    private LocalDateTime promotedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        discoveredAt = LocalDateTime.now();
        if (contentHash == null) {
            contentHash = calculateContentHash();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * Calculate SHA-256 hash of content
     */
    public String calculateContentHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(
                (knowledgeType.name() + ":" + intentCode + ":" + content)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            // Fallback to simple hash
            return String.valueOf(content.hashCode());
        }
    }

    /**
     * Record positive feedback
     */
    public void recordPositiveFeedback() {
        this.positiveFeedbackCount++;
        recalculateEffectiveness();
    }

    /**
     * Record negative feedback
     */
    public void recordNegativeFeedback() {
        this.negativeFeedbackCount++;
        recalculateEffectiveness();
    }

    /**
     * Increment adoption count
     */
    public void incrementAdoption() {
        this.adoptionCount++;
        checkPromotionEligibility();
    }

    /**
     * Calculate Wilson Score Lower Bound for effectiveness
     */
    public void recalculateEffectiveness() {
        int n = positiveFeedbackCount + negativeFeedbackCount;
        if (n == 0) {
            this.effectivenessScore = BigDecimal.valueOf(0.5);
            return;
        }

        double p = (double) positiveFeedbackCount / n;
        double z = 1.96; // 95% confidence
        double denominator = 1 + z * z / n;
        double center = p + z * z / (2 * n);
        double spread = z * Math.sqrt((p * (1 - p) + z * z / (4 * n)) / n);

        double lowerBound = (center - spread) / denominator;
        this.effectivenessScore = BigDecimal.valueOf(Math.max(0, lowerBound));
    }

    /**
     * Check if eligible for promotion to GLOBAL
     */
    public void checkPromotionEligibility() {
        if (promotionStatus == PromotionStatus.DEPRECATED) {
            return;
        }

        if (effectivenessScore.compareTo(promotionThreshold) >= 0
                && adoptionCount >= minAdoptionCount) {
            promoteToGlobal();
        } else if (effectivenessScore.compareTo(promotionThreshold) >= 0) {
            this.promotionStatus = PromotionStatus.CANDIDATE;
        }
    }

    /**
     * Promote to global knowledge
     */
    public void promoteToGlobal() {
        this.promotionStatus = PromotionStatus.GLOBAL;
        this.promotedAt = LocalDateTime.now();
    }

    /**
     * Deprecate this knowledge
     */
    public void deprecate() {
        this.promotionStatus = PromotionStatus.DEPRECATED;
    }

    /**
     * Verify this knowledge manually
     */
    public void verify(String verifier) {
        this.isVerified = true;
        this.verifiedBy = verifier;
        this.verifiedAt = LocalDateTime.now();
    }

    /**
     * Check if this knowledge should be deprecated due to low effectiveness
     *
     * @param minEffectiveness Minimum effectiveness threshold
     * @param minFeedback Minimum feedback count
     * @return true if should be deprecated
     */
    public boolean shouldDeprecate(double minEffectiveness, int minFeedback) {
        int totalFeedback = positiveFeedbackCount + negativeFeedbackCount;
        return totalFeedback >= minFeedback
            && effectivenessScore.doubleValue() < minEffectiveness;
    }

    /**
     * Knowledge type enum
     */
    public enum KnowledgeType {
        KEYWORD,        // Single keyword
        EXPRESSION,     // Full expression/phrase
        PATTERN,        // Regex pattern
        INTENT_MAPPING  // Intent code mapping
    }

    /**
     * Promotion status enum
     */
    public enum PromotionStatus {
        LOCAL,      // Only in source factory
        CANDIDATE,  // Candidate for global promotion
        GLOBAL,     // Promoted to global knowledge
        DEPRECATED  // No longer recommended
    }
}

package com.cretas.aims.entity.learning;

import lombok.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Cross-Factory Knowledge Adoption Entity
 *
 * Records factory-level adoption and effectiveness of global knowledge.
 * Each factory can have its own effectiveness score for shared knowledge.
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-24
 */
@Entity
@Table(name = "cross_factory_knowledge_adoption",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_knowledge_factory",
        columnNames = {"knowledge_id", "factory_id"}
    ),
    indexes = {
        @Index(name = "idx_factory", columnList = "factory_id"),
        @Index(name = "idx_active", columnList = "is_active")
    })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CrossFactoryKnowledgeAdoption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "knowledge_id", nullable = false)
    private Long knowledgeId;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    // ==================== Adoption Info ====================

    @Column(name = "adopted_at")
    private LocalDateTime adoptedAt;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    // ==================== Local Effectiveness ====================

    @Column(name = "local_effectiveness_score", precision = 5, scale = 4)
    private BigDecimal localEffectivenessScore;

    @Column(name = "local_positive_count")
    @Builder.Default
    private Integer localPositiveCount = 0;

    @Column(name = "local_negative_count")
    @Builder.Default
    private Integer localNegativeCount = 0;

    // ==================== Audit Fields ====================

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "deactivated_at")
    private LocalDateTime deactivatedAt;

    // ==================== Relationship ====================

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "knowledge_id", insertable = false, updatable = false)
    private CrossFactoryKnowledge knowledge;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        adoptedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * Record positive feedback for this factory
     */
    public void recordPositiveFeedback() {
        this.localPositiveCount++;
        recalculateLocalEffectiveness();
    }

    /**
     * Record negative feedback for this factory
     */
    public void recordNegativeFeedback() {
        this.localNegativeCount++;
        recalculateLocalEffectiveness();
    }

    /**
     * Calculate Wilson Score for local effectiveness
     */
    public void recalculateLocalEffectiveness() {
        int n = localPositiveCount + localNegativeCount;
        if (n == 0) {
            this.localEffectivenessScore = null;
            return;
        }

        double p = (double) localPositiveCount / n;
        double z = 1.96; // 95% confidence
        double denominator = 1 + z * z / n;
        double center = p + z * z / (2 * n);
        double spread = z * Math.sqrt((p * (1 - p) + z * z / (4 * n)) / n);

        double lowerBound = (center - spread) / denominator;
        this.localEffectivenessScore = BigDecimal.valueOf(Math.max(0, lowerBound));
    }

    /**
     * Deactivate this adoption
     */
    public void deactivate() {
        this.isActive = false;
        this.deactivatedAt = LocalDateTime.now();
    }

    /**
     * Reactivate this adoption
     */
    public void reactivate() {
        this.isActive = true;
        this.deactivatedAt = null;
    }

    /**
     * Check if this adoption is performing well
     *
     * @param minEffectiveness Minimum effectiveness threshold
     * @return true if effective
     */
    public boolean isEffective(double minEffectiveness) {
        if (localEffectivenessScore == null) {
            return true; // Not enough data to evaluate
        }
        return localEffectivenessScore.doubleValue() >= minEffectiveness;
    }

    /**
     * Create a new adoption record
     *
     * @param knowledgeId Knowledge ID
     * @param factoryId Factory ID
     * @return New adoption record
     */
    public static CrossFactoryKnowledgeAdoption createNew(Long knowledgeId, String factoryId) {
        return CrossFactoryKnowledgeAdoption.builder()
            .knowledgeId(knowledgeId)
            .factoryId(factoryId)
            .isActive(true)
            .localPositiveCount(0)
            .localNegativeCount(0)
            .build();
    }
}

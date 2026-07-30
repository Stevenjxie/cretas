package com.cretas.aims.entity.learning;

import lombok.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Sample Cluster Entity
 *
 * Stores clustering results from low-confidence sample analysis.
 * Each cluster represents a group of similar inputs that may:
 * - Need new keywords
 * - Represent new expression patterns
 * - Indicate a missing intent
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-24
 */
@Entity
@Table(name = "sample_clusters",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_factory_cluster",
        columnNames = {"factory_id", "cluster_id"}
    ),
    indexes = {
        @Index(name = "idx_factory", columnList = "factory_id"),
        @Index(name = "idx_status", columnList = "analysis_status"),
        @Index(name = "idx_dominant_intent", columnList = "dominant_intent_code"),
        @Index(name = "idx_sample_count", columnList = "sample_count DESC")
    })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SampleCluster {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "cluster_id", nullable = false, length = 50)
    private String clusterId;

    // ==================== Cluster Info ====================

    @Column(name = "cluster_label", length = 200)
    private String clusterLabel;

    @Column(name = "cluster_centroid", columnDefinition = "bytea")
    private byte[] clusterCentroid;

    @Column(name = "representative_sample", columnDefinition = "TEXT")
    private String representativeSample;

    // ==================== Statistics ====================

    @Column(name = "sample_count")
    @Builder.Default
    private Integer sampleCount = 0;

    @Column(name = "avg_confidence", precision = 5, scale = 4)
    private BigDecimal avgConfidence;

    // ==================== Intent Distribution ====================

    @Column(name = "dominant_intent_code", length = 50)
    private String dominantIntentCode;

    @Column(name = "dominant_intent_ratio", precision = 5, scale = 4)
    private BigDecimal dominantIntentRatio;

    @Column(name = "intent_distribution", columnDefinition = "JSON")
    private String intentDistribution;

    // ==================== Keyword Analysis ====================

    @Column(name = "common_keywords", columnDefinition = "JSON")
    private String commonKeywords;

    @Column(name = "suggested_keywords", columnDefinition = "JSON")
    private String suggestedKeywords;

    // ==================== Status ====================

    @Column(name = "analysis_status", length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private AnalysisStatus analysisStatus = AnalysisStatus.PENDING;

    @Column(name = "action_taken", length = 50)
    private String actionTaken;

    @Column(name = "action_details", columnDefinition = "TEXT")
    private String actionDetails;

    // ==================== Audit Fields ====================

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "analyzed_at")
    private LocalDateTime analyzedAt;

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
     * Mark as analyzed
     */
    public void markAnalyzed() {
        this.analysisStatus = AnalysisStatus.ANALYZED;
        this.analyzedAt = LocalDateTime.now();
    }

    /**
     * Record action taken
     */
    public void recordAction(String action, String details) {
        this.analysisStatus = AnalysisStatus.ACTION_TAKEN;
        this.actionTaken = action;
        this.actionDetails = details;
    }

    /**
     * Check if cluster is large enough to warrant action
     */
    public boolean isSignificant(int minSamples) {
        return sampleCount >= minSamples;
    }

    /**
     * Check if cluster has a clear dominant intent
     */
    public boolean hasClearDominantIntent(double minRatio) {
        if (dominantIntentRatio == null) {
            return false;
        }
        return dominantIntentRatio.doubleValue() >= minRatio;
    }

    /**
     * Check if cluster represents ambiguous cases (no clear intent)
     */
    public boolean isAmbiguous(double maxRatio) {
        if (dominantIntentRatio == null) {
            return true;
        }
        return dominantIntentRatio.doubleValue() < maxRatio;
    }

    /**
     * Get cluster coherence score (higher = more coherent)
     * Based on dominant intent ratio and average confidence
     */
    public BigDecimal getCoherenceScore() {
        if (dominantIntentRatio == null || avgConfidence == null) {
            return BigDecimal.ZERO;
        }
        // Coherence = dominant_ratio * avg_confidence
        return dominantIntentRatio.multiply(avgConfidence);
    }

    /**
     * Analysis status enum
     */
    public enum AnalysisStatus {
        PENDING,        // Waiting for analysis
        ANALYZED,       // Analyzed but no action taken
        ACTION_TAKEN    // Action has been taken
    }

    /**
     * Create a new cluster
     */
    public static SampleCluster createNew(
            String factoryId,
            String clusterId,
            String representativeSample,
            int sampleCount) {
        return SampleCluster.builder()
            .factoryId(factoryId)
            .clusterId(clusterId)
            .representativeSample(representativeSample)
            .sampleCount(sampleCount)
            .analysisStatus(AnalysisStatus.PENDING)
            .build();
    }
}

package com.cretas.aims.entity.learning;

import lombok.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Active Learning Sample Entity
 *
 * Stores low-confidence samples (confidence < 0.7) for clustering analysis
 * and active learning. These samples are collected to:
 * - Identify patterns in unclear inputs
 * - Generate learning suggestions (new keywords, expressions)
 * - Improve intent recognition accuracy
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-24
 */
@Entity
@Table(name = "active_learning_samples",
    indexes = {
        @Index(name = "idx_factory_confidence", columnList = "factory_id, confidence_score"),
        @Index(name = "idx_learning_status", columnList = "learning_status"),
        @Index(name = "idx_cluster_id", columnList = "cluster_id"),
        @Index(name = "idx_created_at", columnList = "created_at"),
        @Index(name = "idx_source_record", columnList = "source_record_id")
    })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ActiveLearningSample {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "session_id", length = 100)
    private String sessionId;

    // ==================== User Input ====================

    @Column(name = "user_input", columnDefinition = "TEXT", nullable = false)
    private String userInput;

    @Column(name = "normalized_input", columnDefinition = "TEXT")
    private String normalizedInput;

    @Column(name = "input_embedding", columnDefinition = "bytea")
    private byte[] inputEmbedding;

    // ==================== Match Result ====================

    @Column(name = "matched_intent_code", length = 50)
    private String matchedIntentCode;

    @Column(name = "confidence_score", nullable = false, precision = 5, scale = 4)
    private BigDecimal confidenceScore;

    @Column(name = "match_method", length = 20)
    private String matchMethod;

    @Column(name = "top_candidates", columnDefinition = "JSON")
    private String topCandidates;

    @Column(name = "matched_keywords", columnDefinition = "JSON")
    private String matchedKeywords;

    // ==================== Clustering Info ====================

    @Column(name = "cluster_id", length = 50)
    private String clusterId;

    @Column(name = "cluster_label", length = 200)
    private String clusterLabel;

    @Column(name = "cluster_centroid_distance", precision = 10, scale = 6)
    private BigDecimal clusterCentroidDistance;

    // ==================== Learning Status ====================

    @Column(name = "learning_status", length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private LearningStatus learningStatus = LearningStatus.PENDING;

    @Column(name = "suggestion_id")
    private Long suggestionId;

    // ==================== User Feedback ====================

    @Column(name = "user_confirmed")
    private Boolean userConfirmed;

    @Column(name = "user_selected_intent", length = 50)
    private String userSelectedIntent;

    @Column(name = "user_feedback", columnDefinition = "TEXT")
    private String userFeedback;

    // ==================== Audit Fields ====================

    @Column(name = "source_record_id", length = 36)
    private String sourceRecordId;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "analyzed_at")
    private LocalDateTime analyzedAt;

    @Column(name = "learned_at")
    private LocalDateTime learnedAt;

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
     * Mark sample as analyzed
     */
    public void markAnalyzed(String clusterId, String clusterLabel) {
        this.clusterId = clusterId;
        this.clusterLabel = clusterLabel;
        this.learningStatus = LearningStatus.ANALYZED;
        this.analyzedAt = LocalDateTime.now();
    }

    /**
     * Mark sample as learned
     */
    public void markLearned(Long suggestionId) {
        this.suggestionId = suggestionId;
        this.learningStatus = LearningStatus.LEARNED;
        this.learnedAt = LocalDateTime.now();
    }

    /**
     * Mark sample as ignored
     */
    public void markIgnored() {
        this.learningStatus = LearningStatus.IGNORED;
    }

    /**
     * Learning status enum
     */
    public enum LearningStatus {
        PENDING,    // Waiting for analysis
        ANALYZED,   // Analyzed but not learned yet
        LEARNED,    // Learning suggestion generated
        IGNORED     // Ignored (not useful for learning)
    }
}

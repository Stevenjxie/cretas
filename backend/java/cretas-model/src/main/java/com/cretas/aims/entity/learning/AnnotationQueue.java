package com.cretas.aims.entity.learning;

import lombok.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Annotation Queue Entity
 *
 * Stores samples that need human annotation for:
 * - Building ground truth dataset
 * - Resolving ambiguous cases
 * - Validating system predictions
 * - Training data quality improvement
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-24
 */
@Entity
@Table(name = "annotation_queue",
    indexes = {
        @Index(name = "idx_factory", columnList = "factory_id"),
        @Index(name = "idx_status", columnList = "annotation_status"),
        @Index(name = "idx_priority", columnList = "priority DESC"),
        @Index(name = "idx_assigned", columnList = "assigned_to, annotation_status"),
        @Index(name = "idx_sample", columnList = "sample_id")
    })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AnnotationQueue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    // ==================== Sample Info ====================

    @Column(name = "sample_id")
    private Long sampleId;

    @Column(name = "user_input", columnDefinition = "TEXT", nullable = false)
    private String userInput;

    @Column(name = "normalized_input", columnDefinition = "TEXT")
    private String normalizedInput;

    // ==================== System Prediction ====================

    @Column(name = "predicted_intent_code", length = 50)
    private String predictedIntentCode;

    @Column(name = "predicted_confidence", precision = 5, scale = 4)
    private BigDecimal predictedConfidence;

    @Column(name = "alternative_intents", columnDefinition = "JSON")
    private String alternativeIntents;

    // ==================== Annotation Status ====================

    @Column(name = "annotation_status", length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private AnnotationStatus annotationStatus = AnnotationStatus.PENDING;

    @Column(name = "priority")
    @Builder.Default
    private Integer priority = 5;

    @Column(name = "difficulty_level", length = 20)
    @Enumerated(EnumType.STRING)
    private DifficultyLevel difficultyLevel;

    // ==================== Annotation Result ====================

    @Column(name = "annotated_intent_code", length = 50)
    private String annotatedIntentCode;

    @Column(name = "annotated_by", length = 50)
    private String annotatedBy;

    @Column(name = "annotated_at")
    private LocalDateTime annotatedAt;

    @Column(name = "annotation_notes", columnDefinition = "TEXT")
    private String annotationNotes;

    // ==================== Dispute Handling ====================

    @Column(name = "is_disputed")
    @Builder.Default
    private Boolean isDisputed = false;

    @Column(name = "dispute_reason", columnDefinition = "TEXT")
    private String disputeReason;

    @Column(name = "resolved_by", length = 50)
    private String resolvedBy;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    // ==================== Quality Control ====================

    @Column(name = "verification_status", length = 20)
    @Enumerated(EnumType.STRING)
    private VerificationStatus verificationStatus;

    @Column(name = "verified_by", length = 50)
    private String verifiedBy;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    // ==================== Assignment Info ====================

    @Column(name = "assigned_to", length = 50)
    private String assignedTo;

    @Column(name = "assigned_at")
    private LocalDateTime assignedAt;

    @Column(name = "due_at")
    private LocalDateTime dueAt;

    // ==================== Audit Fields ====================

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ==================== Relationship ====================

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sample_id", insertable = false, updatable = false)
    private ActiveLearningSample sample;

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
     * Assign to an annotator
     */
    public void assignTo(String annotator, LocalDateTime dueDate) {
        this.assignedTo = annotator;
        this.assignedAt = LocalDateTime.now();
        this.dueAt = dueDate;
        this.annotationStatus = AnnotationStatus.ASSIGNED;
    }

    /**
     * Complete annotation
     */
    public void complete(String intentCode, String annotator, String notes) {
        this.annotatedIntentCode = intentCode;
        this.annotatedBy = annotator;
        this.annotatedAt = LocalDateTime.now();
        this.annotationNotes = notes;
        this.annotationStatus = AnnotationStatus.COMPLETED;
    }

    /**
     * Skip this annotation
     */
    public void skip(String reason) {
        this.annotationStatus = AnnotationStatus.SKIPPED;
        this.annotationNotes = reason;
    }

    /**
     * Raise a dispute
     */
    public void dispute(String reason) {
        this.isDisputed = true;
        this.disputeReason = reason;
        this.annotationStatus = AnnotationStatus.DISPUTED;
    }

    /**
     * Resolve dispute
     */
    public void resolveDispute(String resolver, String finalIntent) {
        this.resolvedBy = resolver;
        this.resolvedAt = LocalDateTime.now();
        this.annotatedIntentCode = finalIntent;
        this.annotationStatus = AnnotationStatus.COMPLETED;
        this.isDisputed = false;
    }

    /**
     * Verify annotation
     */
    public void verify(String verifier, boolean approved) {
        this.verifiedBy = verifier;
        this.verifiedAt = LocalDateTime.now();
        this.verificationStatus = approved ? VerificationStatus.VERIFIED : VerificationStatus.REJECTED;
    }

    /**
     * Check if annotation is overdue
     */
    public boolean isOverdue() {
        if (dueAt == null || annotationStatus == AnnotationStatus.COMPLETED) {
            return false;
        }
        return LocalDateTime.now().isAfter(dueAt);
    }

    /**
     * Check if annotation matches system prediction
     */
    public boolean matchesPrediction() {
        if (annotatedIntentCode == null || predictedIntentCode == null) {
            return false;
        }
        return annotatedIntentCode.equals(predictedIntentCode);
    }

    /**
     * Annotation status enum
     */
    public enum AnnotationStatus {
        PENDING,    // Waiting for assignment
        ASSIGNED,   // Assigned to annotator
        COMPLETED,  // Annotation completed
        SKIPPED,    // Skipped (not suitable for annotation)
        DISPUTED    // Under dispute
    }

    /**
     * Difficulty level enum
     */
    public enum DifficultyLevel {
        EASY,       // Clear intent, simple annotation
        MEDIUM,     // Some ambiguity
        HARD        // Complex, requires expertise
    }

    /**
     * Verification status enum
     */
    public enum VerificationStatus {
        PENDING,    // Waiting for verification
        VERIFIED,   // Verified as correct
        REJECTED    // Rejected, needs re-annotation
    }

    /**
     * Create from active learning sample
     */
    public static AnnotationQueue fromSample(ActiveLearningSample sample) {
        return AnnotationQueue.builder()
            .factoryId(sample.getFactoryId())
            .sampleId(sample.getId())
            .userInput(sample.getUserInput())
            .normalizedInput(sample.getNormalizedInput())
            .predictedIntentCode(sample.getMatchedIntentCode())
            .predictedConfidence(sample.getConfidenceScore())
            .alternativeIntents(sample.getTopCandidates())
            .annotationStatus(AnnotationStatus.PENDING)
            .priority(calculatePriority(sample))
            .difficultyLevel(calculateDifficulty(sample))
            .build();
    }

    /**
     * Calculate priority based on sample characteristics
     */
    private static int calculatePriority(ActiveLearningSample sample) {
        int priority = 5;

        // Lower confidence = higher priority
        if (sample.getConfidenceScore() != null) {
            double conf = sample.getConfidenceScore().doubleValue();
            if (conf < 0.3) priority = 8;
            else if (conf < 0.5) priority = 7;
            else if (conf < 0.6) priority = 6;
        }

        // User rejected = higher priority
        if (Boolean.FALSE.equals(sample.getUserConfirmed())) {
            priority = Math.min(10, priority + 2);
        }

        return priority;
    }

    /**
     * Calculate difficulty based on sample characteristics
     */
    private static DifficultyLevel calculateDifficulty(ActiveLearningSample sample) {
        if (sample.getConfidenceScore() == null) {
            return DifficultyLevel.MEDIUM;
        }

        double conf = sample.getConfidenceScore().doubleValue();
        if (conf < 0.3) return DifficultyLevel.HARD;
        if (conf < 0.5) return DifficultyLevel.MEDIUM;
        return DifficultyLevel.EASY;
    }
}

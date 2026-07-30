package com.cretas.aims.entity.learning;

import lombok.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Intent Transition Matrix Entity
 *
 * Records transition frequencies between intents for Markov chain modeling.
 * Used for:
 * - Confidence score calibration
 * - Intent prediction based on conversation context
 * - Identifying common intent sequences
 *
 * Uses Laplace smoothing for probability calculation:
 * P(to|from) = (transition_count + 1) / (total_from_count + V)
 * where V = number of unique intents
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-24
 */
@Entity
@Table(name = "intent_transition_matrix",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_factory_intents_window",
        columnNames = {"factory_id", "from_intent_code", "to_intent_code", "window_start"}
    ),
    indexes = {
        @Index(name = "idx_from_intent", columnList = "factory_id, from_intent_code"),
        @Index(name = "idx_to_intent", columnList = "factory_id, to_intent_code"),
        @Index(name = "idx_probability", columnList = "transition_probability DESC")
    })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IntentTransitionMatrix {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    // ==================== Transition Info ====================

    @Column(name = "from_intent_code", nullable = false, length = 50)
    private String fromIntentCode;

    @Column(name = "to_intent_code", nullable = false, length = 50)
    private String toIntentCode;

    // ==================== Statistics ====================

    @Column(name = "transition_count")
    @Builder.Default
    private Integer transitionCount = 0;

    @Column(name = "total_from_count")
    @Builder.Default
    private Integer totalFromCount = 0;

    @Column(name = "transition_probability", precision = 7, scale = 6)
    @Builder.Default
    private BigDecimal transitionProbability = BigDecimal.ZERO;

    // ==================== Time Window ====================

    @Column(name = "window_start", nullable = false)
    private LocalDate windowStart;

    @Column(name = "window_end", nullable = false)
    private LocalDate windowEnd;

    // ==================== Audit Fields ====================

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
     * Increment transition count
     */
    public void incrementTransition() {
        this.transitionCount++;
    }

    /**
     * Update total count for source intent
     */
    public void updateTotalFromCount(int totalCount) {
        this.totalFromCount = totalCount;
    }

    /**
     * Calculate Laplace-smoothed transition probability
     *
     * @param vocabularySize Number of unique intents (V in Laplace smoothing)
     */
    public void calculateProbability(int vocabularySize) {
        if (totalFromCount == 0) {
            // Uniform distribution when no data
            this.transitionProbability = BigDecimal.ONE.divide(
                BigDecimal.valueOf(vocabularySize), 6, RoundingMode.HALF_UP);
            return;
        }

        // Laplace smoothing: (count + 1) / (total + V)
        double numerator = transitionCount + 1.0;
        double denominator = totalFromCount + vocabularySize;
        this.transitionProbability = BigDecimal.valueOf(numerator / denominator)
            .setScale(6, RoundingMode.HALF_UP);
    }

    /**
     * Get log probability for numerical stability
     *
     * @return log(P(to|from))
     */
    public double getLogProbability() {
        if (transitionProbability.compareTo(BigDecimal.ZERO) <= 0) {
            return Double.NEGATIVE_INFINITY;
        }
        return Math.log(transitionProbability.doubleValue());
    }

    /**
     * Check if this is a strong transition (high probability)
     *
     * @param threshold Minimum probability threshold
     * @return true if probability >= threshold
     */
    public boolean isStrongTransition(double threshold) {
        return transitionProbability.doubleValue() >= threshold;
    }

    /**
     * Create a new transition record for a time window
     *
     * @param factoryId Factory ID
     * @param fromIntent Source intent code
     * @param toIntent Target intent code
     * @param windowStart Window start date
     * @param windowEnd Window end date
     * @return New IntentTransitionMatrix instance
     */
    public static IntentTransitionMatrix createNew(
            String factoryId,
            String fromIntent,
            String toIntent,
            LocalDate windowStart,
            LocalDate windowEnd) {
        return IntentTransitionMatrix.builder()
            .factoryId(factoryId)
            .fromIntentCode(fromIntent)
            .toIntentCode(toIntent)
            .transitionCount(1)
            .totalFromCount(0)
            .transitionProbability(BigDecimal.ZERO)
            .windowStart(windowStart)
            .windowEnd(windowEnd)
            .build();
    }
}

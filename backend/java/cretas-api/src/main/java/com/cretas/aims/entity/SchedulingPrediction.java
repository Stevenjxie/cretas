package com.cretas.aims.entity;

import lombok.Data;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 预测记录实体
 * 记录 AI 对效率、时长、完成概率等的预测
 */
@Data
@Entity
@Table(name = "scheduling_predictions")
public class SchedulingPrediction {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "schedule_id", length = 36, nullable = false)
    private String scheduleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "prediction_type", length = 20, nullable = false)
    private PredictionType predictionType;

    @Column(name = "predicted_value", precision = 10, scale = 4)
    private BigDecimal predictedValue;

    @Column(name = "confidence_lower", precision = 10, scale = 4)
    private BigDecimal confidenceLower;

    @Column(name = "confidence_upper", precision = 10, scale = 4)
    private BigDecimal confidenceUpper;

    @Column(name = "model_version", length = 20)
    private String modelVersion;

    @Column(name = "features_json", columnDefinition = "TEXT")
    private String featuresJson;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public enum PredictionType {
        efficiency, duration, completion_prob, quality
    }

    @PrePersist
    public void prePersist() {
        if (this.id == null) {
            this.id = java.util.UUID.randomUUID().toString();
        }
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }
}

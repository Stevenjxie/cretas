package com.cretas.aims.entity;

import lombok.Data;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 权重历史记录实体
 * 用于记录预测模型权重调整历史
 */
@Data
@Entity
@Table(name = "aps_weight_history")
public class WeightHistory {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "factory_id", length = 50, nullable = false)
    private String factoryId;

    @Column(name = "adjusted_at", nullable = false)
    private LocalDateTime adjustedAt;

    @Column(name = "weights_before", columnDefinition = "JSON")
    private String weightsBefore;

    @Column(name = "weights_after", columnDefinition = "JSON")
    private String weightsAfter;

    @Column(name = "trigger_reason", length = 200)
    private String triggerReason;

    @Column(name = "performance_metrics", columnDefinition = "JSON")
    private String performanceMetrics;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (this.id == null) {
            this.id = java.util.UUID.randomUUID().toString();
        }
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}

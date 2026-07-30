package com.cretas.aims.entity;

import lombok.Data;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 预测模型权重实体
 * 用于存储各工厂的预测模型特征权重
 */
@Data
@Entity
@Table(name = "aps_prediction_model_weights")
public class PredictionModelWeight {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "factory_id", length = 50, nullable = false)
    private String factoryId;

    @Column(name = "feature_name", length = 50, nullable = false)
    private String featureName;

    @Column(name = "feature_weight", precision = 8, scale = 6, nullable = false)
    private BigDecimal featureWeight;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (this.id == null) {
            this.id = java.util.UUID.randomUUID().toString();
        }
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}

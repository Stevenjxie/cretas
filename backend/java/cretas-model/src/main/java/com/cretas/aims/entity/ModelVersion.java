package com.cretas.aims.entity;

import lombok.*;
import org.hibernate.annotations.GenericGenerator;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * ML模型版本实体
 * 管理训练好的模型版本和元数据
 */
@Entity
@Table(name = "ml_model_versions",
       indexes = {
           @Index(name = "idx_factory_type", columnList = "factory_id, model_type"),
           @Index(name = "idx_factory_active", columnList = "factory_id, is_active")
       },
       uniqueConstraints = {
           @UniqueConstraint(name = "uk_factory_type_active",
                           columnNames = {"factory_id", "model_type", "is_active"})
       })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelVersion {

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(length = 36)
    private String id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "model_type", nullable = false, length = 50)
    private String modelType;  // efficiency, duration, quality

    @Column(name = "version", nullable = false, length = 20)
    private String version;  // 时间戳格式: 20251228_120000

    // ==================== 训练信息 ====================

    @Column(name = "training_data_count")
    private Integer trainingDataCount;

    @Column(name = "rmse", precision = 10, scale = 4)
    private BigDecimal rmse;  // 均方根误差

    @Column(name = "r2_score", precision = 5, scale = 4)
    private BigDecimal r2Score;  // R²决定系数

    @Column(name = "mae", precision = 10, scale = 4)
    private BigDecimal mae;  // 平均绝对误差

    // ==================== 模型文件 ====================

    @Column(name = "model_path", length = 255)
    private String modelPath;  // 模型文件路径

    @Column(name = "features_json", columnDefinition = "TEXT")
    private String featuresJson;  // 使用的特征列表(JSON)

    // ==================== 状态 ====================

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "status", length = 20)
    @Builder.Default
    private String status = "trained";  // training, trained, failed, deprecated

    // ==================== 时间戳 ====================

    @Column(name = "trained_at")
    private LocalDateTime trainedAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.trainedAt == null) {
            this.trainedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // ==================== 便捷方法 ====================

    /**
     * 判断模型是否可用于预测
     */
    public boolean isUsable() {
        return Boolean.TRUE.equals(this.isActive)
            && "trained".equals(this.status)
            && this.modelPath != null;
    }

    /**
     * 判断模型质量是否足够好
     * R² > 0.6 认为模型质量可接受
     */
    public boolean hasGoodQuality() {
        return this.r2Score != null
            && this.r2Score.compareTo(new BigDecimal("0.6")) >= 0;
    }

    /**
     * 获取模型置信度 (基于R²分数)
     */
    public double getConfidence() {
        if (this.r2Score == null) {
            return 0.5;
        }
        double r2 = this.r2Score.doubleValue();
        if (r2 >= 0.8) {
            return 0.9;
        } else if (r2 >= 0.6) {
            return 0.7;
        } else {
            return 0.5;
        }
    }
}

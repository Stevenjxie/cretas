package com.cretas.aims.entity;

import lombok.*;
import org.hibernate.annotations.GenericGenerator;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * ML训练数据记录实体
 * 存储每个生产批次完成后的特征数据，用于训练效率预测模型
 */
@Entity
@Table(name = "scheduling_training_data",
       indexes = {
           @Index(name = "idx_factory_id", columnList = "factory_id"),
           @Index(name = "idx_batch_id", columnList = "batch_id"),
           @Index(name = "idx_factory_recorded", columnList = "factory_id, recorded_at")
       })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainingDataRecord {

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(length = 36)
    private String id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    // ==================== 时间特征 ====================

    @Column(name = "hour_of_day")
    private Integer hourOfDay;

    @Column(name = "day_of_week")
    private Integer dayOfWeek;

    @Column(name = "is_overtime")
    private Boolean isOvertime;

    // ==================== 工人特征 ====================

    @Column(name = "worker_count")
    private Integer workerCount;

    @Column(name = "avg_worker_experience_days")
    private Integer avgWorkerExperienceDays;

    @Column(name = "avg_skill_level", precision = 3, scale = 2)
    private BigDecimal avgSkillLevel;

    @Column(name = "temporary_worker_ratio", precision = 5, scale = 4)
    private BigDecimal temporaryWorkerRatio;

    // ==================== 产品特征 ====================

    @Column(name = "product_complexity")
    private Integer productComplexity;

    @Column(name = "product_type", length = 50)
    private String productType;

    // ==================== 设备特征 ====================

    @Column(name = "equipment_age_days")
    private Integer equipmentAgeDays;

    @Column(name = "equipment_utilization", precision = 5, scale = 4)
    private BigDecimal equipmentUtilization;

    // ==================== 标签数据 (实际结果) ====================

    @Column(name = "actual_efficiency", precision = 10, scale = 2)
    private BigDecimal actualEfficiency;

    @Column(name = "actual_duration_hours", precision = 10, scale = 2)
    private BigDecimal actualDurationHours;

    @Column(name = "quality_pass_rate", precision = 5, scale = 4)
    private BigDecimal qualityPassRate;

    // ==================== 元数据 ====================

    @Column(name = "recorded_at")
    private LocalDateTime recordedAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.recordedAt == null) {
            this.recordedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}

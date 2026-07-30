package com.cretas.aims.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 告警阈值配置实体
 * 定义各工厂的指标告警规则（静态阈值或动态偏差百分比）
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-02-09
 */
@Data
@Entity
@Table(name = "alert_thresholds",
       uniqueConstraints = {
           @UniqueConstraint(columnNames = {"factory_id", "metric_name", "alert_type"})
       },
       indexes = {
           @Index(name = "idx_threshold_factory", columnList = "factory_id"),
           @Index(name = "idx_threshold_enabled", columnList = "enabled")
       }
)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertThreshold implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * 工厂ID
     */
    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    /**
     * 指标名称
     */
    @Column(name = "metric_name", nullable = false, length = 100)
    private String metricName;

    /**
     * 告警类型: YIELD_DROP, COST_SPIKE, OEE_LOW, QUALITY_FAIL
     */
    @Column(name = "alert_type", nullable = false, length = 50)
    private String alertType;

    /**
     * 告警级别: CRITICAL, WARNING, INFO
     */
    @Column(name = "level", nullable = false, length = 20)
    private String level;

    /**
     * 比较方式: LESS_THAN, GREATER_THAN, DEVIATION_BELOW, DEVIATION_ABOVE
     */
    @Column(name = "comparison", nullable = false, length = 20)
    private String comparison;

    /**
     * 静态阈值
     */
    @Column(name = "static_threshold")
    private Double staticThreshold;

    /**
     * 偏差百分比（动态阈值）
     */
    @Column(name = "deviation_percent")
    private Double deviationPercent;

    /**
     * 基线计算天数
     */
    @Builder.Default
    @Column(name = "baseline_days")
    private Integer baselineDays = 30;

    /**
     * 是否启用
     */
    @Builder.Default
    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    /**
     * 描述
     */
    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

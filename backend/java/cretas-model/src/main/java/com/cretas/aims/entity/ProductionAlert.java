package com.cretas.aims.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 生产告警实体
 * 记录生产过程中的异常指标告警（良率下降、成本飙升、OEE过低、质量不达标等）
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-02-09
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "production_alerts",
       indexes = {
           @Index(name = "idx_alert_factory", columnList = "factory_id"),
           @Index(name = "idx_alert_status", columnList = "status"),
           @Index(name = "idx_alert_level", columnList = "level"),
           @Index(name = "idx_alert_type", columnList = "alert_type"),
           @Index(name = "idx_alert_created", columnList = "created_at")
       }
)
@SQLDelete(sql = "UPDATE production_alerts SET deleted_at = NOW() WHERE id = ?")
@Where(clause = "deleted_at IS NULL")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductionAlert extends BaseEntity {

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
     * 告警状态: ACTIVE, ACKNOWLEDGED, RESOLVED, VERIFIED
     */
    @Builder.Default
    @Column(name = "status", nullable = false, length = 20)
    private String status = "ACTIVE";

    /**
     * 指标名称
     */
    @Column(name = "metric_name", nullable = false, length = 100)
    private String metricName;

    /**
     * 当前值
     */
    @Column(name = "current_value")
    private Double currentValue;

    /**
     * 基线值
     */
    @Column(name = "baseline_value")
    private Double baselineValue;

    /**
     * 阈值
     */
    @Column(name = "threshold_value")
    private Double thresholdValue;

    /**
     * 偏差百分比
     */
    @Column(name = "deviation_percent")
    private Double deviationPercent;

    /**
     * 关联批次ID
     */
    @Column(name = "batch_id")
    private Long batchId;

    /**
     * 关联设备ID
     */
    @Column(name = "equipment_id", length = 100)
    private String equipmentId;

    /**
     * 产品名称
     */
    @Column(name = "product_name", length = 255)
    private String productName;

    /**
     * 告警描述
     */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /**
     * AI分析结果
     */
    @Column(name = "ai_analysis", columnDefinition = "TEXT")
    private String aiAnalysis;

    /**
     * 解决备注
     */
    @Column(name = "resolution_notes", columnDefinition = "TEXT")
    private String resolutionNotes;

    /**
     * 确认人ID
     */
    @Column(name = "acknowledged_by")
    private Long acknowledgedBy;

    /**
     * 确认时间
     */
    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;

    /**
     * 解决人ID
     */
    @Column(name = "resolved_by")
    private Long resolvedBy;

    /**
     * 解决时间
     */
    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    /**
     * 验证时间
     */
    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    /**
     * 是否自动验证
     */
    @Builder.Default
    @Column(name = "auto_verified")
    private Boolean autoVerified = false;
}

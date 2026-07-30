package com.cretas.aims.entity;

import com.cretas.aims.entity.enums.ReworkStatus;
import com.cretas.aims.entity.enums.ReworkType;
import lombok.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.hibernate.annotations.Where;

/**
 * 返工记录实体类
 * 用于追踪不合格品的返工处理全流程
 *
 * @author Cretas Team
 * @version 2.0.0
 * @since 2025-11-05
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"qualityInspection", "productionBatch", "materialBatch", "supervisor"})
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "rework_records",
       indexes = {
           @Index(name = "idx_rework_factory", columnList = "factory_id"),
           @Index(name = "idx_rework_status", columnList = "status"),
           @Index(name = "idx_rework_quality", columnList = "quality_inspection_id"),
           @Index(name = "idx_rework_batch", columnList = "production_batch_id"),
           @Index(name = "idx_rework_material", columnList = "material_batch_id"),
           @Index(name = "idx_rework_date", columnList = "start_time")
       }
)
@Where(clause = "deleted_at IS NULL")
public class ReworkRecord extends BaseEntity {
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
     * 质检记录ID（关联到触发返工的质检记录）
     */
    @Column(name = "quality_inspection_id", length = 191)
    private String qualityInspectionId;

    /**
     * 生产批次ID（针对成品返工）
     */
    @Column(name = "production_batch_id")
    private String productionBatchId;

    /**
     * 原材料批次ID（针对原料返工）
     */
    @Column(name = "material_batch_id", length = 191)
    private String materialBatchId;

    /**
     * 返工数量
     */
    @Column(name = "rework_quantity", nullable = false, precision = 12, scale = 2)
    private BigDecimal reworkQuantity;

    /**
     * 返工类型
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "rework_type", nullable = false, length = 30)
    private ReworkType reworkType;

    /**
     * 返工原因（详细描述）
     */
    @Column(name = "rework_reason", columnDefinition = "TEXT")
    private String reworkReason;

    /**
     * 返工方法/步骤
     */
    @Column(name = "rework_method", columnDefinition = "TEXT")
    private String reworkMethod;

    /**
     * 开始时间
     */
    @Column(name = "start_time")
    private LocalDateTime startTime;

    /**
     * 结束时间
     */
    @Column(name = "end_time")
    private LocalDateTime endTime;

    /**
     * 返工状态
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private ReworkStatus status = ReworkStatus.PENDING;

    /**
     * 成功数量（返工后合格的数量）
     */
    @Column(name = "success_quantity", precision = 12, scale = 2)
    private BigDecimal successQuantity;

    /**
     * 失败数量（返工后仍不合格的数量）
     */
    @Column(name = "failed_quantity", precision = 12, scale = 2)
    private BigDecimal failedQuantity;

    /**
     * 挽救数量（通过返工挽救的数量）
     */
    @Column(name = "salvage_quantity", precision = 12, scale = 2)
    private BigDecimal salvageQuantity;

    /**
     * 返工成本
     */
    @Column(name = "rework_cost", precision = 12, scale = 2)
    private BigDecimal reworkCost;

    /**
     * 人工时长（分钟）
     */
    @Column(name = "labor_duration_minutes")
    private Integer laborDurationMinutes;

    /**
     * 负责人ID
     */
    @Column(name = "supervisor_id")
    private Long supervisorId;

    /**
     * 负责人姓名
     */
    @Column(name = "supervisor_name", length = 100)
    private String supervisorName;

    /**
     * 备注
     */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    // ===================================================================
    // 关联关系
    // ===================================================================

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quality_inspection_id", referencedColumnName = "id", insertable = false, updatable = false)
    private QualityInspection qualityInspection;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "production_batch_id", referencedColumnName = "id", insertable = false, updatable = false)
    private ProductionBatch productionBatch;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_batch_id", referencedColumnName = "id", insertable = false, updatable = false)
    private MaterialBatch materialBatch;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supervisor_id", referencedColumnName = "id", insertable = false, updatable = false)
    private User supervisor;

    // ===================================================================
    // 业务方法
    // ===================================================================

    /**
     * 开始返工
     */
    public void startRework() {
        this.status = ReworkStatus.IN_PROGRESS;
        this.startTime = LocalDateTime.now();
    }

    /**
     * 完成返工
     */
    public void completeRework(BigDecimal successQty, BigDecimal failedQty) {
        this.status = ReworkStatus.COMPLETED;
        this.endTime = LocalDateTime.now();
        this.successQuantity = successQty;
        this.failedQuantity = failedQty;
        this.salvageQuantity = successQty;

        // 计算工时
        if (startTime != null && endTime != null) {
            this.laborDurationMinutes = (int) java.time.Duration.between(startTime, endTime).toMinutes();
        }
    }

    /**
     * 返工失败
     */
    public void failRework(String reason) {
        this.status = ReworkStatus.FAILED;
        this.endTime = LocalDateTime.now();
        this.notes = (this.notes != null ? this.notes + "\n" : "") + "返工失败原因: " + reason;
    }

    /**
     * 取消返工
     */
    public void cancelRework(String reason) {
        this.status = ReworkStatus.CANCELLED;
        this.endTime = LocalDateTime.now();
        this.notes = (this.notes != null ? this.notes + "\n" : "") + "取消原因: " + reason;
    }

    /**
     * 计算返工成功率
     */
    @Transient
    public BigDecimal getSuccessRate() {
        if (reworkQuantity == null || reworkQuantity.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        if (successQuantity == null) {
            return BigDecimal.ZERO;
        }
        return successQuantity.multiply(BigDecimal.valueOf(100))
                .divide(reworkQuantity, 2, java.math.RoundingMode.HALF_UP);
    }

    @PrePersist
    protected void onCreate() {
        super.onCreate();
        if (status == null) {
            status = ReworkStatus.PENDING;
        }
    }
}

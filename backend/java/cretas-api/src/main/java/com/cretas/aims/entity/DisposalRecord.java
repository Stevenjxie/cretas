package com.cretas.aims.entity;

import com.cretas.aims.security.PriceSensitive;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.hibernate.annotations.Where;

/**
 * 报废记录实体类
 * 用于追踪不合格品的报废处理
 *
 * @author Cretas Team
 * @version 2.0.0
 * @since 2025-11-05
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"qualityInspection", "reworkRecord", "productionBatch", "materialBatch", "approver"})
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "disposal_records",
       indexes = {
           @Index(name = "idx_disposal_factory", columnList = "factory_id"),
           @Index(name = "idx_disposal_type", columnList = "disposal_type"),
           @Index(name = "idx_disposal_date", columnList = "disposal_date"),
           @Index(name = "idx_disposal_quality", columnList = "quality_inspection_id"),
           @Index(name = "idx_disposal_rework", columnList = "rework_record_id")
       }
)
@Where(clause = "deleted_at IS NULL")
public class DisposalRecord extends BaseEntity {
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
     * 质检记录ID（可选，如果是质检后直接报废）
     */
    @Column(name = "quality_inspection_id", length = 191)
    private String qualityInspectionId;

    /**
     * 返工记录ID（可选，如果是返工失败后报废）
     */
    @Column(name = "rework_record_id")
    private Long reworkRecordId;

    /**
     * 生产批次ID（针对成品报废）
     */
    @Column(name = "production_batch_id")
    private String productionBatchId;

    /**
     * 原材料批次ID（针对原料报废）
     */
    @Column(name = "material_batch_id", length = 191)
    private String materialBatchId;

    /**
     * 报废数量
     */
    @Column(name = "disposal_quantity", nullable = false, precision = 12, scale = 2)
    private BigDecimal disposalQuantity;

    /**
     * 报废类型
     * SCRAP: 彻底报废
     * RECYCLE: 可回收利用
     * RETURN: 退回供应商
     * DONATE: 捐赠
     * DESTROY: 销毁
     */
    @Column(name = "disposal_type", nullable = false, length = 30)
    private String disposalType;

    /**
     * 报废原因（详细描述）
     */
    @Column(name = "disposal_reason", columnDefinition = "TEXT")
    private String disposalReason;

    /**
     * 报废日期
     */
    @Column(name = "disposal_date", nullable = false)
    private LocalDateTime disposalDate;

    /**
     * 处理方式说明
     */
    @Column(name = "disposal_method", columnDefinition = "TEXT")
    private String disposalMethod;

    /**
     * 审批人ID
     */
    @Column(name = "approved_by")
    private Integer approvedBy;

    /**
     * 审批人姓名
     */
    @Column(name = "approved_by_name", length = 100)
    private String approvedByName;

    /**
     * 审批日期
     */
    @Column(name = "approval_date")
    private LocalDateTime approvalDate;

    /**
     * 预估损失金额
     */
    @PriceSensitive
    @Column(name = "estimated_loss", precision = 12, scale = 2)
    private BigDecimal estimatedLoss;

    /**
     * 实际损失金额
     */
    @PriceSensitive
    @Column(name = "actual_loss", precision = 12, scale = 2)
    private BigDecimal actualLoss;

    /**
     * 回收价值（如果可回收）
     */
    @PriceSensitive
    @Column(name = "recovery_value", precision = 12, scale = 2)
    private BigDecimal recoveryValue;

    /**
     * 备注
     */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /**
     * 是否已审批
     */
    @Column(name = "is_approved", nullable = false)
    @Builder.Default
    private Boolean isApproved = false;

    /**
     * SP12 §5.3: 工作流实例 ID，关联 ApprovalWorkflowInstance.id。
     * 非空时表示已通过审批工作流审批；直接调用 approveDisposal() 绕过流程时保持 null。
     */
    @Column(name = "workflow_instance_id", length = 191)
    private String workflowInstanceId;

    // ===================================================================
    // 关联关系
    // ===================================================================

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quality_inspection_id", referencedColumnName = "id", insertable = false, updatable = false)
    private QualityInspection qualityInspection;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rework_record_id", referencedColumnName = "id", insertable = false, updatable = false)
    private ReworkRecord reworkRecord;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "production_batch_id", referencedColumnName = "id", insertable = false, updatable = false)
    private ProductionBatch productionBatch;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_batch_id", referencedColumnName = "id", insertable = false, updatable = false)
    private MaterialBatch materialBatch;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by", referencedColumnName = "id", insertable = false, updatable = false)
    private User approver;

    // ===================================================================
    // 业务方法
    // ===================================================================

    /**
     * 审批报废申请
     */
    public void approve(Integer approverId, String approverName) {
        this.isApproved = true;
        this.approvedBy = approverId;
        this.approvedByName = approverName;
        this.approvalDate = LocalDateTime.now();
    }

    /**
     * 计算净损失（扣除回收价值）
     *
     * <p>Defensive null guard — all three inputs ({@code actualLoss}, {@code estimatedLoss},
     * {@code recoveryValue}) are {@code @PriceSensitive}, stripped to {@code null} for
     * roles lacking {@code procurement:price:view}. Return {@code null} (not
     * {@code ZERO}) when both loss inputs are absent — ZERO would leak a "no loss"
     * signal to warehouse_manager.
     */
    @Transient
    @PriceSensitive
    public BigDecimal getNetLoss() {
        BigDecimal loss = actualLoss != null ? actualLoss : estimatedLoss;
        if (loss == null) {
            return null;
        }
        BigDecimal recovery = recoveryValue != null ? recoveryValue : BigDecimal.ZERO;
        return loss.subtract(recovery);
    }

    /**
     * 检查是否可回收
     */
    @Transient
    public boolean isRecyclable() {
        return "RECYCLE".equals(disposalType);
    }

    // ===================================================================
    // 前端字段别名
    // ===================================================================

    /**
     * approverId 别名（兼容前端）
     * 前端使用 approverId，后端使用 approvedBy
     */
    @JsonProperty("approverId")
    public Integer getApproverId() {
        return approvedBy;
    }

    /**
     * approverName 别名（兼容前端）
     * 前端使用 approverName，后端使用 approvedByName
     */
    @JsonProperty("approverName")
    public String getApproverName() {
        return approvedByName;
    }

    /**
     * approvedAt 别名（兼容前端）
     * 前端使用 approvedAt，后端使用 approvalDate
     */
    @JsonProperty("approvedAt")
    public LocalDateTime getApprovedAt() {
        return approvalDate;
    }

    /**
     * isRecyclable 的 JSON 属性（确保序列化）
     */
    @JsonProperty("isRecyclable")
    public Boolean getIsRecyclable() {
        return isRecyclable();
    }

    @PrePersist
    protected void onCreate() {
        super.onCreate();
        if (disposalDate == null) {
            disposalDate = LocalDateTime.now();
        }
        if (isApproved == null) {
            isApproved = false;
        }
    }
}

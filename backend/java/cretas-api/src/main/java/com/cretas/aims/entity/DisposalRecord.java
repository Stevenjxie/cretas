package com.cretas.aims.entity;

import com.cretas.aims.security.PriceSensitive;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.hibernate.annotations.Where;

/**
 * 报废记录实体类 (报废 ≠ 报损).
 *
 * <p>用于追踪<b>不合格品</b>的<b>报废</b>处理: 关联 QualityInspection / ReworkRecord /
 * ProductionBatch / MaterialBatch, 处置类型 SCRAP/RECYCLE/RETURN/DONATE/DESTROY, 走 SP12
 * 通用审批流 (workflowInstanceId)。LIVE — DisposalController (/disposal-records) + web-admin
 * quality/disposals/list.vue + RN DisposalRecordManagementScreen 全链路在用, 非死代码。
 *
 * <p>⚠️ 与 {@link com.cretas.aims.entity.inventory.WastageReport}(报损单, SP7) <b>不同领域,
 * 不可合并</b>:
 * <ul>
 *   <li>DisposalRecord = 报废: 质检/返工后对不合格品的处置 (销毁/回收/退供)。</li>
 *   <li>WastageReport  = 报损: 仓库/工厂对<b>原料批次</b>库存损耗的双轨审批写减 (照片强制,
 *       approve 写 MaterialBatchAdjustment 负数)。</li>
 * </ul>
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
     * 成品库存批次ID（针对成品报损 → 扣减 FinishedGoodsBatch 可售库存）。
     *
     * <p>R12 (2026-06-23): 此前成品报损只有 {@code productionBatchId} (生产批次的产出额定值,
     * 非可售库存) → approveDisposal 走 422 安全阻止。新增本字段后, 成品报损可直接关联
     * {@link com.cretas.aims.entity.inventory.FinishedGoodsBatch} (可售成品库存批次),
     * 审批通过时真扣其可用量 (减 producedQuantity + 写 FinishedGoodsAdjustmentLog SCRAP 留痕)。
     *
     * <p>向后兼容: 旧数据仅有 productionBatchId 无 finishedGoodsBatchId → 仍走 422 安全阻止,
     * 绝不静默扣错表。
     */
    @Column(name = "finished_goods_batch_id", length = 191)
    private String finishedGoodsBatchId;

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
     * 证据图片 URL 列表（逗号分隔）
     *
     * <p>用于存储废弃现场照片的 OSS/CDN URL，多张以逗号分隔。
     * V20261024_15 新增，替换此前将证据 URL 塞入 notes 字段的 hack。
     */
    @Column(name = "evidence_images", columnDefinition = "TEXT")
    private String evidenceImages;

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
     * 审批状态: PENDING(待审批) / APPROVED(已批准) / REJECTED(已拒绝)。
     *
     * <p>引入原因 (审计 round1 F-BUG-2): 此前实体只有 {@code isApproved} 布尔, 无法区分
     * "待审批" 与 "已拒绝" — 前端却读 {@code row.status} (PENDING/APPROVED/REJECTED) 决定
     * 按钮显示与状态标签。拒绝必须可持久化, 故新增显式状态列。
     *
     * <p>{@code isApproved} 保留为派生真值 (APPROVED 时为 true), 兼容旧统计查询
     * ({@code countByFactoryIdAndIsApproved}) 与库存扣减判断。</p>
     */
    @Column(name = "approval_status", nullable = false, length = 20)
    @Builder.Default
    private String approvalStatus = "PENDING";

    /**
     * 拒绝原因 (仅 REJECTED 时有值)。
     */
    @Column(name = "reject_reason", columnDefinition = "TEXT")
    private String rejectReason;

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
     * 审批报废申请 (置 APPROVED, 触发库存扣减)。
     */
    public void approve(Integer approverId, String approverName) {
        this.isApproved = true;
        this.approvalStatus = "APPROVED";
        this.approvedBy = approverId;
        this.approvedByName = approverName;
        this.approvalDate = LocalDateTime.now();
    }

    /**
     * 拒绝报废申请 (置 REJECTED, <b>不</b>扣减库存)。
     *
     * <p>记录审批人 (拒绝亦是审批动作, 复用 approvedBy/approvalDate 字段留痕)
     * 与拒绝原因。{@code isApproved} 保持 false。
     */
    public void reject(Integer approverId, String approverName, String reason) {
        this.isApproved = false;
        this.approvalStatus = "REJECTED";
        this.approvedBy = approverId;
        this.approvedByName = approverName;
        this.approvalDate = LocalDateTime.now();
        this.rejectReason = reason;
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

    /**
     * status 别名 (兼容前端): 前端 list.vue 读 {@code row.status}
     * (PENDING/APPROVED/REJECTED) 决定按钮显示与状态标签。
     *
     * <p>F-BUG-2 修复: 此前实体不序列化任何 status 字段 → 前端 {@code row.status}
     * 永远 undefined → 审批/拒绝按钮 (gated on status==='PENDING') 永不显示。
     *
     * <p>对历史行 (approvalStatus 列为 null, 仅有 isApproved): 派生 APPROVED/PENDING 兜底。
     */
    @JsonProperty("status")
    public String getStatus() {
        if (approvalStatus != null && !approvalStatus.isBlank()) {
            return approvalStatus;
        }
        return Boolean.TRUE.equals(isApproved) ? "APPROVED" : "PENDING";
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
        if (approvalStatus == null || approvalStatus.isBlank()) {
            approvalStatus = Boolean.TRUE.equals(isApproved) ? "APPROVED" : "PENDING";
        }
    }
}

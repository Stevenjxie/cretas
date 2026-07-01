package com.cretas.aims.entity.factory;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Where;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 半成品盘点任务实体 (镜像 SP7 {@link FactoryStocktake})。
 *
 * <p>与 SP7 仓库盘点 (针对原料 MaterialBatch) 平行, 本实体针对
 * {@link com.cretas.aims.entity.SemiFinishedInventory} (半成品 WIP 库存) 做周期盘点校准。
 * 客户张权要求半成品盘点也必须走审批流 (复用 INVENTORY_ADJUSTMENT 工作流)。
 *
 * <p>状态机 (与 SP7 完全一致):
 * INITIATED → COUNTING → PENDING_APPROVAL → APPROVED → APPLIED
 *                                         ↘ REJECTED → 可重提 PENDING_APPROVAL
 *
 * <p>红线: 盘点生效(apply)必须在审批通过(APPROVED)后执行, 仓管无法绕过直接改半成品库存;
 * 每笔差异写一行预留的 {@code SemiFinishedInventoryTransaction} ADJUST/STOCKTAKE 流水留痕。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"items"})
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "semi_finished_stocktakes",
        indexes = {
                @Index(name = "idx_sf_stocktake_factory_month", columnList = "factory_id,period_month"),
                @Index(name = "idx_sf_stocktake_status",        columnList = "status")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_sf_stocktake_no_factory", columnNames = {"factory_id", "stocktake_no"})
        }
)
@Where(clause = "deleted_at IS NULL")
public class SemiFinishedStocktake extends BaseEntity {

    public enum Status {
        INITIATED, COUNTING, PENDING_APPROVAL, APPROVED, APPLIED, REJECTED
    }

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) id = UUID.randomUUID().toString();
    }

    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    /** 盘点单号, 工厂内唯一, 格式 SFST-{YYYYMM}-{SEQ} */
    @Column(name = "stocktake_no", nullable = false, length = 50)
    private String stocktakeNo;

    /** 盘点月份, 格式 "2026-06" */
    @Column(name = "period_month", nullable = false, length = 7)
    private String periodMonth;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private Status status = Status.INITIATED;

    @Column(name = "initiated_by", nullable = false)
    private Long initiatedBy;

    @Column(name = "initiated_at", nullable = false)
    private LocalDateTime initiatedAt;

    @Column(name = "submitted_by")
    private Long submittedBy;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "reject_reason", columnDefinition = "TEXT")
    private String rejectReason;

    /** 生效时间 (半成品库存差异正式写入后设置) */
    @Column(name = "applied_at")
    private LocalDateTime appliedAt;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /** 复用 SP12 INVENTORY_ADJUSTMENT 工作流实例 ID, 关联 ApprovalWorkflowInstance.id */
    @Column(name = "workflow_instance_id", length = 191)
    private String workflowInstanceId;

    @OneToMany(mappedBy = "stocktake", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<SemiFinishedStocktakeItem> items = new ArrayList<>();
}

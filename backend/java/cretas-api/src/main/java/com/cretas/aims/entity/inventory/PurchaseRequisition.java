package com.cretas.aims.entity.inventory;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.enums.PurchaseRequisitionStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.Where;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 请购单实体 — Sprint 5 Track D (P-REQUISITION-1).
 *
 * <p>Round 12 §B.2 X2 finding: HJ 采购模块有"请购单" (Material Requisition) + "请购汇总" sub-menu.
 * Cretas 仅 {@code ShortageAnalysisService} (无 user-facing entity). 在谈食品厂客户问"我能不能
 * 从生产员发请购单上来" — Cretas 当前不行, 本 entity 补齐.
 *
 * <p>State machine:
 * <pre>
 *   DRAFT ──submit──> PENDING_APPROVAL ──approve──> APPROVED ──convertToPO──> CONVERTED_TO_PO
 *                          │                            │
 *                          └──reject─────────> REJECTED ┘
 * </pre>
 *
 * <p>行项目存 JSONB (Sprint 5 MVP); 未来 Sprint 6 可拆 RequisitionItem 关系表.
 *
 * @since 2026-05-19
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"requestedItems"})
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "purchase_requisitions",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"factory_id", "requisition_number"})
        },
        indexes = {
                @Index(name = "idx_pr_factory", columnList = "factory_id"),
                @Index(name = "idx_pr_status", columnList = "factory_id, status"),
                @Index(name = "idx_pr_requester", columnList = "factory_id, requester_id")
        }
)
@Where(clause = "deleted_at IS NULL")
public class PurchaseRequisition extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    // @PrePersist moved to onCreateRequisition() (line ~133) — JPA spec forbids
    // multiple @PrePersist on same class. Bodies merged 2026-05-20 (8th deploy fix).
    void assignUUID() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    @NotBlank(message = "工厂不能为空")
    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    @NotBlank(message = "请购单号不能为空")
    @Column(name = "requisition_number", nullable = false, length = 50)
    private String requisitionNumber;

    @NotNull(message = "请购人不能为空")
    @Column(name = "requester_id", nullable = false)
    private Long requesterId;

    @Column(name = "requester_dept_id", length = 64)
    private String requesterDeptId;

    /**
     * 请购行项目 — JSONB list.
     *
     * <p>每项形如: {@code {materialTypeId, materialName, quantity, unit, suggestedSupplierId, remark}}.
     * Sprint 5 MVP 用 JSONB 存; Sprint 6 视客户使用情况拆独立 RequisitionItem 关系表
     * (会更利于报表与外键约束).
     */
    @Type(JsonBinaryType.class)
    @Column(name = "requested_items", columnDefinition = "jsonb", nullable = false)
    private List<Map<String, Object>> requestedItems = new ArrayList<>();

    @NotNull(message = "状态不能为空")
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PurchaseRequisitionStatus status = PurchaseRequisitionStatus.DRAFT;

    @Column(name = "expected_date")
    private LocalDate expectedDate;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Column(name = "approver_id")
    private Long approverId;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    /**
     * linkno → {@link PurchaseOrder#getId()}. APPROVED 状态触发 convertToPO 后填.
     * NULL 时表示尚未转单或被驳回.
     */
    @Column(name = "converted_po_id", length = 191)
    private String convertedPoId;

    @Column(name = "converted_at")
    private LocalDateTime convertedAt;

    @Column(name = "remark", columnDefinition = "TEXT")
    private String remark;

    /** Optimistic lock version */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    protected void onCreateRequisition() {
        // 2026-05-20: merged assignUUID() callback here per JPA single-@PrePersist rule
        assignUUID();
        if (requestedItems == null) {
            requestedItems = new ArrayList<>();
        }
        if (status == null) {
            status = PurchaseRequisitionStatus.DRAFT;
        }
    }

    // ==================== State transition helpers ====================

    /**
     * Guard: 校验是否可以从当前 status 转到 target. 否则抛
     * {@link IllegalStateException} (Service 层应 catch 转换为 BusinessException 400).
     */
    @JsonIgnore
    public void assertCanTransitionTo(PurchaseRequisitionStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "请购单状态非法转换: " + status + " → " + target
                    + " (单号 " + requisitionNumber + ")");
        }
    }
}

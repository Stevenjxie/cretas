package com.cretas.aims.entity.inventory;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.enums.ReturnOrderStatus;
import com.cretas.aims.entity.enums.ReturnType;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import org.hibernate.annotations.Type;
import com.cretas.aims.security.PriceSensitive;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import org.hibernate.annotations.Where;

@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"items"})
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "return_orders",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"factory_id", "return_number"})
        },
        indexes = {
                @Index(name = "idx_ro_factory", columnList = "factory_id"),
                @Index(name = "idx_ro_type", columnList = "return_type"),
                @Index(name = "idx_ro_status", columnList = "status"),
                @Index(name = "idx_ro_counterparty", columnList = "counterparty_id"),
                @Index(name = "idx_ro_return_date", columnList = "return_date")
        }
)
@Where(clause = "deleted_at IS NULL")
public class ReturnOrder extends BaseEntity {

    /** Sprint3-E F-VFLAG-1: 凭证生成状态. UNCREATED → PENDING → CREATED / FAILED. */
    @Enumerated(EnumType.STRING)
    @Column(name = "vflag", nullable = false, length = 20)
    private com.cretas.aims.entity.enums.VoucherFlag vflag = com.cretas.aims.entity.enums.VoucherFlag.UNCREATED;

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    @NotBlank
    @Column(name = "factory_id", nullable = false)
    private String factoryId;

    @NotBlank
    @Column(name = "return_number", nullable = false, length = 50)
    private String returnNumber;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "return_type", nullable = false, length = 32)
    private ReturnType returnType;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ReturnOrderStatus status = ReturnOrderStatus.DRAFT;

    @NotBlank
    @Column(name = "counterparty_id", nullable = false, length = 191)
    private String counterpartyId;

    @Column(name = "source_order_id", length = 191)
    private String sourceOrderId;

    @NotNull
    @Column(name = "return_date", nullable = false)
    private LocalDate returnDate;

    @PriceSensitive
    @Column(name = "total_amount", precision = 15, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    /**
     * 有食物 / 无食物 (T-RTA business logic, audit BLOCKER #571):
     *   TRUE  = 有食物退货 (库存入库到总仓 + 不良品状态; AR 冲减在 completeReturnOrder 触发)
     *   FALSE = 无食物退货 (直接退款; AR 冲减在 approveReturnOrder 立即触发, 无库存动作)
     *
     * <p>Customer (第四次:956-1037): "有食物的话, 库存入库到总仓 ... 无食物的话就退款, 财务审批".
     * Default TRUE per customer's stated primary case (实物退货).
     *
     * <p>Phase B (this PR): approve flow branches by this field.
     * Phase C (inventory inbound + 不良品 status flag): follow-up ticket — currently
     * completeReturnOrder for withGoods=true does NOT yet create MaterialBatch inbound;
     * only flips status + runs deferred AR 冲减.
     *
     * <p>Migration: V20260514_03__add_return_order_with_goods.sql
     */
    @Column(name = "with_goods", nullable = false)
    private Boolean withGoods = Boolean.TRUE;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "remark", columnDefinition = "TEXT")
    private String remark;

    @Version
    @Column(name = "version")
    private Long version;

    // ==================== SP6: 仓库执行字段 ====================

    /**
     * SP6 — 仓库人员执行退货的时间（供应商到现场领货 / 快递取件确认时间）。
     * null = 尚未执行。
     */
    @Column(name = "warehouse_executed_at")
    private LocalDateTime warehouseExecutedAt;

    /**
     * SP6 — 执行仓库操作的操作员用户 ID。null = 尚未执行。
     */
    @Column(name = "warehouse_executed_by")
    private Long warehouseExecutedBy;

    /**
     * SP6 — 涉及的原料批次 ID 列表（用于 decideException(RETURN_OVER) 关联批次）。
     * 存 JSON array of batch IDs。null = 无对应批次（退货前未入库）。
     */
    @Type(JsonBinaryType.class)
    @Column(name = "batch_ids", columnDefinition = "jsonb")
    private List<String> batchIds;

    // ==================== 关联 ====================

    @OneToMany(mappedBy = "returnOrder", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ReturnOrderItem> items = new ArrayList<>();

    // ==================== 计算属性 ====================

    @Transient
    public BigDecimal calculateTotalAmount() {
        if (items == null || items.isEmpty()) return BigDecimal.ZERO;
        // Defensive: items[].getLineAmount() may return null when unitPrice/lineAmount
        // are @PriceSensitive-stripped (PR #443 F2 fix). Filter nulls so reduce doesn't
        // NPE on BigDecimal::add — mirrors SalesOrder.calculateTotalAmount.
        return items.stream()
                .map(ReturnOrderItem::getLineAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}

package com.cretas.aims.entity;

import com.cretas.aims.entity.enums.ProductionPlanStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "production_settlements",
        indexes = {
                @Index(name = "idx_prod_settlement_factory_plan", columnList = "factory_id, production_plan_id"),
                @Index(name = "idx_prod_settlement_status", columnList = "factory_id, posting_status")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_prod_settlement_plan", columnNames = {"factory_id", "production_plan_id"}),
                @UniqueConstraint(name = "uq_prod_settlement_idem", columnNames = {"factory_id", "production_plan_id", "idempotency_key"})
        })
@Where(clause = "deleted_at IS NULL")
public class ProductionSettlement extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "production_plan_id", nullable = false, length = 191)
    private String productionPlanId;

    @Column(name = "plan_number", nullable = false, length = 50)
    private String planNumber;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "planned_quantity", nullable = false, precision = 12, scale = 2)
    private BigDecimal plannedQuantity;

    /**
     * Compatibility scalar for single-unit output. Mixed-unit Workflow output is
     * represented exclusively by production_settlement_output_lines.
     */
    @Column(name = "actual_finished_quantity", precision = 12, scale = 2)
    private BigDecimal actualFinishedQuantity;

    @Column(name = "actual_semi_finished_quantity", nullable = false, precision = 12, scale = 2)
    private BigDecimal actualSemiFinishedQuantity = BigDecimal.ZERO;

    @Column(name = "quantity_unit", length = 20)
    private String quantityUnit;

    @Column(name = "quantity_variance_reason", length = 64)
    private String quantityVarianceReason;

    @Column(name = "quantity_variance_note", columnDefinition = "TEXT")
    private String quantityVarianceNote;

    @Column(name = "material_variance_reason", length = 64)
    private String materialVarianceReason;

    @Column(name = "material_variance_note", columnDefinition = "TEXT")
    private String materialVarianceNote;

    @Column(name = "labor_deferred_reason", length = 64)
    private String laborDeferredReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_status_after", nullable = false, length = 30)
    private ProductionPlanStatus planStatusAfter;

    @Column(name = "posting_status", nullable = false, length = 30)
    private String postingStatus = "PENDING_POSTING";

    @Column(name = "posting_message", columnDefinition = "TEXT")
    private String postingMessage;

    @Column(name = "warehouse_receipt_idempotency_key", length = 128)
    private String warehouseReceiptIdempotencyKey;

    @Column(name = "warehouse_received_quantity", precision = 12, scale = 2)
    private BigDecimal warehouseReceivedQuantity;

    @Column(name = "warehouse_variance_quantity", precision = 12, scale = 2)
    private BigDecimal warehouseVarianceQuantity;

    @Column(name = "warehouse_variance_reason", length = 64)
    private String warehouseVarianceReason;

    @Column(name = "warehouse_responsibility_side", length = 30)
    private String warehouseResponsibilitySide;

    @Column(name = "warehouse_variance_note", columnDefinition = "TEXT")
    private String warehouseVarianceNote;

    @Column(name = "finished_goods_batch_id", length = 191)
    private String finishedGoodsBatchId;

    @Column(name = "transit_ledger_id", length = 191)
    private String transitLedgerId;

    @Column(name = "warehouse_received_by")
    private Long warehouseReceivedBy;

    @Column(name = "warehouse_received_at")
    private LocalDateTime warehouseReceivedAt;

    @Column(name = "settled_by")
    private Long settledBy;

    @Column(name = "settled_at", nullable = false)
    private LocalDateTime settledAt;
}

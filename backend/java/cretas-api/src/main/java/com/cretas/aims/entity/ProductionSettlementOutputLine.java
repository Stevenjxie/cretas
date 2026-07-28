package com.cretas.aims.entity;

import com.cretas.aims.entity.bom.BomRecipe;
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
import java.util.UUID;

/**
 * Immutable server-derived terminal output fact for one production settlement.
 * Cost authority is copied from the plan's pinned BOM family; receipt quantities
 * may be added later but clients can never submit allocation policy or cost.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "production_settlement_output_lines",
        indexes = {
                @Index(name = "idx_pso_factory_settlement", columnList = "factory_id, settlement_id"),
                @Index(name = "idx_pso_factory_plan", columnList = "factory_id, production_plan_id")
        },
        uniqueConstraints = @UniqueConstraint(
                name = "uq_pso_reported_output",
                columnNames = {"settlement_id", "product_type_id", "reported_batch_number", "quantity_unit"}))
@Where(clause = "deleted_at IS NULL")
public class ProductionSettlementOutputLine extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "settlement_id", nullable = false, length = 191)
    private String settlementId;

    @Column(name = "production_plan_id", nullable = false, length = 191)
    private String productionPlanId;

    @Column(name = "product_type_id", nullable = false, length = 191)
    private String productTypeId;

    @Column(name = "reported_batch_number", nullable = false, length = 64)
    private String reportedBatchNumber;

    @Column(name = "reported_quantity", nullable = false, precision = 18, scale = 4)
    private BigDecimal reportedQuantity;

    @Column(name = "quantity_unit", nullable = false, length = 20)
    private String quantityUnit;

    @Column(name = "bom_family_id", nullable = false, length = 64)
    private String bomFamilyId;

    @Column(name = "bom_recipe_id", nullable = false, length = 191)
    private String bomRecipeId;

    @Column(name = "bom_recipe_version", nullable = false)
    private Integer bomRecipeVersion;

    @Column(name = "target_terminal_node_id", nullable = false, length = 128)
    private String targetTerminalNodeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "output_role", nullable = false, length = 24)
    private BomRecipe.OutputRole outputRole;

    @Column(name = "cost_allocation_ratio", precision = 7, scale = 4)
    private BigDecimal costAllocationRatio;

    @Column(name = "byproduct_nrv_unit_price", precision = 15, scale = 4)
    private BigDecimal byproductNrvUnitPrice;

    @Column(name = "allocated_cost", precision = 18, scale = 6)
    private BigDecimal allocatedCost;

    @Column(name = "unit_cost", precision = 18, scale = 6)
    private BigDecimal unitCost;

    @Column(name = "received_quantity", precision = 18, scale = 4)
    private BigDecimal receivedQuantity;

    @Column(name = "finished_goods_batch_id", length = 191)
    private String finishedGoodsBatchId;

    @Column(name = "receipt_idempotency_key", length = 128)
    private String receiptIdempotencyKey;

    @Column(name = "received_by")
    private Long receivedBy;

    @Column(name = "received_at")
    private LocalDateTime receivedAt;

    @Column(name = "status", nullable = false, length = 24)
    private String status = "REPORTED";

    public static ProductionSettlementOutputLine create() {
        ProductionSettlementOutputLine line = new ProductionSettlementOutputLine();
        line.setId(UUID.randomUUID().toString());
        return line;
    }
}

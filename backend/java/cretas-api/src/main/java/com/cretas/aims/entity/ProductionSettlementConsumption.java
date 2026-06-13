package com.cretas.aims.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "production_settlement_consumptions",
        indexes = {
                @Index(name = "idx_prod_settlement_cons_settlement", columnList = "settlement_id"),
                @Index(name = "idx_prod_settlement_cons_plan", columnList = "factory_id, production_plan_id")
        })
@Where(clause = "deleted_at IS NULL")
public class ProductionSettlementConsumption extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "settlement_id", nullable = false, length = 191)
    private String settlementId;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "production_plan_id", nullable = false, length = 191)
    private String productionPlanId;

    @Column(name = "source_type", nullable = false, length = 30)
    private String sourceType;

    @Column(name = "material_batch_id", length = 191)
    private String materialBatchId;

    @Column(name = "semi_finished_inventory_id")
    private Long semiFinishedInventoryId;

    @Column(name = "material_type_id", length = 191)
    private String materialTypeId;

    @Column(name = "batch_number", length = 100)
    private String batchNumber;

    @Column(name = "quantity", nullable = false, precision = 12, scale = 2)
    private BigDecimal quantity;

    @Column(name = "unit", length = 20)
    private String unit;

    @Column(name = "available_before", precision = 12, scale = 2)
    private BigDecimal availableBefore;

    @Column(name = "warehouse_id", length = 64)
    private String warehouseId;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;
}

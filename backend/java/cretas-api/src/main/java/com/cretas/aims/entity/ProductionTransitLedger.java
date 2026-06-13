package com.cretas.aims.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "production_transit_ledgers",
        indexes = {
                @Index(name = "idx_prod_transit_factory_status", columnList = "factory_id, status"),
                @Index(name = "idx_prod_transit_plan", columnList = "factory_id, production_plan_id"),
                @Index(name = "idx_prod_transit_settlement", columnList = "settlement_id")
        })
@Where(clause = "deleted_at IS NULL")
public class ProductionTransitLedger extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "settlement_id", nullable = false, length = 191)
    private String settlementId;

    @Column(name = "production_plan_id", nullable = false, length = 191)
    private String productionPlanId;

    @Column(name = "plan_number", nullable = false, length = 50)
    private String planNumber;

    @Column(name = "ledger_type", nullable = false, length = 40)
    private String ledgerType;

    @Column(name = "reported_quantity", nullable = false, precision = 12, scale = 2)
    private BigDecimal reportedQuantity;

    @Column(name = "confirmed_quantity", nullable = false, precision = 12, scale = 2)
    private BigDecimal confirmedQuantity;

    @Column(name = "variance_quantity", nullable = false, precision = 12, scale = 2)
    private BigDecimal varianceQuantity;

    @Column(name = "tolerance_quantity", nullable = false, precision = 12, scale = 2)
    private BigDecimal toleranceQuantity;

    @Column(name = "quantity_unit", length = 20)
    private String quantityUnit;

    @Column(name = "variance_reason", nullable = false, length = 64)
    private String varianceReason;

    @Column(name = "responsibility_side", nullable = false, length = 30)
    private String responsibilitySide;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "created_by")
    private Long createdBy;

}

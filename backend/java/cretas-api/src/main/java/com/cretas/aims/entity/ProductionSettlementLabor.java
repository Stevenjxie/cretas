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
@Table(name = "production_settlement_labor",
        indexes = {
                @Index(name = "idx_prod_settlement_labor_settlement", columnList = "settlement_id"),
                @Index(name = "idx_prod_settlement_labor_plan", columnList = "factory_id, production_plan_id")
        })
@Where(clause = "deleted_at IS NULL")
public class ProductionSettlementLabor extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "settlement_id", nullable = false, length = 191)
    private String settlementId;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "production_plan_id", nullable = false, length = 191)
    private String productionPlanId;

    @Column(name = "worker_id")
    private Long workerId;

    @Column(name = "worker_name", length = 100)
    private String workerName;

    @Column(name = "work_type", length = 64)
    private String workType;

    @Column(name = "minutes", nullable = false)
    private Integer minutes;

    @Column(name = "headcount", nullable = false)
    private Integer headcount = 1;

    @Column(name = "hourly_rate", precision = 12, scale = 2)
    private BigDecimal hourlyRate;

    @Column(name = "labor_cost", precision = 12, scale = 2)
    private BigDecimal laborCost;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;
}

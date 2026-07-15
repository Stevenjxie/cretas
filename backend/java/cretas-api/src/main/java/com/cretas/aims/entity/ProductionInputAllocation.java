package com.cretas.aims.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;

/**
 * 正式报工时，操作员录入的原料总量被系统分摊到生产库具体批次后的不可变明细。
 * 批次实际扣减仍由现有小结事务执行；未小结的 SUBMITTED 行通过本表形成库存占用视图。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "production_input_allocations",
        indexes = {
                @Index(name = "idx_pia_row", columnList = "factory_id,process_sheet_row_id"),
                @Index(name = "idx_pia_batch", columnList = "factory_id,material_batch_id")
        })
@Where(clause = "deleted_at IS NULL")
public class ProductionInputAllocation extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "production_plan_id", nullable = false, length = 191)
    private String productionPlanId;

    @Column(name = "process_sheet_row_id", nullable = false)
    private Long processSheetRowId;

    @Column(name = "material_type_id", nullable = false, length = 191)
    private String materialTypeId;

    @Column(name = "material_batch_id", nullable = false, length = 191)
    private String materialBatchId;

    @Column(name = "warehouse_id", nullable = false, length = 64)
    private String warehouseId;

    @Column(name = "quantity", nullable = false, precision = 18, scale = 6)
    private BigDecimal quantity;

    @Column(name = "unit", nullable = false, length = 16)
    private String unit;

    @Column(name = "allocation_order", nullable = false)
    private Integer allocationOrder;

    @Column(name = "status", nullable = false, length = 16)
    private String status = "ALLOCATED";

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;
}

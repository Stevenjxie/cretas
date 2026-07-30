package com.cretas.aims.entity.factory;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 盐化仓独立扣量记录 (SP7 F11, 转录 [91:30]-[91:42]).
 *
 * <p>客户原话: "盐化要的是单独扣" — 盐化(代加工)扣减走独立口径，不混入正常销售报表。
 * 哪怕走成品出库流程，最终统计/报表口径仍归盐化。
 *
 * <p>场景: 外商/代加工客户从盐化仓提货 → 创建本记录 → 触发对应 MaterialBatch.usedQuantity 扣量。
 * 销售报表过滤 {@code sourceType != SALTED}，盐化报表专门汇总此表。
 *
 * <p><b>不破坏通用仓</b>: 现有 MaterialBatch 扣量逻辑不变，本记录作为"标记层"记录
 * 哪些扣量来自盐化仓，提供独立报表隔离。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "salted_warehouse_deductions",
        indexes = {
                @Index(name = "idx_swd_factory_date", columnList = "factory_id, deduction_date"),
                @Index(name = "idx_swd_warehouse", columnList = "warehouse_id"),
                @Index(name = "idx_swd_batch", columnList = "material_batch_id"),
                @Index(name = "idx_swd_order_no", columnList = "factory_id, order_number")
        })
@Where(clause = "deleted_at IS NULL")
public class SaltedWarehouseDeduction extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @PrePersist
    void assignId() {
        if (id == null) id = UUID.randomUUID().toString();
    }

    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    /**
     * 盐化仓 ID — 必须是 type=SALTED 的 factory_warehouses 记录。
     * FK: factory_warehouses.id
     */
    @Column(name = "warehouse_id", nullable = false, length = 64)
    private String warehouseId;

    /**
     * 被扣减的原料批次 ID。
     * FK: material_batches.id
     */
    @Column(name = "material_batch_id", nullable = false, length = 191)
    private String materialBatchId;

    /**
     * 扣量单号 — 格式 "SD-{yyyyMMdd}-{seq}", 盐化供单/代加工单号。
     * "谁盐化谁建" — 权限下放给做盐化业务的销售自建。
     */
    @Column(name = "order_number", nullable = false, length = 64)
    private String orderNumber;

    /** 扣量日期 */
    @Column(name = "deduction_date", nullable = false)
    private LocalDate deductionDate;

    /** 扣减数量 (kg 或原料批次 quantityUnit) */
    @Column(name = "quantity", nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    /** 扣减单位 — 对齐 material_batches.quantity_unit */
    @Column(name = "quantity_unit", nullable = false, length = 20)
    private String quantityUnit;

    /**
     * 单价 (可选，盘账勾稽: 期中出库数量对+单价对=账平)。
     * 转录 [92:12]: 期中出库是盘点核账勾稽依据。
     */
    @Column(name = "unit_price", precision = 10, scale = 4)
    private BigDecimal unitPrice;

    /** 总金额 = quantity × unitPrice (null 时系统不计算) */
    @Column(name = "total_amount", precision = 15, scale = 2)
    private BigDecimal totalAmount;

    /** 外商/代加工客户名称 (自由文本，无 FK 约束) */
    @Column(name = "customer_name", length = 200)
    private String customerName;

    /** 操作人 user_id */
    @Column(name = "operator_id")
    private Long operatorId;

    /** 备注 */
    @Column(name = "remarks", columnDefinition = "TEXT")
    private String remarks;

    /**
     * 计算 totalAmount = quantity × unitPrice (两者均非 null 时).
     * 调用方在 set quantity/unitPrice 后可手动调用。
     */
    public void computeTotal() {
        if (quantity != null && unitPrice != null) {
            this.totalAmount = quantity.multiply(unitPrice)
                    .setScale(2, java.math.RoundingMode.HALF_UP);
        }
    }
}

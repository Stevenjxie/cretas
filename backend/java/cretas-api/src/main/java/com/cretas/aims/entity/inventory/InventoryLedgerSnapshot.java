package com.cretas.aims.entity.inventory;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.enums.SnapshotType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * SP11: 库存台账期末快照.
 *
 * <p>在月结时({@code AccountingPeriodServiceImpl.confirmClose()})触发写入,
 * 冻结每种物料的期末数量/单价/金额. 下期查询时作为期初数据来源.
 *
 * <p>精度约定(对齐 CostRollupUtil):
 * <ul>
 *   <li>closingQty: scale-6 (NUMERIC(18,6))</li>
 *   <li>closingUnitPrice: scale-4 (NUMERIC(18,4))</li>
 *   <li>closingAmount: scale-2 (NUMERIC(18,2)), ROUND_HALF_UP</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "inventory_ledger_snapshots",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"factory_id", "accounting_period_id", "material_type_id", "snapshot_type"}),
        indexes = {
                @Index(name = "idx_ils_factory_period", columnList = "factory_id, accounting_period_id"),
                @Index(name = "idx_ils_material", columnList = "material_type_id")
        })
@Where(clause = "deleted_at IS NULL")
public class InventoryLedgerSnapshot extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    @Column(name = "factory_id", nullable = false, length = 191)
    private String factoryId;

    @Column(name = "accounting_period_id", nullable = false, length = 191)
    private String accountingPeriodId;

    @Column(name = "material_type_id", nullable = false, length = 191)
    private String materialTypeId;

    @Column(name = "material_code", length = 64)
    private String materialCode;

    @Column(name = "material_name", length = 128)
    private String materialName;

    @Column(name = "unit", length = 32)
    private String unit;

    /** 期末数量 (scale-6, 对齐 CostRollupUtil) */
    @Column(name = "closing_qty", precision = 18, scale = 6, nullable = false)
    @Builder.Default
    private BigDecimal closingQty = BigDecimal.ZERO;

    /** 期末移动均价 (scale-4); 诚实 null 当无法计算时 */
    @Column(name = "closing_unit_price", precision = 18, scale = 4)
    private BigDecimal closingUnitPrice;

    /** 期末金额 = closingQty × closingUnitPrice (scale-2, ROUND_HALF_UP) */
    @Column(name = "closing_amount", precision = 18, scale = 2)
    private BigDecimal closingAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "snapshot_type", nullable = false, length = 32)
    @Builder.Default
    private SnapshotType snapshotType = SnapshotType.PERIOD_CLOSE;
}

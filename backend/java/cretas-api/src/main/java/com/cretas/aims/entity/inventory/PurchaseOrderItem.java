package com.cretas.aims.entity.inventory;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.security.PriceSensitive;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import org.hibernate.annotations.Where;

/**
 * 采购订单行项目
 * 每行对应一种原料/食材的采购明细
 *
 * @author Cretas Team
 * @since 2026-02-19
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"purchaseOrder", "materialType"})
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "purchase_order_items",
        indexes = {
                @Index(name = "idx_poi_order", columnList = "purchase_order_id"),
                @Index(name = "idx_poi_material", columnList = "material_type_id")
        }
)
@Where(clause = "deleted_at IS NULL")
public class PurchaseOrderItem extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "purchase_order_id", nullable = false, length = 191)
    private String purchaseOrderId;

    @Column(name = "material_type_id", nullable = false, length = 191)
    private String materialTypeId;

    /** 原料/食材名称（冗余，方便查询） */
    @Column(name = "material_name", length = 200)
    private String materialName;

    @NotNull
    @Positive
    @Column(name = "quantity", nullable = false, precision = 15, scale = 4)
    private BigDecimal quantity;

    @Column(name = "unit", nullable = false, length = 20)
    private String unit;

    /**
     * Canonical denominator unit of {@link #unitPrice}, for example {@code kg}.
     * This is deliberately stored as a unit code, never as a display string such as 元/kg.
     */
    @Column(name = "price_unit", nullable = false, length = 20)
    private String priceUnit;

    /**
     * Snapshot factor converting one {@link #unit} into {@link #priceUnit}.
     * It makes historical order totals deterministic even if master-data conversions change later.
     */
    @Column(name = "quantity_to_price_factor", nullable = false, precision = 24, scale = 12)
    private BigDecimal quantityToPriceFactor = BigDecimal.ONE;

    /** Supplier/material purchase-package identity selected at order time. */
    @Column(name = "supplier_material_id", length = 64)
    private String supplierMaterialId;

    @Column(name = "purchase_packaging_spec_id", length = 64)
    private String purchasePackagingSpecId;

    @Column(name = "material_packaging_spec_id", length = 36)
    private String materialPackagingSpecId;

    /** Immutable unit/conversion snapshots for historical receipts and copies. */
    @Column(name = "purchase_package_unit_snapshot", length = 20)
    private String purchasePackageUnitSnapshot;

    @Column(name = "inventory_base_unit_snapshot", length = 20)
    private String inventoryBaseUnitSnapshot;

    @Column(name = "package_to_base_factor_snapshot", precision = 24, scale = 12)
    private BigDecimal packageToBaseFactorSnapshot;

    @Column(name = "inventory_quantity_snapshot", precision = 24, scale = 12)
    private BigDecimal inventoryQuantitySnapshot;

    @PriceSensitive
    @Column(name = "unit_price", precision = 15, scale = 4)
    private BigDecimal unitPrice;

    /** 税率（百分比，如 13 表示 13%） */
    @PriceSensitive
    @Column(name = "tax_rate", precision = 5, scale = 2)
    private BigDecimal taxRate = BigDecimal.ZERO;

    @PriceSensitive
    @Column(name = "untaxed_amount_snapshot", precision = 18, scale = 2)
    private BigDecimal untaxedAmountSnapshot;

    @PriceSensitive
    @Column(name = "tax_amount_snapshot", precision = 18, scale = 2)
    private BigDecimal taxAmountSnapshot;

    @PriceSensitive
    @Column(name = "tax_inclusive_amount_snapshot", precision = 18, scale = 2)
    private BigDecimal taxInclusiveAmountSnapshot;

    /** 已收货数量 */
    @Column(name = "received_quantity", nullable = false, precision = 15, scale = 4)
    private BigDecimal receivedQuantity = BigDecimal.ZERO;

    @Column(name = "remark", length = 500)
    private String remark;

    /** 规格 */
    @Column(name = "specification", length = 200)
    private String specification;

    /** 箱数 */
    @Column(name = "box_quantity", precision = 15, scale = 2)
    private BigDecimal boxQuantity;

    // ==================== 关联 ====================

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_id", referencedColumnName = "id", insertable = false, updatable = false)
    private PurchaseOrder purchaseOrder;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_type_id", referencedColumnName = "id", insertable = false, updatable = false)
    private RawMaterialType materialType;

    // ==================== 计算属性 ====================

    /** 行金额 = 数量 × 单价. Price-sensitive: returns null when unitPrice stripped. */
    @Transient
    @PriceSensitive
    public BigDecimal getLineAmount() {
        // Defensive null guard — unitPrice is @PriceSensitive, stripped to null
        // for warehouse_manager. Return null (not ZERO) to avoid leaking "free".
        if (unitPrice == null || quantity == null) return null;
        BigDecimal factor = quantityToPriceFactor != null ? quantityToPriceFactor : BigDecimal.ONE;
        return quantity.multiply(factor).multiply(unitPrice).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /** 含税金额. Price-sensitive: returns null when unitPrice / taxRate stripped. */
    @Transient
    @PriceSensitive
    public BigDecimal getLineAmountWithTax() {
        BigDecimal amount = getLineAmount();
        // Defensive: getLineAmount() now returns null when unitPrice stripped.
        if (amount == null) return null;
        if (taxRate == null || taxRate.compareTo(BigDecimal.ZERO) == 0) return amount;
        BigDecimal taxMultiplier = BigDecimal.ONE.add(taxRate.divide(new BigDecimal("100"), 6, BigDecimal.ROUND_HALF_UP));
        return amount.multiply(taxMultiplier).setScale(2, BigDecimal.ROUND_HALF_UP);
    }

    /** 未收货数量. Non-price (no @PriceSensitive). */
    @Transient
    public BigDecimal getPendingQuantity() {
        if (quantity == null) return BigDecimal.ZERO;
        BigDecimal received = receivedQuantity != null ? receivedQuantity : BigDecimal.ZERO;
        return quantity.subtract(received);
    }
}

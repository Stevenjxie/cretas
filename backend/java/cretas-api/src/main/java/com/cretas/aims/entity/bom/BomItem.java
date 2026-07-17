package com.cretas.aims.entity.bom;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.security.PriceSensitive;
import lombok.*;
import lombok.experimental.SuperBuilder;

import jakarta.persistence.*;
import java.math.BigDecimal;
import org.hibernate.annotations.Where;

/**
 * BOM (Bill of Materials) 项目实体
 * 记录产品所需的原辅料配方
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-13
 */
@Entity
@Table(name = "bom_items", indexes = {
    @Index(name = "idx_bom_factory_product", columnList = "factory_id, product_type_id")
})
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Where(clause = "deleted_at IS NULL")
public class BomItem extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 工厂ID
     */
    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    /**
     * 产品类型ID
     */
    @Column(name = "product_type_id", nullable = false, length = 64)
    private String productTypeId;

    /**
     * 产品名称 (冗余字段，方便查询)
     */
    @Column(name = "product_name", length = 100)
    private String productName;

    /**
     * 原辅料类型ID
     */
    @Column(name = "material_type_id", nullable = false, length = 64)
    private String materialTypeId;

    /**
     * 原辅料名称 (冗余字段，方便查询)
     */
    @Column(name = "material_name", length = 100)
    private String materialName;

    /**
     * 成品含量/标准用量 (每单位成品所需原料量)
     */
    @Column(name = "standard_quantity", precision = 15, scale = 4)
    private BigDecimal standardQuantity;

    /**
     * 出成率 (百分比; 保水工序可 >100, 如六扇门猪舌保水 105–126%).
     * null = 待评估 (B1: 不再强制填充 100.00, 允许前端/UI 展示"待评估"状态).
     * {@link #getActualQuantity()} null 时回退到 standardQuantity (等效 100% 数学上一致).
     */
    @Column(name = "yield_rate", precision = 6, scale = 2)
    @Builder.Default
    private BigDecimal yieldRate = null;

    /**
     * 计量单位
     */
    @Column(name = "unit", length = 20)
    private String unit;

    /**
     * Unit price for BOM cost, stored pre-tax. Tax-included purchase prices must be
     * converted before being copied here.
     *
     * <p>Stripped to {@code null} for roles lacking {@code procurement:price:view}
     * (warehouse_manager, operator, quality_inspector). See PR #455 BUG-2 follow-up.
     */
    @PriceSensitive
    @Column(name = "unit_price", precision = 15, scale = 4)
    private BigDecimal unitPrice;

    /** Canonical denominator unit of unitPrice (for example kg). */
    @Column(name = "price_unit", length = 20)
    private String priceUnit;

    /** Snapshot factor converting one BOM quantity unit into priceUnit. */
    @Column(name = "quantity_to_price_factor", nullable = false, precision = 24, scale = 12)
    @Builder.Default
    private BigDecimal quantityToPriceFactor = BigDecimal.ONE;

    /**
     * 税率 (百分比，如13表示13%增值税)
     */
    @Column(name = "tax_rate", precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal taxRate = BigDecimal.ZERO;

    /**
     * 物料分类: RAW (原料) / AUXILIARY (辅料) / PACKAGING (包材)
     * P0-14 客户需求: BOM 拆原料/辅料/包材 3 块
     */
    @Column(name = "material_category", nullable = false, length = 32)
    @Builder.Default
    private String materialCategory = "RAW";

    /**
     * 排序顺序
     */
    @Column(name = "sort_order")
    @Builder.Default
    private Integer sortOrder = 0;

    /**
     * 备注
     */
    @Column(name = "remark", length = 500)
    private String remark;

    // ===== SP12 #728: 组合装/嵌套 BOM 字段 (mirrors bom_recipe_items) =====

    /**
     * SP4: 按成品份数投料标志。true = 每份成品用量固定，不随出成率折算（调味料、添加剂等）。
     */
    @Column(name = "per_portion", nullable = false)
    @Builder.Default
    private Boolean perPortion = false;

    /**
     * SP8: 组合装半成品引用编码。非 null 时表示此 BOM 行为半成品引用。
     */
    @Column(name = "semi_finished_ref_code", length = 100)
    private String semiFinishedRefCode;

    /**
     * SP12 #728: 组合装子产品/先做后用 嵌套 BOM 引用。
     * 非 null 时触发 NestedBomCostService 递归聚合子 BOM 成本。
     */
    @Column(name = "sub_product_type_id", length = 100)
    private String subProductTypeId;

    /**
     * 计算实际用量（考虑出成率）
     * 实际用量 = 标准用量 / (出成率/100)
     */
    @Transient
    public BigDecimal getActualQuantity() {
        if (standardQuantity == null) {
            return null;
        }
        if (yieldRate == null || yieldRate.compareTo(BigDecimal.ZERO) == 0) {
            return standardQuantity;
        }
        return standardQuantity.divide(
            yieldRate.divide(new BigDecimal("100"), 6, BigDecimal.ROUND_HALF_UP),
            6, BigDecimal.ROUND_HALF_UP
        );
    }

    /**
     * 计算单项原料成本
     * 成本 = 实际用量 * 单价
     */
    @Transient
    public BigDecimal calculateCost() {
        if (unitPrice == null || standardQuantity == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal factor = quantityToPriceFactor != null ? quantityToPriceFactor : BigDecimal.ONE;
        return getActualQuantity().multiply(factor).multiply(unitPrice)
                .setScale(4, java.math.RoundingMode.HALF_UP);
    }
}

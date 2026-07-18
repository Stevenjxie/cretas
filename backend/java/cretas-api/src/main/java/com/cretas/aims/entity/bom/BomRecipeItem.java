package com.cretas.aims.entity.bom;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.security.PriceSensitive;
import com.cretas.aims.service.shared.CostRollupUtil;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * M-BOM-1 BOM 配方项 (子表).
 *
 * <p>硬外键 {@code material_type_id → raw_material_types(id)} ON DELETE RESTRICT —
 * 禁手写物料名称 (客户原话 May10 line 217-222 "物料名称是要手写吗? — 应该是 SELECT").
 *
 * <p>{@link #calculateActualQuantity()} 实现出成率折算公式 (spec SCHEMA_DESIGN line 1340):
 * <pre>{@code
 *   actualQuantity = standardQuantity / (yieldRate / 100)
 * }</pre>
 * 例: standardQuantity=200g, yieldRate=58% → actualQuantity = 200 / 0.58 = 344.83g.
 *
 * @author Cretas Team / Track D1
 * @since 2026-05-14
 */
@Entity
@Table(name = "bom_recipe_items", indexes = {
    @Index(name = "idx_bri_recipe",   columnList = "recipe_id, sort_order"),
    @Index(name = "idx_bri_material", columnList = "factory_id, material_type_id"),
    @Index(name = "idx_bri_primary_code_v2", columnList = "factory_id, primary_code")
})
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Where(clause = "deleted_at IS NULL")
public class BomRecipeItem extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recipe_id", nullable = false, length = 191)
    private String recipeId;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    /** 硬外键 raw_material_types.id (DB-level enforced, ON DELETE RESTRICT). */
    @Column(name = "material_type_id", nullable = false, length = 191)
    private String materialTypeId;

    /** Denormalized name for display. Service rehydrates from raw_material_types if needed. */
    @Column(name = "material_name", length = 200)
    private String materialName;

    @Column(name = "standard_quantity", precision = 15, scale = 4)
    private BigDecimal standardQuantity;

    /** 该原料出成率 (0-100%), 默认 100 (无损耗). */
    @Column(name = "yield_rate", nullable = false, precision = 6, scale = 2)
    @Builder.Default
    private BigDecimal yieldRate = new BigDecimal("100.00");

    /** 折算后实际用量 (运行时算或写入). */
    @Column(name = "actual_quantity", precision = 15, scale = 4)
    private BigDecimal actualQuantity;

    /** CHECK constraint: 'g'/'kg'/'mg'/'ml'/'L'/'个'/'袋'/'箱'/'瓶'/'盒'. */
    @Column(name = "unit", nullable = false, length = 20)
    private String unit;

    /** Unit price for BOM cost, stored pre-tax; do not divide by tax rate in rollup. */
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

    @Column(name = "tax_rate", precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal taxRate = BigDecimal.ZERO;

    /** 单项成本 = actualQuantity × unitPrice. Recomputed by service. */
    @PriceSensitive
    @Column(name = "item_cost", precision = 15, scale = 4)
    private BigDecimal itemCost;

    /** RAW / AUXILIARY / PACKAGING. */
    @Column(name = "material_category", nullable = false, length = 32)
    @Builder.Default
    private String materialCategory = "RAW";

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    /** 配方可选项 (装饰菜等), 不影响生产计划完整性. */
    @Column(name = "is_optional", nullable = false)
    @Builder.Default
    private Boolean isOptional = false;

    /** 替代分组 — 同组互可替换 (e.g. 牛肉糜 / 鸡肉糜 都属于 group=MEAT_BASE). */
    @Column(name = "substitute_group", length = 50)
    private String substituteGroup;

    @Column(name = "remark", length = 500)
    private String remark;

    // ========== SP4-T3: 按份计量标志 + 半成品引用编码 ==========

    /**
     * 该 BOM 行是否按"份"计算（true = 每份用量，默认 false = 按批次总量）。
     * DB: per_portion BOOLEAN NOT NULL DEFAULT FALSE
     */
    @Column(name = "per_portion", nullable = false)
    @Builder.Default
    private Boolean perPortion = false;

    /**
     * 指向半成品（WIP）的引用编码，用于追踪原料→半成品→成品链路。
     * DB: semi_finished_ref_code VARCHAR(100) NULL
     */
    @Column(name = "semi_finished_ref_code", length = 100)
    private String semiFinishedRefCode;

    // ========== SP1: 嵌套 BOM — 组合装子产品引用 ==========

    /**
     * SP1: 若此 BOM 行是一个半成品/子产品组件（而非原材料），此列存储子产品的 product_type_id。
     *
     * <p>当此列非 null 时，成本聚合逻辑（{@link BomRecipeServiceImpl#recomputeMaterialCost}）
     * 会递归查找子产品的当前有效 BOM（{@code status=ACTIVE, is_current=TRUE}），
     * 取其 {@code totalCost / outputQuantityPerUnit} 作为单位成本，乘以本行 {@code actualQuantity}。
     *
     * <p>"先做后用"场景（先生产半成品入库，后续成品领用）：
     * 当 {@code semiFinishedRefCode} 也设置时，运行时可从 {@link SemiFinishedInventory#unitCost}
     * （移动均价）取值，优先级高于子 BOM 设计成本。
     *
     * <p>DB: sub_product_type_id VARCHAR(100) NULL
     */
    @Column(name = "sub_product_type_id", length = 100)
    private String subProductTypeId;

    // ========== SP8: BOM 物料前三位主编码冗余列 ==========

    /**
     * SP8: 物料前三位主编码冗余列 (如 001/002/003).
     * 与 materialTypeId 不替代; 仅供按类型搜索/统计.
     * 创建时优先用 DTO 传入, 次选从关联 RawMaterialType.primaryCode 自动回填.
     * DB: primary_code_ref VARCHAR(3) NULL
     */
    @Column(name = "primary_code", length = 3)
    private String primaryCode;

    @Column(name = "primary_code_ref", length = 3)
    private String primaryCodeRef;

    /** Back-ref to parent recipe; insertable/updatable=false because recipeId column drives FK. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipe_id", insertable = false, updatable = false)
    @JsonIgnore
    private BomRecipe recipe;

    /**
     * 折算实际用量 (公式 SCHEMA_DESIGN line 1340):
     * {@code actualQuantity = standardQuantity / (yieldRate / 100)}, HALF_UP scale 6.
     *
     * <p>{@link #yieldRate} 为 null 或 0 时透传 standardQuantity (无损耗).
     *
     * <p>#57: math delegated to {@link CostRollupUtil#calcActualQuantity}
     * (single source of truth shared with restaurant dish-cost). The {@code yieldRate}
     * (percent 0–100) is normalised to a scale-6 fraction BEFORE the call so the
     * rounding stays byte-identical to the historical two-step formula.
     */
    @Transient
    public BigDecimal calculateActualQuantity() {
        if (standardQuantity == null) {
            return null;
        }
        if (yieldRate == null || yieldRate.compareTo(BigDecimal.ZERO) == 0) {
            return standardQuantity;
        }
        BigDecimal fraction = yieldRate.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP);
        return CostRollupUtil.calcActualQuantity(standardQuantity, fraction);
    }

    /**
     * 单项成本 (公式: actualQuantity × unitPrice), HALF_UP scale 4.
     * unitPrice 为 null 时 (RBAC strip 后) 返 null (而非 0), 避免误导.
     *
     * <p>#57: math delegated to {@link CostRollupUtil#calcItemCost}.
     */
    @Transient
    @PriceSensitive
    public BigDecimal computeItemCost() {
        if (standardQuantity == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal factor = quantityToPriceFactor != null ? quantityToPriceFactor : BigDecimal.ONE;
        return CostRollupUtil.calcItemCost(calculateActualQuantity().multiply(factor), unitPrice);
    }
}

package com.cretas.aims.entity.foodsafety;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;

/**
 * GB 28050-2011 预包装食品营养标签生成结果 — Sprint 9 P2.B (2026-05-21).
 *
 * <p>法定: GB 28050-2011《食品安全国家标准 预包装食品营养标签通则》要求每个 SKU
 * 标注 "1+4" 强制营养素 (能量 + 蛋白质 + 脂肪 + 碳水化合物 + 钠) per serving.
 *
 * <p>生成算法 (NutritionLabelGenerateTool.doExecute):
 * <ol>
 *   <li>按 bomVersionId 加载 BomVersion + snapshot_json (items array)</li>
 *   <li>按 servingSize / outputQuantityPerUnit 算缩放系数</li>
 *   <li>对每 BomRecipeItem 按 ingredientCode (或 name fallback) 查 IngredientNutritionFact</li>
 *   <li>累加 each item.standardQuantity / 100 * per100gNutrient * scaling</li>
 *   <li>结果 quantize HALF_UP scale 2</li>
 * </ol>
 *
 * <p>状态机:
 * <pre>
 *   DRAFT ──── publish ───→ PUBLISHED ─── revoke ─→ REVOKED
 * </pre>
 *
 * <p>factory 隔离, factory_id NOT NULL. uniqueConstraint:
 * (factory_id, product_code, bom_version_id) — 同 SKU 同 BOM 版本只允许一个 DRAFT/PUBLISHED.
 *
 * <p>calculationDetails JSON 字段保存算法明细 (每 ingredient 贡献的营养值), 用于追溯
 * "为什么钠是 X mg" — GFSI / 食药监审计要求.
 *
 * @author Cretas Team / Sprint 9 P2.B
 * @since 2026-05-21
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Entity
@Table(name = "nutrition_labels",
        indexes = {
                @Index(name = "idx_nutrition_labels_factory_product",
                        columnList = "factory_id,product_code"),
                @Index(name = "idx_nutrition_labels_bom_version",
                        columnList = "bom_version_id"),
                @Index(name = "idx_nutrition_labels_status",
                        columnList = "factory_id,status")
        })
@Where(clause = "deleted_at IS NULL")
public class NutritionLabel extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    /** 产品编码 (业务 SKU, e.g. "QHJ-LZT-200G"). */
    @Column(name = "product_code", nullable = false, length = 100)
    private String productCode;

    /** 产品名称 (冗余 — 便于查询展示). */
    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    /**
     * BOM 版本 ID — Logical FK to {@code bom_versions.id} VARCHAR(191) UUID
     * (per Sprint 9 P1.1 finding MaterialBatch.id 走 String UUID 同样 pattern).
     * 不加硬 FK 避免和 BOM 模块迁移 coupling.
     */
    @Column(name = "bom_version_id", nullable = false, length = 191)
    private String bomVersionId;

    /** 每份 g (营养声称基准, GB 28050 §5.1.2 要求标注). */
    @Column(name = "serving_size", nullable = false, precision = 8, scale = 2)
    private BigDecimal servingSize;

    /** 每份能量 (kJ) — 强制. */
    @Column(name = "energy_per_serving", nullable = false, precision = 8, scale = 2)
    private BigDecimal energyPerServing;

    /** 每份蛋白质 (g) — 强制. */
    @Column(name = "protein_per_serving", nullable = false, precision = 8, scale = 2)
    private BigDecimal proteinPerServing;

    /** 每份脂肪 (g) — 强制. */
    @Column(name = "fat_per_serving", nullable = false, precision = 8, scale = 2)
    private BigDecimal fatPerServing;

    /** 每份碳水化合物 (g) — 强制. */
    @Column(name = "carbohydrate_per_serving", nullable = false, precision = 8, scale = 2)
    private BigDecimal carbohydratePerServing;

    /** 每份钠 (mg) — 强制. */
    @Column(name = "sodium_per_serving", nullable = false, precision = 8, scale = 2)
    private BigDecimal sodiumPerServing;

    /** 每份饱和脂肪 (g) — 推荐. */
    @Column(name = "saturated_fat_per_serving", precision = 8, scale = 2)
    private BigDecimal saturatedFatPerServing;

    /** 每份反式脂肪 (g) — 推荐. */
    @Column(name = "trans_fat_per_serving", precision = 8, scale = 2)
    private BigDecimal transFatPerServing;

    /** 每份糖 (g) — 推荐. */
    @Column(name = "sugar_per_serving", precision = 8, scale = 2)
    private BigDecimal sugarPerServing;

    /** 每份膳食纤维 (g) — 推荐. */
    @Column(name = "dietary_fiber_per_serving", precision = 8, scale = 2)
    private BigDecimal dietaryFiberPerServing;

    /**
     * 算法明细 JSON (每 ingredient 贡献的营养值 + 是否 match GB 食物成分表).
     * 审计追溯用 — "为什么钠是 X mg" 必须可回放.
     */
    @Column(name = "calculation_details", columnDefinition = "TEXT")
    private String calculationDetails;

    /** 生成此标签的操作员. */
    @Column(name = "generated_by_user_id", nullable = false)
    private Long generatedByUserId;

    /** 状态: DRAFT / PUBLISHED / REVOKED. */
    @Column(name = "status", nullable = false, length = 32)
    @Builder.Default
    private String status = "DRAFT";
}

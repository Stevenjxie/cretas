package com.cretas.aims.entity.bom;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.recipe.SeasoningLine;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;

/**
 * BOM 调料 ingredient (注射段 + 熟制段) — 折叠自 SP-A {@code recipe_ingredients}.
 *
 * <p>BOM 统管配方+锅序合并 (2026-06-24, decision A 真折叠): 调料明细从独立的
 * {@code product_recipes}/{@code recipe_ingredients} 子系统搬进 BOM, 挂在
 * {@link BomRecipe} (SKU 级, FK {@code recipe_id → bom_recipes.id}), 与原辅料
 * {@link BomRecipeItem} 同挂法. 版本化由 {@code bom_versions.snapshot_json} 承载.
 *
 * <p>字段 1:1 镜像 {@code RecipeIngredient} 以保证迁移 (U3) 与成本算法零回归 (U4).
 * implements {@link SeasoningLine} 让 {@code RecipeCostCalculator} 用同一算法读取.
 *
 * @since 2026-06-24
 */
@Entity
@Table(name = "bom_seasoning_items", indexes = {
    @Index(name = "idx_bsi_recipe",  columnList = "recipe_id, seq"),
    @Index(name = "idx_bsi_factory", columnList = "factory_id, recipe_id")
})
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Where(clause = "deleted_at IS NULL")
public class BomSeasoningItem extends BaseEntity implements SeasoningLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK -> bom_recipes.id (SKU 级). */
    @Column(name = "recipe_id", nullable = false, length = 191)
    private String recipeId;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    /** {@link SeasoningLine#getSection()}: INJECTION | COOKING. */
    @Column(name = "section", nullable = false, length = 20)
    private String section;

    /** 段内排序 (镜像 recipe_ingredients.seq). */
    @Column(name = "seq", nullable = false)
    @Builder.Default
    private Integer seq = 0;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /** 每 kg 原料用量 (g). */
    @Column(name = "dosage_per_kg_g", nullable = false, precision = 14, scale = 4)
    private BigDecimal dosagePerKgG;

    @Column(name = "price_source1", precision = 14, scale = 4)
    private BigDecimal priceSource1;

    @Column(name = "price_source2", precision = 14, scale = 4)
    private BigDecimal priceSource2;

    /** 老汤/高汤 = false (熟制段不计入调料). */
    @Column(name = "count_in_seasoning", nullable = false)
    @Builder.Default
    private Boolean countInSeasoning = true;

    @Column(name = "remark", length = 500)
    private String remark;

    /** Back-ref to parent recipe; insertable/updatable=false because recipeId column drives FK. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipe_id", insertable = false, updatable = false)
    @JsonIgnore
    private BomRecipe recipe;
}

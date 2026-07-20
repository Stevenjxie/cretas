package com.cretas.aims.entity.bom;

import com.cretas.aims.entity.BaseEntity;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;

/**
 * BOM 调料明细（注射段 + 熟制段）的唯一运行时模型。
 *
 * <p>明细挂在 {@link BomRecipe}（SKU 级，FK {@code recipe_id → bom_recipes.id}），
 * 与原辅料 {@link BomRecipeItem} 同挂法；版本化由 {@code bom_versions.snapshot_json} 承载。
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
public class BomSeasoningItem extends BaseEntity {

    public static final String SECTION_INJECTION = "INJECTION";
    public static final String SECTION_COOKING = "COOKING";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK -> bom_recipes.id (SKU 级). */
    @Column(name = "recipe_id", nullable = false, length = 191)
    private String recipeId;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    /** 关联物料档案；历史调料行允许为 null，新保存行必须由服务层校验。 */
    @Column(name = "material_type_id", length = 191)
    private String materialTypeId;

    /** {@link #SECTION_INJECTION} | {@link #SECTION_COOKING}. */
    @Column(name = "section", nullable = false, length = 20)
    private String section;

    /** 段内排序。 */
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

    /** FK -> work_processes.id (nullable, 迁移期兼容) — 该调料明细归属的工序. */
    @Column(name = "work_process_id", length = 50)
    private String workProcessId;

    /** Stable PROCESS Cell identity inside the BOM-pinned Workflow revision. */
    @Column(name = "workflow_process_node_id", length = 128)
    private String workflowProcessNodeId;

    @Column(name = "subsequent_pot_ratio", precision = 8, scale = 4)
    private BigDecimal subsequentPotRatio;

    /** Back-ref to parent recipe; insertable/updatable=false because recipeId column drives FK. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipe_id", insertable = false, updatable = false)
    @JsonIgnore
    private BomRecipe recipe;
}

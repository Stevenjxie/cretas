package com.cretas.aims.entity.bom;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Structured, version-local BOM substitution rule.
 *
 * <p>The rule points at exactly one parent row in the same BOM recipe. Recipe items cover RAW
 * and PACKAGING requirements; seasoning items cover process-scoped AUXILIARY requirements.
 * Scope fields are copied from the parent when the rule is created so a cloned/activated version
 * remains auditable even if master data is renamed later. The deprecated free-text
 * {@code BomRecipeItem.substituteGroup} is deliberately not migrated into this table.
 */
@Entity
@Table(name = "bom_item_substitutes", indexes = {
        @Index(name = "idx_bis_recipe", columnList = "factory_id,recipe_id,parent_kind"),
        @Index(name = "idx_bis_substitute_material", columnList = "factory_id,substitute_material_type_id")
})
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Where(clause = "deleted_at IS NULL")
public class BomItemSubstitute extends BaseEntity {

    public enum ParentKind {
        RECIPE_ITEM,
        SEASONING_ITEM
    }

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "recipe_id", nullable = false, length = 191)
    private String recipeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "parent_kind", nullable = false, length = 24)
    private ParentKind parentKind;

    @Column(name = "parent_recipe_item_id")
    private Long parentRecipeItemId;

    @Column(name = "parent_seasoning_item_id")
    private Long parentSeasoningItemId;

    @Column(name = "parent_material_type_id_snapshot", nullable = false, length = 191)
    private String parentMaterialTypeIdSnapshot;

    @Column(name = "parent_material_name_snapshot", nullable = false, length = 200)
    private String parentMaterialNameSnapshot;

    /** RAW / AUXILIARY / PACKAGING, inherited from the parent row. */
    @Column(name = "material_category_snapshot", nullable = false, length = 32)
    private String materialCategorySnapshot;

    /** Present only for process-scoped auxiliary/seasoning parents. */
    @Column(name = "work_process_id_snapshot", length = 50)
    private String workProcessIdSnapshot;

    /** Stable PROCESS Cell identity inside the BOM-pinned Workflow revision. */
    @Column(name = "workflow_process_node_id_snapshot", length = 128)
    private String workflowProcessNodeIdSnapshot;

    /** Present for packaging parents above the base selling level. */
    @Column(name = "packaging_spec_id_snapshot", length = 36)
    private String packagingSpecIdSnapshot;

    @Column(name = "packaging_role_snapshot", length = 64)
    private String packagingRoleSnapshot;

    @Column(name = "substitute_material_type_id", nullable = false, length = 191)
    private String substituteMaterialTypeId;

    @Column(name = "substitute_material_code_snapshot", length = 50)
    private String substituteMaterialCodeSnapshot;

    @Column(name = "substitute_material_name_snapshot", nullable = false, length = 200)
    private String substituteMaterialNameSnapshot;

    @Column(name = "parent_unit_snapshot", nullable = false, length = 20)
    private String parentUnitSnapshot;

    @Column(name = "substitute_unit_snapshot", nullable = false, length = 20)
    private String substituteUnitSnapshot;

    /** Quantity of substitute that is equivalent to one quantity unit of the parent requirement. */
    @Column(name = "conversion_factor", nullable = false, precision = 24, scale = 12)
    private BigDecimal conversionFactor;

    /** True only when the user explicitly supplied the conversion instead of the 1:1 default. */
    @Column(name = "conversion_explicit", nullable = false)
    private Boolean conversionExplicit;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}

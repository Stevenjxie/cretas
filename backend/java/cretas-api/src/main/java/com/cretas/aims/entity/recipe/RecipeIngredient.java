package com.cretas.aims.entity.recipe;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Where;
import java.math.BigDecimal;
import java.util.UUID;

@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "recipe_ingredients", indexes = {
        @Index(name = "idx_ringredient_recipe", columnList = "recipe_id")
})
@Where(clause = "deleted_at IS NULL")
public class RecipeIngredient extends BaseEntity {

    /** 注射段 / 熟制段 */
    public static final String SECTION_INJECTION = "INJECTION";
    public static final String SECTION_COOKING = "COOKING";

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) id = UUID.randomUUID().toString();
    }

    @Column(name = "recipe_id", nullable = false, length = 64)
    private String recipeId;

    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    @Column(name = "section", nullable = false, length = 20)
    private String section;

    @Column(name = "seq", nullable = false)
    private Integer seq;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /** 每kg原料用量(g) */
    @Column(name = "dosage_per_kg_g", nullable = false, precision = 14, scale = 4)
    private BigDecimal dosagePerKgG;

    @Column(name = "price_source1", precision = 14, scale = 4)
    private BigDecimal priceSource1;

    @Column(name = "price_source2", precision = 14, scale = 4)
    private BigDecimal priceSource2;

    /** 老汤/高汤 = false (不计入调料) */
    @Column(name = "count_in_seasoning", nullable = false)
    private Boolean countInSeasoning;

    @Column(name = "remark", length = 500)
    private String remark;
}

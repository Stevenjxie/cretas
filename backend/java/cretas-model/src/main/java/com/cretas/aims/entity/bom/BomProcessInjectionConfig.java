package com.cretas.aims.entity.bom;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;

/**
 * Absolute injection amount for one {@code recipe × work process} pair.
 *
 * <p>Cooking pot rules belong to individual {@link BomSeasoningItem} bindings.
 * This entity is deliberately injection-only so a process-level ratio cannot
 * reappear as a hidden fallback.
 */
@Entity(name = "BomProcessInjectionConfig")
@Table(
        name = "bom_process_injection_configs",
        indexes = {
                @Index(name = "idx_bpic_factory_recipe", columnList = "factory_id, recipe_id")
        }
)
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Where(clause = "deleted_at IS NULL")
public class BomProcessInjectionConfig extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "recipe_id", nullable = false, length = 191)
    private String recipeId;

    @Column(name = "work_process_id", nullable = false, length = 50)
    private String workProcessId;

    @Column(name = "injection_amount_kg", nullable = false, precision = 12, scale = 3)
    private BigDecimal injectionAmountKg;

    @Column(name = "notes", length = 500)
    private String notes;
}

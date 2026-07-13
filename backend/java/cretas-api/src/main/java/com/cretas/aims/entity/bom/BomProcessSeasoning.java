package com.cretas.aims.entity.bom;

import com.cretas.aims.entity.BaseEntity;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

import jakarta.persistence.*;
import java.math.BigDecimal;

/**
 * per-(recipe × 工序) 调料参数 — 挂在「熟制」「注射」等工序类别上的锅序/注射量配置.
 *
 * <p>与 {@link BomSeasoningItem}(调料 ingredient 明细) 是两张互补的表:
 * {@code BomSeasoningItem} 记录"用什么调料/用量多少", 本实体记录"这道工序整体的
 * 锅序比例 / 注射量"这类工序级参数, 不是逐条调料明细。
 *
 * @since 调料配方按工序 (2026-07-13)
 */
@Entity(name = "BomProcessSeasoning")
@Table(
    name = "bom_process_seasoning",
    indexes = {
        @Index(name = "idx_bps_factory_recipe", columnList = "factory_id, recipe_id")
    }
    // 唯一性由 Flyway partial unique index (uq_bps_recipe_wp WHERE deleted_at IS NULL) 保证 —
    // 不用 @UniqueConstraint (会在 ddl-auto=update 的 dev 环境生成普通全表唯一约束, 与软删全量替换冲突)。
)
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Where(clause = "deleted_at IS NULL")
public class BomProcessSeasoning extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    /** FK -> bom_recipes.id (SKU 级). */
    @Column(name = "recipe_id", nullable = false, length = 191)
    private String recipeId;

    /** FK -> work_processes.id — 挂载的工序 (通常是「熟制」「注射」类别). */
    @Column(name = "work_process_id", nullable = false, length = 50)
    private String workProcessId;

    /** 后续锅调料比例 (熟制工序适用). */
    @Column(name = "subsequent_pot_ratio", precision = 8, scale = 4)
    private BigDecimal subsequentPotRatio;

    /** 注射量 (kg, 注射工序适用). */
    @Column(name = "injection_amount_kg", precision = 12, scale = 3)
    private BigDecimal injectionAmountKg;

    @Column(name = "notes", length = 500)
    private String notes;
}

package com.cretas.aims.entity.recipe;

import com.cretas.aims.security.PriceSensitive;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 工序材料配方 (调料 BOM / 包材汇总).
 *
 * <p>每道工序可有 0..N 条配方 (按 recipe_type 区分调料/包材), 但通常每种类型最多 1 条 active。
 * unit_cost 是已摊分的单位成本, 直接用于逐道成本叠加:
 * <ul>
 *   <li>SEASONING → 元/kg 投入量 (本道 inputQuantity × unit_cost)</li>
 *   <li>PACKAGING → 元/盒 产出量 (本道 outputQuantity × unit_cost)</li>
 * </ul>
 *
 * <p><b>price fields</b>: {@code unit_cost} 是 {@link PriceSensitive} — 工人角色查看逐道报工时
 * 不应看到配方成本单价。items 里的 unit_price/line_cost 同样敏感。
 */
@Entity
@Table(name = "process_material_recipes", indexes = {
        @Index(name = "idx_pmr_factory_wp", columnList = "factory_id, work_process_id"),
        @Index(name = "idx_pmr_factory_active", columnList = "factory_id, is_active")
})
@Where(clause = "deleted_at IS NULL")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessMaterialRecipe {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    /** 关联工序 ID (VARCHAR, 不加 FK 到 entity-only 的 work_processes 表) */
    @Column(name = "work_process_id", nullable = false, length = 50)
    private String workProcessId;

    /** SEASONING = 调料 (元/kg投入), PACKAGING = 包材 (元/盒产出) */
    @Enumerated(EnumType.STRING)
    @Column(name = "recipe_type", nullable = false, length = 20)
    private RecipeType recipeType;

    /**
     * 已摊分的单位成本.
     * SEASONING: 元/kg 投入量; PACKAGING: 元/盒 产出量.
     *
     * <p>{@code @PriceSensitive}: 工人角色(warehouse_manager, operator 等)
     * 无 procurement:price:view 权限时, Jackson 序列化时此字段被置 null。
     */
    @PriceSensitive
    @Column(name = "unit_cost", nullable = false, precision = 14, scale = 4)
    private BigDecimal unitCost;

    /** 人读单位说明, 如 "元/kg投入" / "元/盒产出" */
    @Column(name = "cost_unit", nullable = false, length = 20)
    private String costUnit;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "remark", columnDefinition = "TEXT")
    private String remark;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /** 配方明细行 (审计用, 不参与成本计算) */
    @OneToMany(mappedBy = "recipe", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<ProcessMaterialRecipeItem> items;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum RecipeType {
        /** 调料: 元/kg 投入量 */
        SEASONING,
        /** 包材: 元/盒 产出量 */
        PACKAGING
    }
}

package com.cretas.aims.entity.recipe;

import com.cretas.aims.security.PriceSensitive;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 工序配方明细行 (审计用).
 *
 * <p>每行记录一种原材料的用量和行成本, {@code material_name} 是自由文本不 FK 到原材料主档,
 * 方便配方管理员直接录入而不必先建物料档案。
 *
 * <p><b>price fields</b>: {@code unit_price} / {@code line_cost} 均 {@link PriceSensitive},
 * 工人角色序列化时被 Jackson 置 null。
 */
@Entity
@Table(name = "process_material_recipe_items")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessMaterialRecipeItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属配方 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipe_id", nullable = false)
    private ProcessMaterialRecipe recipe;

    /** 原材料名称 (自由文本, 不 FK 到原材料主档) */
    @Column(name = "material_name", nullable = false, length = 100)
    private String materialName;

    /**
     * 采购单价 元/kg.
     * {@code @PriceSensitive}: 工人无权查看。
     */
    @PriceSensitive
    @Column(name = "unit_price", precision = 14, scale = 4)
    private BigDecimal unitPrice;

    /** 配方用量 */
    @Column(name = "quantity", precision = 14, scale = 4)
    private BigDecimal quantity;

    /** 用量单位 (g / kg / 个 / 张 等) */
    @Column(name = "quantity_unit", length = 20)
    private String quantityUnit;

    /**
     * 行成本 元.
     * {@code @PriceSensitive}: 工人无权查看。
     */
    @PriceSensitive
    @Column(name = "line_cost", precision = 14, scale = 4)
    private BigDecimal lineCost;

    @Column(name = "remark", length = 200)
    private String remark;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

package com.cretas.aims.dto.restaurant;

import com.cretas.aims.security.PriceSensitive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 菜品成本卡 (Dish Cost Card) — 单菜品逐料食材成本拆解 + 毛利率.
 *
 * <p>由 {@code DishCostCardService} 基于该菜品的 active {@code Recipe} 行 + 各食材
 * {@code RawMaterialType.unitPrice} + 菜品售价 ({@code ProductType.unitPrice}) 计算。
 *
 * <p><b>RBAC</b>: 成本/售价/毛利字段标 {@link PriceSensitive}, 由
 * {@code PriceFieldResponseAdvice} 在无价权角色 (e.g. warehouse_manager) 的响应里
 * 递归剥离为 {@code null}。前端凭 {@code null} 判断是否隐藏金额, 永不显示 ¥0 误导。
 *
 * @author Cretas Team
 * @since 2026-06-04 (feature #57 — dish cost card)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DishCostCardResponse {

    /** 菜品 ID (product_types.id). */
    private String productTypeId;

    /** 菜品名称. */
    private String productName;

    /** 计算份数 (默认 1). 成本与售价均按此份数缩放. */
    private Integer portions;

    /** 食材总成本 (折算用量 × 单价 之和). 任一食材缺价 → null (不误导为 ¥0). */
    @PriceSensitive
    private BigDecimal totalIngredientCost;

    /** 菜品售价 (来自 product_types.unit_price, 按份数缩放). */
    @PriceSensitive
    private BigDecimal sellPrice;

    /**
     * 毛利率 = (售价 - 食材成本) / 售价. 范围通常 0–1.
     * 售价缺失 / 为 0, 或成本缺失 → null。
     */
    @PriceSensitive
    private BigDecimal grossMargin;

    /** 是否有食材缺单价 (true → 总成本不可信, 前端需提示去配置原料单价). */
    private Boolean hasMissingPrices;

    /** 配方行数 (active recipe lines for the dish). 防呆 header 用. */
    private Integer recipeLineCount;

    /** 配方最近更新时间 (max of recipe updatedAt). 防呆 header 用. */
    private LocalDateTime recipeUpdatedAt;

    /** 计算时间. */
    private LocalDateTime computedAt;

    /** 逐料明细. */
    private List<IngredientCostLine> ingredients;

    /**
     * 单条食材成本行.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IngredientCostLine {

        /** 食材 ID (raw_material_types.id). */
        private String rawMaterialTypeId;

        /** 食材名称. */
        private String materialName;

        /** 标准用量 (每份, 已按份数缩放). */
        private BigDecimal standardQty;

        /** 折算实际用量 (考虑净料率, 已按份数缩放). */
        private BigDecimal actualQty;

        /** 计量单位. */
        private String unit;

        /** 净料率 (0–1 fraction). */
        private BigDecimal netYieldRate;

        /** 食材单价 (raw_material_types.unit_price). */
        @PriceSensitive
        private BigDecimal unitPrice;

        /** 单项成本 (actualQty × unitPrice). 单价缺失 → null. */
        @PriceSensitive
        private BigDecimal itemCost;
    }
}

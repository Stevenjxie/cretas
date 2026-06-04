package com.cretas.aims.service.restaurant;

import com.cretas.aims.dto.restaurant.DishCostCardResponse;

/**
 * 菜品成本卡服务 — 把某菜品的 active 配方 + 食材单价 + 售价滚动成
 * 逐料成本拆解 + 毛利率 ({@link DishCostCardResponse})。
 *
 * <p>成本数学全部委托 {@link com.cretas.aims.service.shared.CostRollupUtil},
 * 与工厂 BOM 共用同一套舍入 / null-guard 规则 (#57)。
 *
 * @author Cretas Team
 * @since 2026-06-04 (feature #57)
 */
public interface DishCostCardService {

    /**
     * 构建某菜品的成本卡.
     *
     * @param factoryId     工厂/餐厅 ID
     * @param productTypeId 菜品 ID (product_types.id)
     * @param portions      份数 (≥1); 成本与售价按此缩放
     * @return 成本卡 (含逐料明细 + 总成本 + 毛利率 + 缺价标记)
     * @throws com.cretas.aims.exception.ResourceNotFoundException 菜品不存在或无 active 配方
     */
    DishCostCardResponse getCostCard(String factoryId, String productTypeId, int portions);
}

package com.cretas.aims.service;

import com.cretas.aims.dto.bom.BomCostSummaryDTO;

import java.util.List;

/**
 * BOM 成本计算服务接口
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-13
 */
public interface BomService {

    // ============ Cost Calculation (成本计算) ============

    /**
     * 计算产品成本 —— 口径仅为「包材」，不是完整成本。
     *
     * <p>只读 {@code bom_recipe_items}，且只聚合 {@code materialCategory=PACKAGING} 的行:
     * 总成本 = SUM(包材行: 实际用量 / 出成率 * 单价)。
     *
     * <p>不包含: 原料（standard_quantity 是已废弃的历史脏数据，新行为 null）；
     * 辅料（真实辅料成本在 {@code bom_seasoning_items}，本方法不联查，故完全不体现在返回值中）；
     * 人工（要等结算：实际工时 × 时薪 ÷ 实际箱数）；均摊（要等成本分析）。
     * 人工/均摊在返回 DTO 中为 {@code null}（"此处不归集"），不是 0（"不要钱"）。
     *
     * <p>调用方不能把返回的 {@code totalCost} 当作产品完整成本使用（例如与批次实际成本
     * 直接比较差异率）——那是包材小计, 不是全成本。
     *
     * @param factoryId 工厂ID
     * @param productTypeId 产品类型ID
     * @return 成本汇总DTO（仅包材口径）
     */
    BomCostSummaryDTO calculateProductCost(String factoryId, String productTypeId);

    /**
     * 批量计算多个产品的成本
     *
     * @param factoryId 工厂ID
     * @param productTypeIds 产品类型ID列表
     * @return 成本汇总DTO列表
     */
    List<BomCostSummaryDTO> calculateProductCosts(String factoryId, List<String> productTypeIds);

    /**
     * 获取工厂下有BOM配置的产品类型ID列表
     *
     * @param factoryId 工厂ID
     * @return 产品类型ID列表
     */
    List<String> getProductTypesWithBom(String factoryId);
}

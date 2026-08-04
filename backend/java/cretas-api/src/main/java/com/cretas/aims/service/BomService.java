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
     * 计算产品的完整成本
     * 成本公式:
     * - 原料成本 = SUM(成品含量 / 出成率 * 单价)
     * - 人工成本 = SUM(工序单价 * 操作量)
     * - 均摊费用 = SUM(单价 * 分摊量)
     * - 总成本 = 原料成本 + 人工成本 + 均摊费用
     *
     * @param factoryId 工厂ID
     * @param productTypeId 产品类型ID
     * @return 成本汇总DTO
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

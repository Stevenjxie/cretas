package com.cretas.aims.service;

import com.cretas.aims.entity.bom.BomItem;
import com.cretas.aims.entity.bom.LaborCostConfig;
import com.cretas.aims.entity.bom.OverheadCostConfig;
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

    // ============ BOM Items (原辅料配方) ============

    /**
     * 获取产品的BOM项目列表
     *
     * @param factoryId 工厂ID
     * @param productTypeId 产品类型ID
     * @return BOM项目列表
     */
    List<BomItem> getBomItemsByProduct(String factoryId, String productTypeId);

    /**
     * 获取工厂的所有BOM项目
     *
     * @param factoryId 工厂ID
     * @return BOM项目列表
     */
    List<BomItem> getAllBomItems(String factoryId);

    /**
     * 保存BOM项目
     *
     * @param bomItem BOM项目
     * @return 保存后的BOM项目
     */
    BomItem saveBomItem(BomItem bomItem);

    /**
     * 批量保存BOM项目
     *
     * @param bomItems BOM项目列表
     * @return 保存后的BOM项目列表
     */
    List<BomItem> saveBomItems(List<BomItem> bomItems);

    /**
     * BOM 批量导入: 逐行校验 (materialTypeId 缺则按 materialName 模糊匹配原料类型),
     * <b>任一行失败则整批不入库</b> (原子), 返回每行 ok/error 结果。
     *
     * @param factoryId 工厂ID
     * @param request   {productTypeId, items[]}
     * @return 每行结果 + inserted/failed 计数
     */
    com.cretas.aims.dto.bom.BomBatchImportResult batchImportBomItems(
            String factoryId, com.cretas.aims.dto.bom.BomBatchImportRequest request);

    /**
     * 删除BOM项目
     *
     * @param id BOM项目ID
     */
    void deleteBomItem(Long id);

    // ============ Labor Cost (人工成本) ============

    /**
     * 获取产品的人工成本配置
     *
     * @param factoryId 工厂ID
     * @param productTypeId 产品类型ID
     * @return 人工成本配置列表
     */
    List<LaborCostConfig> getLaborCostsByProduct(String factoryId, String productTypeId);

    /**
     * 获取工厂的全局人工成本配置
     *
     * @param factoryId 工厂ID
     * @return 人工成本配置列表
     */
    List<LaborCostConfig> getGlobalLaborCosts(String factoryId);

    /**
     * 获取工厂的所有人工成本配置
     *
     * @param factoryId 工厂ID
     * @return 人工成本配置列表
     */
    List<LaborCostConfig> getAllLaborCosts(String factoryId);

    /**
     * 保存人工成本配置
     *
     * @param config 人工成本配置
     * @return 保存后的配置
     */
    LaborCostConfig saveLaborCost(LaborCostConfig config);

    /**
     * 删除人工成本配置
     *
     * @param id 配置ID
     */
    void deleteLaborCost(Long id);

    // ============ Overhead Cost (均摊费用) ============

    /**
     * 获取工厂的均摊费用配置
     *
     * @param factoryId 工厂ID
     * @return 均摊费用配置列表
     */
    List<OverheadCostConfig> getOverheadCosts(String factoryId);

    /**
     * 获取工厂启用的均摊费用配置
     *
     * @param factoryId 工厂ID
     * @return 均摊费用配置列表
     */
    List<OverheadCostConfig> getActiveOverheadCosts(String factoryId);

    /**
     * 保存均摊费用配置
     *
     * @param config 均摊费用配置
     * @return 保存后的配置
     */
    OverheadCostConfig saveOverheadCost(OverheadCostConfig config);

    /**
     * 部分更新均摊费用配置 (PUT semantics: load-existing → merge non-null fields → save).
     *
     * <p>R5 audit §3 BUG#1 (T-R5-3, 2026-05-12): previously the PUT controller did
     * a blind {@code repo.save(body)}, dropping audit columns (createdAt, version,
     * deletedAt) to NULL whenever the client payload omitted them. Now we
     * select-then-merge so any DB-only or audit field stays intact.
     *
     * @param factoryId 工厂ID (path var, tenant scope)
     * @param id        费用配置ID (path var)
     * @param body      部分字段更新; null 字段表示"保持当前值"
     * @return 合并并保存后的配置
     * @throws com.cretas.aims.exception.EntityNotFoundException 当记录不存在
     * @throws com.cretas.aims.exception.BusinessException       当 factoryId 跨租户
     */
    OverheadCostConfig updateOverheadCost(String factoryId, Long id, OverheadCostConfig body);

    /**
     * 删除均摊费用配置
     *
     * @param id 配置ID
     */
    void deleteOverheadCost(Long id);

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

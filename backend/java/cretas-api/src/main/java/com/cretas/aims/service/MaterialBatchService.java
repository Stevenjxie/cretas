package com.cretas.aims.service;

import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.material.ConvertToFrozenRequest;
import com.cretas.aims.dto.material.UndoFrozenRequest;
import com.cretas.aims.dto.material.CreateMaterialBatchRequest;
import com.cretas.aims.dto.material.UpdateMaterialBatchRequest;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.dto.material.MaterialStockSummaryDTO;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
/**
 * 原材料批次服务接口
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
public interface MaterialBatchService {
    /**
     * 创建原材料批次（入库）
     */
    MaterialBatchDTO createMaterialBatch(String factoryId, CreateMaterialBatchRequest request, Long userId);
     /**
     * 更新原材料批次 (无角色守卫重载 — 内部/AI-tool 调用路径, 写操作另由 W0 WriteGuard 把关).
      */
    MaterialBatchDTO updateMaterialBatch(String factoryId, String batchId, UpdateMaterialBatchRequest request);
     /**
     * 更新原材料批次 (角色守卫重载 — HTTP 入口路径).
     *
     * <p>W4 红线 (张权 F006 仓管员): 纯操作员 (warehouse_worker / operator) 无库存自主权,
     * 不能经 PUT 修改入库量 (receiptQuantity)、单价、重量、总价值 等影响台账/出成率分母/成本的字段。
     * 这类库存量变更必须走盘点 (Stocktake) / 调整 (MaterialBatchAdjustment) 审批流。
     * 非库存字段 (storageLocation/notes/expireDate/qualityCertificate 等) 仓管可改。
     *
     * <p>默认实现忽略 callerRole 委托无守卫重载 (向后兼容现有 implementors/测试桩);
     * 生产 {@code MaterialBatchServiceImpl} override 本方法落地角色守卫。
     *
     * @param callerRole 调用者角色码 (小写, 见 FactoryUserRole)。null = 跳过角色守卫 (内部调用)。
      */
    default MaterialBatchDTO updateMaterialBatch(String factoryId, String batchId,
                                                 UpdateMaterialBatchRequest request, String callerRole) {
        return updateMaterialBatch(factoryId, batchId, request);
    }
     /**
     * 删除原材料批次 (无角色守卫重载 — 内部/AI-tool 调用路径).
      */
    void deleteMaterialBatch(String factoryId, String batchId);
     /**
     * 删除原材料批次 (角色守卫重载 — HTTP 入口路径).
     *
     * <p>W4 红线: 删除批次 = 无单据移除库存。纯操作员不可直接删, 需管理员 (factory_super_admin /
     * warehouse_manager / 平台管理员)。误录入批次的删除走管理员路径。
     *
     * <p>默认实现忽略 callerRole 委托无守卫重载; 生产 impl override 落地角色守卫。
     *
     * @param callerRole 调用者角色码 (小写)。null = 跳过角色守卫 (内部调用)。
      */
    default void deleteMaterialBatch(String factoryId, String batchId, String callerRole) {
        deleteMaterialBatch(factoryId, batchId);
    }
     /**
     * 获取原材料批次详情
      */
    MaterialBatchDTO getMaterialBatchById(String factoryId, String batchId);
     /**
     * 获取原材料批次列表（分页）
      */
    PageResponse<MaterialBatchDTO> getMaterialBatchList(String factoryId, PageRequest pageRequest);
     /**
     * 根据状态获取批次
      */
    List<MaterialBatchDTO> getMaterialBatchesByStatus(String factoryId, MaterialBatchStatus status);

    /**
     * 按状态获取批次，可选 BOM 过滤 (防呆: 操作员只看产品所需原料).
     *
     * <p>当 {@code productTypeId} 非空时，查找该产品的 ACTIVE BOM 的 material_type_id 集合，
     * 仅返回那些原料类型的批次。
     *
     * <p><b>FALLBACK 铁律</b>: 若产品无 ACTIVE BOM 或 BOM 无明细行，
     * 回退返回全部 AVAILABLE 批次 (与无 productTypeId 行为一致)，绝不返回空列表阻断操作员。
     *
     * @param factoryId     工厂 ID
     * @param status        批次状态
     * @param productTypeId 产品类型 ID (可选, null = 不过滤)
     */
    List<MaterialBatchDTO> getMaterialBatchesByStatus(String factoryId, MaterialBatchStatus status,
                                                      String productTypeId);

    default List<MaterialBatchDTO> getMaterialBatchesByStatus(String factoryId, MaterialBatchStatus status,
                                                              String productTypeId, String warehouseId) {
        return getMaterialBatchesByStatus(factoryId, status, productTypeId);
    }

     /**
     * 获取在制品 (WIP) 批次列表 — 状态 = PRODUCING_RESERVED, 当前工厂.
     * Sprint 4 Wave 2 M-WIP-1: 关联到进行中的生产批次的物料批次。
      */
    List<MaterialBatchDTO> getWipBatches(String factoryId);
     /**
     * 获取全部工厂的在制品 (WIP) 批次列表 — admin scope, 跨工厂.
     * Sprint 4 Wave 2 M-WIP-1: 平台超管 / 系统管理员的全局 WIP 视图。
      */
    List<MaterialBatchDTO> getAllWipBatches();
     /**
     * 获取可用批次（FIFO）
      */
    List<MaterialBatchDTO> getAvailableBatchesFIFO(String factoryId, String materialTypeId);
     /**
     * 获取即将过期的批次
      */
    List<MaterialBatchDTO> getExpiringBatches(String factoryId, Integer warningDays);
     /**
     * 获取已过期的批次
      */
    List<MaterialBatchDTO> getExpiredBatches(String factoryId);
     /**
     * 根据供应商获取批次
      */
    List<MaterialBatchDTO> getMaterialBatchesBySupplier(String factoryId, String supplierId);
     /**
     * 按 delta 调整批次数量 (newQuantity = current + adjustmentQuantity).
     *
     * <p>R5 audit §3 BUG#2 (T-R5-4, 2026-05-12): renamed from {@code adjustBatchQuantity}
     * to make the delta semantic explicit at the API boundary. Callers wanting
     * to <em>set</em> a batch to an absolute quantity must use the 5-arg
     * {@link #adjustBatchQuantity(String, String, BigDecimal, String, Long)}
     * overload (ABSOLUTE math).
      */
    MaterialBatchDTO applyBatchQuantityDelta(String factoryId, String batchId, BigDecimal adjustmentQuantity, String reason);
     /**
     * 标记批次过期
      */
    void markBatchAsExpired(String factoryId, String batchId);
     /**
     * 标记批次用完
      */
    void markBatchAsUsedUp(String factoryId, String batchId);
     /**
     * 预留批次数量
      */
    void reserveBatchQuantity(String factoryId, String batchId, BigDecimal quantity);
     /**
     * 释放预留数量
      */
    void releaseBatchQuantity(String factoryId, String batchId, BigDecimal quantity);
     /**
     * 使用批次数量
      */
    void useBatchQuantity(String factoryId, String batchId, BigDecimal quantity);
     /**
     * 计算库存总值
      */
    BigDecimal calculateInventoryValue(String factoryId);
     /**
     * 按原材料类型统计库存
      */
    Map<String, BigDecimal> getInventoryByMaterialType(String factoryId);
     /**
     * 获取低库存预警
      */
    List<Map<String, Object>> getLowStockWarnings(String factoryId);
     /**
     * 批量入库
      */
    List<MaterialBatchDTO> batchCreateMaterialBatches(String factoryId, List<CreateMaterialBatchRequest> requests, Long userId);
     /**
     * 导出库存报表 (Excel).
     *
     * @deprecated callers must pass {@code maskPrice} explicitly (RBAC). Defaults to admin (no mask).
     *             Kept for binary-compat with existing internal callers.
      */
    @Deprecated
    default byte[] exportInventoryReport(String factoryId) {
        return exportInventoryReport(factoryId, null, null, false);
    }
     /**
     * 获取批次使用记录
      */
    List<Map<String, Object>> getBatchUsageHistory(String factoryId, String batchId);
     /**
     * 检查批次号是否存在
      */
    boolean checkBatchNumberExists(String batchNumber);
     /**
     * 自动检查并更新过期批次
      */
    void autoCheckAndUpdateExpiredBatches();
     /**
     * 导出库存报表（带日期范围）.
     *
     * @deprecated callers must pass {@code maskPrice} explicitly (RBAC). Defaults to admin (no mask).
      */
    @Deprecated
    default byte[] exportInventoryReport(String factoryId, LocalDate startDate, LocalDate endDate) {
        return exportInventoryReport(factoryId, startDate, endDate, false);
    }

    /**
     * 导出库存报表 (Excel) with RBAC price-mask flag.
     *
     * <p>RBAC defense-in-depth (PR P0-C sweep, 2026-05-12): {@code maskPrice}=true 时
     * 采购单价 / 库存价值 列以 "—" 占位, 通过 {@link com.cretas.aims.dto.material.MaterialBatchMaskedExportDTO}
     * 渲染.
     *
     * @param factoryId  工厂 ID
     * @param startDate  起始日期 (可选, null = 不限)
     * @param endDate    结束日期 (可选, null = 不限)
     * @param maskPrice  {@code true} → 采购单价 / 库存价值 显示 "—"; {@code false} → 真实数值
     */
    byte[] exportInventoryReport(String factoryId, LocalDate startDate, LocalDate endDate, boolean maskPrice);
     /**
     * 按材料类型获取批次
      */
    List<MaterialBatchDTO> getMaterialBatchesByType(String factoryId, String materialTypeId);
     /**
     * 获取FIFO批次（先进先出）
      */
    List<MaterialBatchDTO> getFIFOBatches(String factoryId, String materialTypeId, BigDecimal requiredQuantity);

    /**
     * 获取FEFO批次（先到期先出，食品行业合规）
     */
    List<MaterialBatchDTO> getFEFOBatches(String factoryId, String materialTypeId, BigDecimal requiredQuantity);

     /**
     * 使用批次材料
     *
     * @param operatorId 操作人用户ID, 写入 MaterialConsumption.recordedBy (NOT NULL + FK users)。
      */
    MaterialBatchDTO useBatchMaterial(String factoryId, String batchId, BigDecimal quantity, String productionPlanId, Long operatorId);
     /**
     * 设置批次数量到绝对值 (newQuantity = param, NOT current + param).
     *
     * <p>R5 audit §3 BUG#2 (T-R5-4, 2026-05-12): this is the ABSOLUTE overload —
     * the param is the desired post-update quantity, not a delta. For delta math
     * (e.g. "consume 3 kg" / "add 5 kg" / "clear via -current"), call
     * {@link #applyBatchQuantityDelta(String, String, BigDecimal, String)}.
      */
    MaterialBatchDTO adjustBatchQuantity(String factoryId, String batchId, BigDecimal newQuantity, String reason, Long adjustedBy);
     /**
     * 更新批次状态
      */
    MaterialBatchDTO updateBatchStatus(String factoryId, String batchId, MaterialBatchStatus status);
     /**
     * 预留批次材料
      */
    void reserveBatchMaterial(String factoryId, String batchId, BigDecimal quantity, String productionPlanId);
     /**
     * 释放预留材料
      */
    void releaseBatchReservation(String factoryId, String batchId, BigDecimal quantity, String productionPlanId);
     /**
     * 消耗批次材料（从预留中扣减）
     *
     * @param operatorId 操作人用户ID, 写入 MaterialConsumption.recordedBy (NOT NULL + FK users)。
      */
    void consumeBatchMaterial(String factoryId, String batchId, BigDecimal quantity, String productionPlanId, Long operatorId);
     /**
     * 获取库存统计
      */
    Map<String, Object> getInventoryStatistics(String factoryId);
     /**
     * 获取库存价值
      */
    BigDecimal getInventoryValuation(String factoryId);
     /**
     * 工厂级原料总库存汇总 (按物料聚合, 跨所有仓库) — F006 六膳门 "总库存查询" 页。
     *
     * <p>每个原料类型一行: 把该物料在所有仓库的所有在库批次的当前剩余量 / 价值 汇总。
     * 仅统计在库批次 (status NOT IN DEPLETED/USED_UP/EXPIRED/SCRAPPED/DEFECTIVE 且剩余量 &gt; 0)。
     * avgUnitPrice = totalValue/totalQuantity (除零保护 → null)。无数据返回空列表。</p>
      */
    List<MaterialStockSummaryDTO> listStockSummary(String factoryId);
     /**
     * 处理过期批次
      */
    int handleExpiredBatches(String factoryId);

    /**
     * 转冻品
     * 将原材料批次从鲜品转换为冻品状态
     *
     * @param factoryId 工厂ID
     * @param batchId 批次ID
     * @param request 转换请求参数
     * @return 转换后的批次信息
     * @since 2025-11-20
     */
    MaterialBatchDTO convertToFrozen(String factoryId, String batchId, ConvertToFrozenRequest request);

    /**
     * 撤销转冻品操作
     *
     * 业务规则：
     * 1. 批次当前状态必须是FROZEN
     * 2. 转换时间必须在10分钟内
     * 3. 操作人必须是原转换操作人或管理员
     *
     * @param factoryId 工厂ID
     * @param batchId 批次ID
     * @param request 撤销请求参数
     * @return 撤销后的批次信息
     * @since 2025-11-20
     */
    MaterialBatchDTO undoFrozen(String factoryId, String batchId, UndoFrozenRequest request);

    /**
     * 重算并更新某原料类型的移动平均价
     *
     * 公式: newAvg = (existingQty × currentAvg + receiptQty × receiptPrice) / (existingQty + receiptQty)
     *
     * 入库链路若不走 createMaterialBatch (例如 PurchaseService.confirmReceive 直接 new MaterialBatch),
     * 必须显式调用本方法, 否则三价对比的"移动均价"列将永远为 null。
     *
     * @param materialTypeId 原料类型ID
     * @param receiptQty 本次入库数量
     * @param receiptPrice 本次入库单价
     * @param newBatchId 本次新建的批次ID, 用于在汇总现有量时把自己排除
     */
    void recalculateMovingAvgPrice(String materialTypeId, BigDecimal receiptQty,
                                   BigDecimal receiptPrice, String newBatchId);
}

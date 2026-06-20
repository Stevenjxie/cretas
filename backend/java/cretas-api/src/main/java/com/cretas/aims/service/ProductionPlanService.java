package com.cretas.aims.service;

import com.cretas.aims.dto.common.ImportResult;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.production.CreateProductionPlanRequest;
import com.cretas.aims.dto.production.ProductionPlanDTO;
import com.cretas.aims.dto.production.ProductionPlanMaterialAdvisoryDTO;
import com.cretas.aims.dto.production.ProductionSettlementRequest;
import com.cretas.aims.dto.production.ProductionSettlementResponse;
import com.cretas.aims.dto.production.ProductionTransitClearingRequest;
import com.cretas.aims.dto.production.ProductionWarehouseReceiptRequest;
import com.cretas.aims.dto.production.ProductionWarehouseReceiptResponse;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
/**
 * 生产计划服务接口
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
public interface ProductionPlanService {
    /**
     * 创建生产计划
     */
    ProductionPlanDTO createProductionPlan(String factoryId, CreateProductionPlanRequest request, Long userId);

    /**
     * 获取工厂级"免工序报工默认值" (Fable 审计修复 2026-06-11 — 问题1).
     *
     * <p>web 新建计划对话框初始化"免工序报工"开关时调用: F006 返 true (默认勾选两点),
     * 其他工厂返 false (默认不勾, 逐道)。无 settings 行 → false (安全默认)。
     */
    boolean getSkipProcessReportingDefault(String factoryId);
     /**
     * 创建草稿态 (PREPARED) 生产计划 — M-PREP-1, Sprint 4 W2.
     * 与 createProductionPlan 相同, 但 status 初始化为 PREPARED 而非 PENDING。
     * 用于先创建后预览物料短缺, 满足后再 commitDraft 提交到 PENDING。
      */
    ProductionPlanDTO createDraftProductionPlan(String factoryId, CreateProductionPlanRequest request, Long userId);
     /**
     * 提交草稿态生产计划 — M-PREP-1, Sprint 4 W2.
     * 将 PREPARED 状态的计划转换为 PENDING (正式提交)。
     * 仅允许 PREPARED → PENDING, 其他状态拒绝。
      */
    ProductionPlanDTO commitDraftProductionPlan(String factoryId, String planId);
     /**
     * 获取交货预警列表 (M-DELIVERY-WARN-1, Sprint 4 W2).
     * 返回 expectedCompletionDate &lt; today + windowDays 且状态非 COMPLETED/CANCELLED 的生产计划,
     * 按 warnLevel 分级 (OVERDUE / URGENT / WARN / NORMAL)。
     *
     * @param factoryId  工厂 ID
     * @param windowDays 预警窗口天数 (默认 7d, 即 expectedCompletionDate &lt; today + 7d)
      */
    java.util.List<com.cretas.aims.dto.production.DeliveryWarnDTO> getDeliveryWarnings(String factoryId, int windowDays);
     /**
     * 更新生产计划
      */
    ProductionPlanDTO updateProductionPlan(String factoryId, String planId, CreateProductionPlanRequest request);
     /**
     * 删除生产计划
      */
    void deleteProductionPlan(String factoryId, String planId);
     /**
     * 获取生产计划详情
      */
    ProductionPlanDTO getProductionPlanById(String factoryId, String planId);
     /**
     * 复制生产计划 — 基于现有计划创建新草稿 (#860 follow-up).
     *
     * <p>复制内容: productTypeId/plannedQuantity/plannedDate/expectedCompletionDate/planType/
     * customerOrderNumber/priority/notes/estimated*Cost/suggestedProductionLineId/estimatedWorkers/
     * assignedSupervisorId/sourceType/sourceOrderId/sourceOrderItemId/sourceCustomerName/processName/
     * batchDate.
     *
     * <p>不复制 (重置或重新生成): id / planNumber (重新生成) / status (PENDING) / createdBy (current user)
     * / actualQuantity / actual*Cost / approval* / locked* / current/forecast/probability fields
     * / vflag / startTime / endTime / batchUsages / materialConsumptions.
     *
     * <p>注: 此 API 直接 save 而非走 createProductionPlan, 跳过 Canvas validation +
     * SO source validation + auto scheduling (复制场景假设源订单已通过这些检查).
     *
     * @throws com.cretas.aims.exception.ResourceNotFoundException 源计划不存在
     * @throws com.cretas.aims.exception.BusinessException 403 跨工厂访问
      */
    ProductionPlanDTO copyProductionPlan(String factoryId, String sourcePlanId, Long userId);
     /**
     * 获取生产计划列表（分页）
      */
    PageResponse<ProductionPlanDTO> getProductionPlanList(String factoryId, PageRequest pageRequest);
     /**
     * 按状态获取生产计划
      */
    List<ProductionPlanDTO> getProductionPlansByStatus(String factoryId, ProductionPlanStatus status);
     /**
     * 按日期范围获取生产计划
      */
    List<ProductionPlanDTO> getProductionPlansByDateRange(String factoryId, LocalDate startDate, LocalDate endDate);
     /**
     * 获取今日生产计划
      */
    List<ProductionPlanDTO> getTodayProductionPlans(String factoryId);

    /**
     * 获取生产计划的原料库存预警。只读 advisory, 不阻断生产流转。
     */
    ProductionPlanMaterialAdvisoryDTO getMaterialAdvisory(String factoryId, String planId);

     /**
    * 开始生产
     */
    ProductionPlanDTO startProduction(String factoryId, String planId);
     /**
     * 完成生产
      */
    ProductionPlanDTO completeProduction(String factoryId, String planId, BigDecimal actualQuantity);

    ProductionSettlementResponse settleProduction(String factoryId, String planId,
                                                  ProductionSettlementRequest request, Long settledBy);

    ProductionSettlementResponse getProductionSettlement(String factoryId, String planId);

    ProductionWarehouseReceiptResponse confirmWarehouseReceipt(String factoryId, String planId,
                                                               ProductionWarehouseReceiptRequest request,
                                                               Long receivedBy);

    ProductionWarehouseReceiptResponse clearProductionTransitLedger(String factoryId, String planId,
                                                                    ProductionTransitClearingRequest request,
                                                                    Long clearedBy);
     /**
     * 取消生产计划
      */
    void cancelProductionPlan(String factoryId, String planId, String reason);

    /**
     * @deprecated 计划级 PRODUCTION_REVERSAL 审批流已废弃（结构性死路：workflow 引擎从不回调
     * {@link #executeCancelApproved}，即使回调也只改状态、不恢复库存）。客户需求（六扇门 6.09
     * requirements-catalog §C「工单撤回」）是<b>整单撤回</b>（工单/批次级，非工序级），由
     * {@link com.cretas.aims.service.reversal.ReportReversalService} 实现（批次级整单撤回 +
     * 审批 + 无数据直撤 + 角色权限 + G1 下游消费/G2 成品出货/G3 幂等 fail-closed 守卫 + 库存恢复）。
     * <p>本方法现抛 409 引导用户改走批次级整单撤回，无前端调用方。
     */
    @Deprecated
    String requestCancelWithApproval(String factoryId, String planId, String reason, Long userId);

    /**
     * @deprecated 配套 {@link #requestCancelWithApproval} 一并废弃（workflow 回调零调用方）。
     * canonical 撤回是 {@link com.cretas.aims.service.reversal.ReportReversalService} 的批次级整单撤回。
     */
    @Deprecated
    void executeCancelApproved(String planId);
     /**
     * 锁定生产计划 (Issue #759, 2026-05-17). 锁定后不可编辑数量/日期/取消.
     */
    ProductionPlanDTO lockProductionPlan(String factoryId, String planId, String reason, Long userId);
     /**
     * 解锁生产计划 (Issue #759, 2026-05-17).
     */
    ProductionPlanDTO unlockProductionPlan(String factoryId, String planId, Long userId);
     /**
     * 暂停生产
      */
    ProductionPlanDTO pauseProduction(String factoryId, String planId);
     /**
     * 恢复生产
      */
    ProductionPlanDTO resumeProduction(String factoryId, String planId);
     /**
     * 更新实际成本
      */
    ProductionPlanDTO updateActualCosts(String factoryId, String planId,
                                        BigDecimal materialCost,
                                        BigDecimal laborCost,
                                        BigDecimal equipmentCost,
                                        BigDecimal otherCost);
     /**
     * 分配原材料批次
      */
    void assignMaterialBatches(String factoryId, String planId, List<String> batchIds);
     /**
     * 记录材料消耗
      */
    void recordMaterialConsumption(String factoryId, String planId, String batchId, BigDecimal quantity, Long operatorId);
     /**
     * 获取生产计划统计
      */
    Map<String, Object> getProductionStatistics(String factoryId, LocalDate startDate, LocalDate endDate);
     /**
     * 获取需要执行的计划
      */
    List<ProductionPlanDTO> getPendingPlansToExecute(String factoryId);
     /**
     * 批量创建生产计划
      */
    List<ProductionPlanDTO> batchCreateProductionPlans(String factoryId, List<CreateProductionPlanRequest> requests, Long userId);
     /**
     * 导出生产计划 (Excel).
     *
     * <p>RBAC defense-in-depth (PR P0-C sweep, 2026-05-12): {@code maskPrice} 参数
     * 已 wired through 但目前 {@code ProductionPlanImportDTO} 不含 cost/price 列 (产品/
     * 数量/日期/优先级/产线/工人数/主管/订单号/备注), 故该参数当前 no-op. 当未来 export 加上
     * actualMaterialCost / laborCost / equipmentCost / otherCost / totalCost 等列时,
     * 该参数即可用于在 maskPrice=true 时把这些列替换为 "—". 见 PR #450 sweep matrix 行 9.
     *
     * @param factoryId  工厂 ID
     * @param startDate  起始日期
     * @param endDate    结束日期
     * @param maskPrice  {@code true} → mask cost columns (future); {@code false} → real values
     */
    byte[] exportProductionPlans(String factoryId, LocalDate startDate, LocalDate endDate,
                                 boolean maskPrice);

    /** @deprecated callers must pass {@code maskPrice} explicitly (RBAC). Defaults to admin (no mask). */
    @Deprecated
    default byte[] exportProductionPlans(String factoryId, LocalDate startDate, LocalDate endDate) {
        return exportProductionPlans(factoryId, startDate, endDate, false);
    }
     /**
     * Excel批量导入生产计划
      */
    ImportResult<ProductionPlanDTO> importProductionPlansFromExcel(String factoryId, InputStream inputStream, Long userId);
     /**
     * 生成生产计划导入模板
      */
    byte[] generateImportTemplate();

    /**
     * 从生产计划创建生产批次（计划→执行转换）
     * 映射：suggestedProductionLineId→equipmentId, estimatedWorkers→workerCount, assignedSupervisorId→supervisorId
     */
    ProductionBatch createBatchFromPlan(String factoryId, String planId);

    /**
     * SP2 二次加工: 基于 WIP 半成品创建二次加工计划。
     *
     * <p>区别于普通计划:
     * <ul>
     *   <li>planSourceType = "SECONDARY"</li>
     *   <li>secondarySourceWipId = wipId (原料来源半成品库存行)</li>
     *   <li>startProduction 时自动调用 WipInventoryService.deductForSecondaryPlan 扣减 WIP 库存</li>
     * </ul>
     *
     * @param factoryId     工厂 ID
     * @param wipId         来源半成品库存 ID (SemiFinishedInventory.id)
     * @param quantity      计划加工数量 (不超过 WIP availableQuantity)
     * @param productTypeId 目标成品类型 ID
     * @param plannedDate   计划日期
     * @param submittedBy   提交人 userId
     * @since SP2 (2026-06-10, feat/liushanmen-sp2-reversal)
     */
    ProductionPlanDTO createSecondaryPlan(String factoryId, Long wipId, java.math.BigDecimal quantity,
                                          String productTypeId, java.time.LocalDate plannedDate,
                                          Long submittedBy);

    /**
     * SP5: 将 entity 转为 DTO (供 controller 双向检索端点使用, 避免循环依赖).
     *
     * @param plan entity (已加载)
     * @return DTO with conversion info (含产品名称等关联字段)
     */
    ProductionPlanDTO toPlanDTO(com.cretas.aims.entity.ProductionPlan plan);
}

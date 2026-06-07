package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.inventory.CreateDeliveryRequest;
import com.cretas.aims.dto.inventory.CreateSalesOrderRequest;
import com.cretas.aims.dto.inventory.UpdateSalesOrderRequest;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.entity.inventory.SalesOrder;

import java.util.List;
import java.util.Map;

public interface SalesService {

    // ==================== 销售订单 ====================

    SalesOrder createSalesOrder(String factoryId, CreateSalesOrderRequest request, Long userId);

    SalesOrder getSalesOrderById(String factoryId, String orderId);

    PageResponse<SalesOrder> getSalesOrders(String factoryId, int page, int size);

    /** Bug G fix: keyword search overload (qa-prompt v2.3 Rule 12.1) */
    PageResponse<SalesOrder> getSalesOrders(String factoryId, String keyword, int page, int size);

    PageResponse<SalesOrder> getSalesOrdersByStatus(String factoryId, SalesOrderStatus status, int page, int size);

    SalesOrder confirmOrder(String factoryId, String orderId);

    /** 提交财务审核: CONFIRMED -> PENDING_FINANCE_REVIEW */
    SalesOrder submitForFinanceReview(String factoryId, String orderId);

    /** 财务审核通过: PENDING_FINANCE_REVIEW -> FINANCE_APPROVED, 触发供应链联动 */
    SalesOrder financeApproveOrder(String factoryId, String orderId, String notes, Long reviewerId);

    /**
     * 六扇门 V1 §2.2 (audit fix 2026-04-26 #6): finance approve with optional
     * estimatedCost. If provided, persists to sales_orders.estimated_cost and
     * auto-computes estimated_profit = totalAmount - estimatedCost.
     */
    SalesOrder financeApproveOrder(String factoryId, String orderId, String notes,
                                    java.math.BigDecimal estimatedCost, Long reviewerId);

    /** 财务审核驳回: PENDING_FINANCE_REVIEW -> FINANCE_REJECTED */
    SalesOrder financeRejectOrder(String factoryId, String orderId, String reason, Long reviewerId);

    /**
     * Sprint4-H F-AR-1: 财务成本核算视图 — 拉 BOM 标准成本 + 当前预估成本 +
     * (订单完成后) 实际生产成本, 自动计算预估利润 vs 实际利润对比.
     */
    com.cretas.aims.dto.inventory.FinanceCostBreakdown getOrderCostBreakdown(String factoryId, String orderId);

    /**
     * B3 售价趋势 — 财审辅助决策.
     *
     * <p>返回该工厂该产品最近 N 笔成交销售订单的单价明细（非草稿/取消状态），
     * 供财务审核人员快速判断本单售价是否偏离历史水平。
     *
     * @param factoryId     工厂 ID
     * @param productTypeId 产品类型 ID
     * @param limit         最多返回条数（建议 8–12）
     * @return 按下单日期倒序的价格趋势 DTO 列表
     */
    java.util.List<com.cretas.aims.dto.inventory.SalesPriceTrendDTO> getProductPriceTrend(
            String factoryId, String productTypeId, int limit);

    SalesOrder updateSalesOrder(String factoryId, String orderId, UpdateSalesOrderRequest request);

    SalesOrder cancelOrder(String factoryId, String orderId);

    /**
     * 复制销售订单 — 基于现有订单创建新草稿 (#860 follow-up).
     *
     * <p>复制内容: 客户/订单日期(=今天)/要求交货日期/收货地址/折扣/备注/业务员/运费/extraFees/quoteId/
     * defaultTaxRate/defaultInvoiceType/items (产品/数量/单位/单价/折扣率/税率/规格/箱数/备注/sourceWarehouseCode).
     *
     * <p>不复制 (重置或重新生成): id / orderNumber (重新生成) / status (DRAFT) / createdBy (current user)
     * / confirmedAt / financeReview* / estimatedCost / estimatedProfit / actualShippedAmount
     * / deliveredQuantity / lockedQty / reservedQty / invoiceStatus / invoicedAmount / settlementFlag
     * / paidAmount / transportPlanStatus / deliveryReminderDate / contractFile* / vflag / markerColor.
     *
     * @throws com.cretas.aims.exception.ResourceNotFoundException 源订单不存在
     * @throws com.cretas.aims.exception.BusinessException 403 跨工厂访问
     */
    SalesOrder copySalesOrder(String factoryId, String sourceOrderId, Long userId);

    /**
     * 删除草稿销售订单 (软删除, 仅 DRAFT 状态).
     *
     * <p>触发 @SQLDelete 设 deleted_at — 订单在所有 @Where 查询中消失。
     * factoryId 隔离通过 getSalesOrderById(403) 保证。
     * 权限: sales:read_write (与 createSalesOrder 一致, MVP 无 creator-only 限制)。
     *
     * @throws com.cretas.aims.exception.ResourceNotFoundException 订单不存在
     * @throws com.cretas.aims.exception.BusinessException 403 跨工厂 / 409 非 DRAFT 状态
     */
    void deleteDraft(String factoryId, String orderId, Long userId);

    // ==================== 发货/出库 ====================

    SalesDeliveryRecord createDeliveryRecord(String factoryId, CreateDeliveryRequest request, Long userId);

    SalesDeliveryRecord shipDelivery(String factoryId, String deliveryId, Long userId);

    SalesDeliveryRecord confirmDelivered(String factoryId, String deliveryId);

    SalesDeliveryRecord getDeliveryRecordById(String factoryId, String deliveryId);

    PageResponse<SalesDeliveryRecord> getDeliveryRecords(String factoryId, int page, int size);

    List<SalesDeliveryRecord> getDeliveryRecordsByOrder(String salesOrderId);

    /**
     * Issue #740: list deliveries awaiting warehouse confirmation (DRAFT / PENDING_WAREHOUSE_CONFIRM /
     * PICKED). Warehouse staff view this to pick up sales-created drafts.
     */
    PageResponse<SalesDeliveryRecord> getPendingWarehouseDeliveries(String factoryId, int page, int size);

    /**
     * Issue #740 warehouse-side confirm endpoint. Takes 实际发货数量 (actual quantities may differ
     * from sales-planned), updates items, then 扣库存 + 转 SHIPPED + auto AR. Mirrors
     * {@link #shipDelivery} but allows actual-quantity override.
     *
     * @param actualQuantities map of deliveryItemId (String) → actual qty (BigDecimal). Items
     *                        not in map keep the original deliveredQuantity.
     */
    SalesDeliveryRecord warehouseConfirmDelivery(String factoryId, String deliveryId,
                                                  java.util.Map<String, java.math.BigDecimal> actualQuantities,
                                                  Long userId);

    /** P0-NEW-1 上传签收凭证 (照片+签收人+备注) */
    SalesDeliveryRecord uploadDeliverySignature(String factoryId, String deliveryId,
                                                 List<String> photoUrls, String signedByName, String remark);

    // ==================== 成品库存 ====================

    PageResponse<FinishedGoodsBatch> getFinishedGoodsBatches(String factoryId, int page, int size);

    /**
     * Issue #786 follow-up to #761: single-item lookup for finished-goods detail page.
     * Previous detail.vue used FE list-filter fallback (TODO comment) — cross-page
     * miss, performance hit. This method enables direct fetch-by-ID.
     *
     * @throws com.cretas.aims.exception.ResourceNotFoundException 找不到该批次
     * @throws com.cretas.aims.exception.BusinessException 403 跨工厂访问
     */
    FinishedGoodsBatch getFinishedGoodsBatchById(String factoryId, String batchId);

    List<FinishedGoodsBatch> getAvailableBatches(String factoryId, String productTypeId);

    /**
     * T4-D5 #572 Phase B-1: 按 sourceWarehouseCode 过滤的可用成品批次查询.
     *
     * <p>{@code sourceWarehouseCode} 为 null/空时回落到 WH-LOG (保持 D5 默认行为, 与 Phase A 一致).
     */
    List<FinishedGoodsBatch> getAvailableBatches(String factoryId, String productTypeId, String sourceWarehouseCode);

    FinishedGoodsBatch createFinishedGoodsBatch(String factoryId, FinishedGoodsBatch batch, Long userId);

    /**
     * T126 Phase 1 (F8) — 期初入库专用路径，发布 FinishedGoodsCreatedEvent 时设
     * {@code sourceType="OPENING"}，供监听者过滤批量期初条目。
     */
    FinishedGoodsBatch createOpeningFinishedGoodsBatch(String factoryId, FinishedGoodsBatch batch, Long userId);

    // ==================== T126 Phase 1: 成品库存 Web 闭环 ====================

    /**
     * PUT /finished-goods/{id} — 编辑元数据（备注/库位/过期日/成本单价）.
     * <p>⛔ producedQuantity 和 unit 不可通过此方法修改。
     *
     * @throws com.cretas.aims.exception.BusinessException 403 跨工厂, 409 乐观锁冲突
     */
    FinishedGoodsBatch editFinishedGoodsBatch(String factoryId, String batchId,
            com.cretas.aims.dto.inventory.EditFinishedGoodsRequest request, Long operatorId);

    /**
     * POST /finished-goods/{id}/adjust — 调整数量.
     * <p>可用量不得为负，否则抛 422。成功写 finished_goods_adjustment_log。
     *
     * @throws com.cretas.aims.exception.BusinessException 422 调整后可用量为负, 409 乐观锁冲突
     */
    FinishedGoodsBatch adjustFinishedGoodsQuantity(String factoryId, String batchId,
            com.cretas.aims.dto.inventory.AdjustFinishedGoodsRequest request, Long operatorId);

    /**
     * POST /finished-goods/{id}/void — 软删除（设 deleted_at）.
     * <p>前提：shippedQuantity == 0 AND reservedQuantity == 0，否则抛 409。
     *
     * @throws com.cretas.aims.exception.BusinessException 409 已有出库/预留记录
     */
    void voidFinishedGoodsBatch(String factoryId, String batchId, Long operatorId);

    // ==================== 公式计算 ====================

    Map<String, Object> computeOrderFormulas(String factoryId, String orderId);

    // ==================== 统计 ====================

    Map<String, Object> getSalesStatistics(String factoryId);
}

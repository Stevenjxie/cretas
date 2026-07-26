package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.inventory.CreatePurchaseOrderRequest;
import com.cretas.aims.dto.inventory.CreateReceiveRecordRequest;
import com.cretas.aims.dto.inventory.ClosePurchaseReceivingTaskRequest;
import com.cretas.aims.dto.inventory.UpdatePurchaseOrderRequest;
import com.cretas.aims.dto.inventory.PurchaseApprovalRecoveryResponse;
import com.cretas.aims.dto.inventory.PurchaseReceivingTaskResponse;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseReceiveRecord;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.workflow.ApprovalHistory.HistoryAction;

import com.cretas.aims.dto.inventory.MaterialPriceComparisonDTO;
import com.cretas.aims.dto.inventory.PurchaseSuggestionResponse;

import java.util.List;
import java.util.Map;

public interface PurchaseService {

    // ==================== 采购订单 ====================

    PurchaseOrder createPurchaseOrder(String factoryId, CreatePurchaseOrderRequest request, Long userId);

    PurchaseOrder getPurchaseOrderById(String factoryId, String orderId);

    /**
     * 按订单号 (orderNumber, 如 PO-20260514-001) 查采购单 — 工厂隔离.
     *
     * <p>主要用于 PDF QR 扫码场景: 仓管员扫 PDF 上的 QR 拿到 orderNumber, 直接
     * 反查订单 + 关联明细, 进入入库收货页 (W-ABA-1 Day 3-6 PDF 扫码闭环).</p>
     *
     * @throws com.cretas.aims.exception.ResourceNotFoundException 找不到该订单号
     */
    PurchaseOrder getPurchaseOrderByNumber(String factoryId, String orderNumber);

    PageResponse<PurchaseOrder> getPurchaseOrders(String factoryId, int page, int size);

    PageResponse<PurchaseOrder> getPurchaseOrdersByStatus(String factoryId, PurchaseOrderStatus status, int page, int size);

    /** W-12 fix: filter by linked sales order id (for SO detail "关联采购" tab). */
    PageResponse<PurchaseOrder> getPurchaseOrdersBySalesOrder(String factoryId, String salesOrderId, int page, int size);

    default PurchaseOrder submitOrder(String factoryId, String orderId) {
        return submitOrder(factoryId, orderId, null);
    }

    /** Submit once and atomically start the unified OA workflow. */
    PurchaseOrder submitOrder(String factoryId, String orderId, Long initiatorUserId);

    /** Repair one historical SUBMITTED order that has no OA workflow instance. */
    PurchaseApprovalRecoveryResponse recoverMissingApprovalInstance(
            String factoryId,
            String orderId,
            Long operatorUserId,
            String expectedOrderNumber,
            String idempotencyKey,
            String reason,
            boolean confirm);

    /** Apply an OA action and project the workflow state back to the purchase order. */
    PurchaseOrder applyWorkflowAction(String factoryId,
                                      String orderId,
                                      String instanceId,
                                      Long actorId,
                                      String actorRole,
                                      HistoryAction action,
                                      String notes);

    /**
     * Phase 4a follow-up (issue #45) — RuleEngine bridge method, invoked by submitOrder
     * via self-proxy so {@code @RuleEvaluate} aspect fires.
     *
     * <p>Not intended for direct caller use. Visible on the interface only because
     * Spring AOP proxy interception requires the method to be declared on the interface
     * (for JDK proxies) or {@code public} (for CGLIB) and routed through a proxy reference.
     *
     * @param factoryId tenant scope
     * @param order     loaded {@link PurchaseOrder} POJO — rules evaluated against this
     */
    void evaluateOrderRules(String factoryId, PurchaseOrder order);

    PurchaseOrder approveOrder(String factoryId, String orderId, Long approvedBy);

    PurchaseOrder cancelOrder(String factoryId, String orderId);

    PurchaseOrder submitForFinanceReview(String factoryId, String orderId);

    PurchaseOrder financeApproveOrder(String factoryId, String orderId, Long reviewedBy, String notes);

    PurchaseOrder financeRejectOrder(String factoryId, String orderId, Long reviewedBy, String notes);

    PurchaseOrder updateDraftOrder(String factoryId, String orderId, UpdatePurchaseOrderRequest request);

    /**
     * 复制采购订单 — 基于现有订单创建新草稿 (#860 follow-up).
     *
     * <p>复制内容: 供应商/采购类型/预期到货日期/备注/sales_order_id/inquiry_quote_id/items
     * (material_type_id/quantity/unit/unit_price/tax_rate/specification/box_quantity/remark).
     *
     * <p>不复制 (重置或重新生成): id / orderNumber (重新生成) / status (DRAFT) / createdBy (current user)
     * / approvedBy / approvedAt / financeReview* / receivedQuantity / vflag (UNCREATED) / markerColor.
     *
     * @throws com.cretas.aims.exception.ResourceNotFoundException 源订单不存在
     * @throws com.cretas.aims.exception.BusinessException 403 跨工厂访问
     */
    PurchaseOrder copyPurchaseOrder(String factoryId, String sourceOrderId, Long userId);

    // ==================== 采购入库 ====================

    PurchaseReceiveRecord createReceiveRecord(String factoryId, CreateReceiveRecordRequest request, Long userId);

    /**
     * 仓储统一入库页待收货任务。只读派生，不创建任务记录、不修改采购单或库存。
     */
    List<PurchaseReceivingTaskResponse> getPendingReceivingTasks(
            String factoryId, String purchaseOrderId, String orderNumber);

    /**
     * 仓储手动少收关闭。已确认库存不回滚；活动收货草稿存在时拒绝关闭。
     */
    com.cretas.aims.entity.enums.PurchaseOrderStatus closeReceivingTask(
            String factoryId,
            String purchaseOrderId,
            ClosePurchaseReceivingTaskRequest request,
            Long userId);

    PurchaseReceiveRecord confirmReceive(String factoryId, String receiveId, Long userId);

    PurchaseReceiveRecord getReceiveRecordById(String factoryId, String receiveId);

    PageResponse<PurchaseReceiveRecord> getReceiveRecords(String factoryId, int page, int size);

    List<PurchaseReceiveRecord> getReceiveRecordsByOrder(String factoryId, String purchaseOrderId);

    /**
     * Issue #787 follow-up to PR #782 / #775: 后端按行汇总入库累计数量.
     *
     * <p>之前 RCV list response 的 '累计已收' 是 FE-only 聚合 current page rows — 跨 page 不准,
     * 性能差. 现在前端改用此 endpoint 获取后端权威值.
     *
     * <p>返回结构: {@code {poId, orderNumber, plannedTotal, cumulativeReceived,
     *                        lines: [{materialId, materialName, plannedQty, receivedQty, pendingQty}]} }
     *
     * <p>数据源: 直接读 {@link com.cretas.aims.entity.inventory.PurchaseOrderItem#receivedQuantity}
     * (confirmReceive 时增量累计, 已 byPO partition). 不需要再 SUM(receive_items) 跨表.
     *
     * @throws com.cretas.aims.exception.ResourceNotFoundException PO 不存在
     * @throws com.cretas.aims.exception.BusinessException 403 跨工厂访问
     */
    Map<String, Object> getCumulativeReceived(String factoryId, String orderId);

    /**
     * 单元 G (F006 R-B3) — 采购订单的分次收货时序明细.
     *
     * <p>客户张权 (5/8 system review): "收货数量要显示出来 (第一次收了多少第二次收了多少更直观)".
     * 不同于 {@link #getCumulativeReceived} (读 PO item 累计总量), 本方法返回**每次收货事件**
     * (PurchaseReceiveRecord) 的时序列表, createdAt 升序, 逐条带 1-based seq.
     *
     * <p>返回结构: {@code [{seq, receiveId, receiveNumber, receiveDate, createdAt,
     *                        createdByName, totalQuantity, items: [{materialName, quantity, unit}]}]}
     *
     * <p>无收货记录时返回空列表 (非 null), 不返回假数据.
     *
     * @throws com.cretas.aims.exception.ResourceNotFoundException PO 不存在
     * @throws com.cretas.aims.exception.BusinessException 403 跨工厂访问
     */
    List<Map<String, Object>> getOrderReceiveSequence(String factoryId, String orderId);

    // ==================== 统计 ====================

    Map<String, Object> getPurchaseStatistics(String factoryId);

    // ==================== 开始采购 — 从 SO 生成采购建议 ====================

    /**
     * 从销售订单一键生成采购建议明细 (客户原话: "做个弹窗…我直接点开始采购").
     *
     * <p>逻辑:
     * <ol>
     *   <li>按 salesOrderId 加载 SO 及其行项目。</li>
     *   <li>对每个行项目查当前 ACTIVE BomRecipe；若有 BOM，展开原辅料/包材需求量
     *       (standardQuantity / (yieldRate/100) × SOItem.quantity)。</li>
     *   <li>无 BOM 的产品原样放入列表（materialTypeId=null，作为提示）。</li>
     *   <li>相同原料跨产品合并（materialTypeId 为 key，数量累加）。</li>
     *   <li>从 MaterialBatch 查当前可用库存，计算净需求。</li>
     * </ol>
     *
     * @param factoryId   工厂 ID（多租户隔离）
     * @param salesOrderId 销售订单 ID
     * @return 采购建议响应，含每种原料的需求量/库存/净需求
     * @throws com.cretas.aims.exception.ResourceNotFoundException SO 不存在
     * @throws com.cretas.aims.exception.BusinessException 403 跨工厂访问
     */
    PurchaseSuggestionResponse generatePurchaseSuggestion(String factoryId, String salesOrderId);

    /**
     * 多销售订单合并生成采购建议 (转录行3650: 多个 SO 用"加号"逐个追加合并成一张采购单).
     *
     * <p>对每张 SO 展开 BOM, <b>跨所有 SO 按 materialTypeId 聚合</b> 需求量
     * (如 SO1 需牛腱 50 + SO2 需牛腱 30 → 合并 80), 再 <b>统一扣减一次当前库存</b> 得净需求
     * (库存只有一份, 净需求 = Σrequired − 当前库存; 避免每 SO 各扣一次重复计算)。
     *
     * <p>复用单 SO 的 BOM 展开 + 聚合逻辑 ({@code expandSoItemsInto}); 单 SO 即此方法退化情形。
     * 返回顶层 salesOrderIds/Numbers + 每行 sourceSalesOrderNumbers (合并自哪几张 SO) +
     * 逐 SO 的 hasBom 摘要 (诚实暴露无配方的 SO, 不静默跳过)。
     *
     * @param factoryId     工厂 ID（多租户隔离）
     * @param salesOrderIds 销售订单 ID 列表 (去重后聚合)
     * @return 合并采购建议响应
     * @throws com.cretas.aims.exception.ResourceNotFoundException 任一 SO 不存在
     * @throws com.cretas.aims.exception.BusinessException 403 任一 SO 跨工厂; 或列表为空
     */
    com.cretas.aims.dto.inventory.PurchaseSuggestionMultiResponse generatePurchaseSuggestionMulti(
            String factoryId, java.util.List<String> salesOrderIds);

    // ==================== 三价对比 ====================

    /**
     * 获取采购订单的三价对比数据
     * 对每个行项目比较：BOM标准单价、移动平均价、当前采购单价
     */
    List<MaterialPriceComparisonDTO> getOrderPriceComparison(String factoryId, String orderId);

    /**
     * 获取单个原料的三价信息
     * 用于采购下单时逐个原料查询参考价
     */
    MaterialPriceComparisonDTO getMaterialPriceInfo(String factoryId, String materialTypeId, java.math.BigDecimal currentPrice);
}

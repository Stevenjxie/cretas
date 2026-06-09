package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.inventory.PurchaseInvoice;

import java.math.BigDecimal;
import java.util.List;

/**
 * 采购发票 Service（SP6）
 *
 * <p>上传发票 → 对账（PENDING/MATCHED/MISMATCHED）→ 逾期提醒。
 */
public interface PurchaseInvoiceService {

    /**
     * 上传发票。
     * 幂等：同一工厂下 invoiceNumber 重复则抛 BusinessException。
     *
     * @param factoryId    工厂 ID
     * @param poId         采购订单 ID
     * @param supplierId   供应商 ID
     * @param invoiceNumber 发票号
     * @param amount       发票金额
     * @param ocrRawResult OCR 原始结果（JSON，可 null）
     * @param uploadedBy   上传人 userId
     * @return 创建的发票实体（reconcileStatus=PENDING）
     */
    PurchaseInvoice upload(
            String factoryId,
            String poId,
            String supplierId,
            String invoiceNumber,
            BigDecimal amount,
            String ocrRawResult,
            Long uploadedBy);

    /**
     * 对账操作（PENDING → MATCHED 或 MISMATCHED）。
     * 已对账（非 PENDING）再次调用抛 BusinessException。
     *
     * @param invoiceId      发票 ID
     * @param factoryId      工厂 ID（隔离校验）
     * @param reconcileStatus 目标状态（MATCHED / MISMATCHED）
     * @param note           对账备注
     * @param reconciledBy   操作人 userId
     */
    void reconcile(
            String invoiceId,
            String factoryId,
            String reconcileStatus,
            String note,
            Long reconciledBy);

    /**
     * 查询超期未开票的采购订单 ID 列表（逾期提醒）。
     *
     * <p>条件：PO.orderDate + COALESCE(invoiceReminderDays, 30) <= today
     * 且该 PO 在 purchase_invoices 中无记录。
     */
    List<String> listPendingReminder(String factoryId);

    /** 查询某采购订单的全部发票 */
    List<PurchaseInvoice> listByPurchaseOrder(String factoryId, String poId);
}

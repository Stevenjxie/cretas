package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.inventory.PurchaseInvoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * 采购发票 Repository（SP6）
 */
@Repository
public interface PurchaseInvoiceRepository extends JpaRepository<PurchaseInvoice, String> {

    List<PurchaseInvoice> findByFactoryIdOrderByCreatedAtDesc(String factoryId);

    List<PurchaseInvoice> findByPurchaseOrderId(String purchaseOrderId);

    List<PurchaseInvoice> findByFactoryIdAndReconcileStatus(String factoryId, String reconcileStatus);

    java.util.Optional<PurchaseInvoice> findByFactoryIdAndInvoiceNumber(String factoryId, String invoiceNumber);

    List<PurchaseInvoice> findByFactoryIdAndPurchaseOrderId(String factoryId, String purchaseOrderId);

    /**
     * 查询已超过开票提醒期限但尚未收到发票的采购订单（列表包含 PO 基础信息）。
     *
     * <p>逻辑：PO 的 orderDate + invoiceReminderDays <= today，
     * 且 purchase_invoices 中 factoryId 相同的 PO 无任何发票记录。
     *
     * <p>返回的字段：purchaseOrderId, orderNumber (via Formula), supplierName。
     * Controller 层需进一步 join 或二次查询做展示。
     */
    @Query(value = """
            SELECT pi.*
            FROM purchase_invoices pi
            WHERE pi.factory_id = :factoryId
              AND pi.reconcile_status = 'PENDING'
              AND pi.deleted_at IS NULL
            ORDER BY pi.created_at DESC
            """, nativeQuery = true)
    List<PurchaseInvoice> findPendingReconcileByFactory(@Param("factoryId") String factoryId);

    /**
     * 查询某工厂超期未到票的采购订单 ID 列表。
     *
     * <p>条件：po.orderDate + po.invoice_reminder_days < today，
     * 且该 PO 在 purchase_invoices 中无记录（COUNT=0）。缺配置时不使用硬编码默认值。
     */
    @Query(value = """
            SELECT po.id
            FROM purchase_orders po
            WHERE po.factory_id = :factoryId
              AND (
                  po.status IN ('COMPLETED', 'CLOSED')
                  OR EXISTS (
                      SELECT 1 FROM payment_requests pr
                      WHERE pr.purchase_order_id = po.id
                        AND pr.status = 'PAID'
                        AND pr.deleted_at IS NULL
                  )
              )
              AND po.deleted_at IS NULL
              AND po.invoice_reminder_days IS NOT NULL
              AND po.invoice_reminder_days > 0
              AND (po.order_date + po.invoice_reminder_days * INTERVAL '1 day') < :today
              AND NOT EXISTS (
                  SELECT 1 FROM purchase_invoices pi
                  WHERE pi.purchase_order_id = po.id
                    AND pi.deleted_at IS NULL
              )
            """, nativeQuery = true)
    List<String> findOverdueInvoicePoIds(
            @Param("factoryId") String factoryId,
            @Param("today") LocalDate today);
}

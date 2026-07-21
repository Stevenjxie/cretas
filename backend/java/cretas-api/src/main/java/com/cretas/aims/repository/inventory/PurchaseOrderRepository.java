package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.enums.PurchaseInvoiceStatus;
import com.cretas.aims.entity.enums.PaymentRequestStatus;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

@Repository
public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, String> {

    Page<PurchaseOrder> findByFactoryIdOrderByCreatedAtDesc(String factoryId, Pageable pageable);

    /**
     * Sprint 6 W2-B (RBAC DataScope SELF) — 仅查当前用户创建的采购单.
     * Used by {@link com.cretas.aims.service.inventory.PurchaseService#getPurchaseOrders} when
     * {@link com.cretas.aims.security.DataScopeContext#current()} scope is SELF.
     */
    Page<PurchaseOrder> findByFactoryIdAndCreatedByOrderByCreatedAtDesc(
            String factoryId, Long createdBy, Pageable pageable);

    /**
     * Sprint 6 W2-B (RBAC DataScope DEPT_AND_BELOW / SELF_AND_BELOW) — IN list 过滤.
     */
    Page<PurchaseOrder> findByFactoryIdAndCreatedByInOrderByCreatedAtDesc(
            String factoryId, List<Long> createdByList, Pageable pageable);

    Page<PurchaseOrder> findByFactoryIdAndStatusOrderByCreatedAtDesc(String factoryId, PurchaseOrderStatus status, Pageable pageable);

    /** W-12 fix: SO detail "关联采购" tab filter. */
    Page<PurchaseOrder> findByFactoryIdAndSalesOrderId(String factoryId, String salesOrderId, Pageable pageable);

    Optional<PurchaseOrder> findByFactoryIdAndOrderNumber(String factoryId, String orderNumber);

    /**
     * 按 ID + factoryId 查 PO（租户隔离安全查法）.
     *
     * <p>防止跨工厂 ID 猜测：先查 ID，再断言 factoryId 匹配。
     * 等价于 {@code findById} + {@code !factoryId.equals(po.getFactoryId())} 检查，
     * 但以单个 SQL 完成，消除 audit 扫描的 "findById without factoryId guard" 误报。
     */
    Optional<PurchaseOrder> findByIdAndFactoryId(String id, String factoryId);

    /**
     * OA submit boundary lock.  The purchase order and its workflow instance must be
     * created as one atomic transition; two browser tabs must not both observe DRAFT.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT po FROM PurchaseOrder po WHERE po.id = :id AND po.factoryId = :factoryId")
    Optional<PurchaseOrder> findByIdAndFactoryIdForUpdate(
            @Param("id") String id,
            @Param("factoryId") String factoryId);

    List<PurchaseOrder> findByFactoryIdAndSupplierId(String factoryId, String supplierId);

    /**
     * 防呆 R4 (幂等防双击): 60s 窗口内同 工厂/供应商/创建人 的 DRAFT 采购单 → 视为重复点击。
     * 键收窄到 createdBy: 误拦面 = 同一买手 60s 内对同供应商重复建单 (几乎必是双击),
     * 不同买手/超 60s 的合法重复下单不受影响 (reviewer ISSUE-2 经验)。
     */
    @Query("SELECT po FROM PurchaseOrder po WHERE po.factoryId = ?1 AND po.supplierId = ?2 "
            + "AND po.createdBy = ?3 AND po.status = com.cretas.aims.entity.enums.PurchaseOrderStatus.DRAFT "
            + "AND po.deletedAt IS NULL AND po.createdAt >= ?4 ORDER BY po.createdAt DESC")
    List<PurchaseOrder> findRecentDuplicateOrders(String factoryId, String supplierId,
            Long createdBy, java.time.LocalDateTime cutoff);

    @Query("SELECT po FROM PurchaseOrder po WHERE po.factoryId = :factoryId " +
            "AND po.orderDate BETWEEN :startDate AND :endDate ORDER BY po.orderDate DESC")
    List<PurchaseOrder> findByFactoryIdAndDateRange(
            @Param("factoryId") String factoryId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /** 多组织聚合查询（总部视图） */
    Page<PurchaseOrder> findByFactoryIdInOrderByCreatedAtDesc(List<String> factoryIds, Pageable pageable);

    /** 生成订单号：统计当天该工厂的采购单数量 */
    @Query("SELECT COUNT(po) FROM PurchaseOrder po WHERE po.factoryId = :factoryId AND po.orderDate = :date")
    long countByFactoryIdAndDate(@Param("factoryId") String factoryId, @Param("date") LocalDate date);

    /** 生成订单号：查找当天最大订单号（避免并发冲突） */
    @Query("SELECT MAX(po.orderNumber) FROM PurchaseOrder po WHERE po.factoryId = :factoryId AND po.orderNumber LIKE :prefix")
    Optional<String> findMaxOrderNumberByPrefix(@Param("factoryId") String factoryId, @Param("prefix") String prefix);
    @Query("SELECT DISTINCT po.factoryId FROM PurchaseOrder po WHERE po.deletedAt IS NULL")
    List<String> findDistinctFactoryIds();

    @Query("""
            SELECT po
              FROM PurchaseOrder po
             WHERE po.factoryId = :factoryId
               AND (
                    po.status IN :statuses
                    OR EXISTS (
                        SELECT pr.id
                          FROM PaymentRequest pr
                         WHERE pr.purchaseOrderId = po.id
                           AND pr.status = :paidStatus
                           AND pr.deletedAt IS NULL
                    )
               )
               AND (po.invoiceStatus IS NULL OR po.invoiceStatus = :invoiceStatus)
               AND po.deletedAt IS NULL
               AND NOT EXISTS (
                    SELECT pi.id
                      FROM PurchaseInvoice pi
                     WHERE pi.purchaseOrderId = po.id
                       AND pi.deletedAt IS NULL
               )
             ORDER BY po.orderDate ASC
            """)
    List<PurchaseOrder> findInvoiceChaseCandidates(
            @Param("factoryId") String factoryId,
            @Param("statuses") List<PurchaseOrderStatus> statuses,
            @Param("paidStatus") PaymentRequestStatus paidStatus,
            @Param("invoiceStatus") PurchaseInvoiceStatus invoiceStatus);

    default List<PurchaseOrder> findInvoiceChaseCandidates(String factoryId) {
        return findInvoiceChaseCandidates(
                factoryId,
                List.of(PurchaseOrderStatus.COMPLETED, PurchaseOrderStatus.CLOSED),
                PaymentRequestStatus.PAID,
                PurchaseInvoiceStatus.NOT_RECEIVED);
    }
}

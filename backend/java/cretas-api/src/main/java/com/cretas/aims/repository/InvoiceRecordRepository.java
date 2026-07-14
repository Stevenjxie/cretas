package com.cretas.aims.repository;

import com.cretas.aims.entity.finance.InvoiceRecord;
import com.cretas.aims.entity.enums.InvoiceStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface InvoiceRecordRepository extends JpaRepository<InvoiceRecord, String> {

    Page<InvoiceRecord> findByFactoryIdAndDeletedAtIsNull(String factoryId, Pageable pageable);

    /** Sprint 4 W1 S-CUSTOMER-TAB-1 (tab 15): paginated by customer, newest first, deleted excluded. */
    Page<InvoiceRecord> findByFactoryIdAndCustomerIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            String factoryId, String customerId, Pageable pageable);

    Page<InvoiceRecord> findByFactoryIdAndStatusAndDeletedAtIsNull(String factoryId, InvoiceStatus status, Pageable pageable);

    /**
     * 发票列表筛选 — status + invoiceType 均可选 (前端下拉).
     * invoice_records 是不设上限持续增长的财务台账, 与 SystemLogRepository.searchLogs 同款
     * CAST(:param AS text) IS NULL 写法 (PG 严格类型推断下 untyped 占位符会报
     * "could not determine data type of parameter", 见 database-entity-sync.md).
     */
    @Query(value = "SELECT * FROM invoice_records i WHERE i.factory_id = :factoryId " +
            "AND i.deleted_at IS NULL " +
            "AND (CAST(:status AS text) IS NULL OR i.status = CAST(:status AS text)) " +
            "AND (CAST(:invoiceType AS text) IS NULL OR i.invoice_type = CAST(:invoiceType AS text)) " +
            "ORDER BY i.created_at DESC",
            countQuery = "SELECT COUNT(*) FROM invoice_records i WHERE i.factory_id = :factoryId " +
            "AND i.deleted_at IS NULL " +
            "AND (CAST(:status AS text) IS NULL OR i.status = CAST(:status AS text)) " +
            "AND (CAST(:invoiceType AS text) IS NULL OR i.invoice_type = CAST(:invoiceType AS text))",
            nativeQuery = true)
    Page<InvoiceRecord> searchInvoices(@Param("factoryId") String factoryId,
                                        @Param("status") String status,
                                        @Param("invoiceType") String invoiceType,
                                        Pageable pageable);

    List<InvoiceRecord> findBySalesOrderIdAndDeletedAtIsNull(String salesOrderId);

    /** Factory-scoped lookup — enforces tenant isolation. */
    Optional<InvoiceRecord> findByIdAndFactoryIdAndDeletedAtIsNull(String id, String factoryId);

    /** Factory-scoped: list invoices for a specific sales order. */
    List<InvoiceRecord> findByFactoryIdAndSalesOrderIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            String factoryId, String salesOrderId);

    @Query("SELECT COALESCE(SUM(i.totalAmount), 0) FROM InvoiceRecord i WHERE i.salesOrderId = ?1 AND i.status = 'ISSUED' AND i.deletedAt IS NULL")
    BigDecimal sumIssuedAmountBySalesOrderId(String salesOrderId);

    long countByFactoryIdAndStatusAndDeletedAtIsNull(String factoryId, InvoiceStatus status);

    /**
     * Find pending (REQUESTED/APPROVED, not yet ISSUED/REJECTED) invoices for a given sales order.
     * Used by requestInvoiceFromOrder to prevent duplicate submissions (Bug #2, R2 fix 2026-04-16).
     */
    List<InvoiceRecord> findByFactoryIdAndSalesOrderIdAndStatusInAndDeletedAtIsNull(
            String factoryId, String salesOrderId, List<InvoiceStatus> statuses);
}

package com.cretas.aims.repository;

import com.cretas.aims.entity.finance.InvoiceRecord;
import com.cretas.aims.entity.enums.InvoiceStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
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

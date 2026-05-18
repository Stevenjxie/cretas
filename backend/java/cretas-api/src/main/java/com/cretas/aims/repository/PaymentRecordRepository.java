package com.cretas.aims.repository;

import com.cretas.aims.entity.finance.PaymentRecord;
import com.cretas.aims.entity.enums.PaymentRecordStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface PaymentRecordRepository extends JpaRepository<PaymentRecord, String> {

    Page<PaymentRecord> findByFactoryIdAndDeletedAtIsNull(String factoryId, Pageable pageable);

    Page<PaymentRecord> findByFactoryIdAndStatusAndDeletedAtIsNull(String factoryId, PaymentRecordStatus status, Pageable pageable);

    /** Sprint 4 W1 S-CUSTOMER-TAB-1 (tab 16): paginated by customer, newest first, deleted excluded. */
    Page<PaymentRecord> findByFactoryIdAndCustomerIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            String factoryId, String customerId, Pageable pageable);

    List<PaymentRecord> findBySalesOrderIdAndDeletedAtIsNull(String salesOrderId);

    /** Factory-scoped lookup for sales order tab. */
    List<PaymentRecord> findByFactoryIdAndSalesOrderIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            String factoryId, String salesOrderId);

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM PaymentRecord p WHERE p.salesOrderId = ?1 AND p.status = 'VERIFIED' AND p.deletedAt IS NULL")
    BigDecimal sumVerifiedAmountBySalesOrderId(String salesOrderId);
}

package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.enums.PurchaseInvoiceChaseLevel;
import com.cretas.aims.entity.enums.PurchaseInvoiceChaseStatus;
import com.cretas.aims.entity.inventory.PurchaseInvoiceChaseLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Repository
public interface PurchaseInvoiceChaseLogRepository extends JpaRepository<PurchaseInvoiceChaseLog, String> {

    boolean existsByFactoryIdAndPurchaseOrderIdAndChaseLevelAndChaseWindowStartAndDeletedAtIsNull(
            String factoryId,
            String purchaseOrderId,
            PurchaseInvoiceChaseLevel chaseLevel,
            LocalDate chaseWindowStart);

    @Modifying
    @Query("""
            UPDATE PurchaseInvoiceChaseLog entry
               SET entry.status = :closedStatus,
                   entry.closedAt = :closedAt
             WHERE entry.factoryId = :factoryId
               AND entry.purchaseOrderId = :purchaseOrderId
               AND entry.status = :sentStatus
               AND entry.deletedAt IS NULL
            """)
    int closeOpenChases(@Param("factoryId") String factoryId,
                        @Param("purchaseOrderId") String purchaseOrderId,
                        @Param("closedAt") LocalDateTime closedAt,
                        @Param("closedStatus") PurchaseInvoiceChaseStatus closedStatus,
                        @Param("sentStatus") PurchaseInvoiceChaseStatus sentStatus);

    default int closeOpenChases(String factoryId, String purchaseOrderId, LocalDateTime closedAt) {
        return closeOpenChases(
                factoryId,
                purchaseOrderId,
                closedAt,
                PurchaseInvoiceChaseStatus.CLOSED,
                PurchaseInvoiceChaseStatus.SENT);
    }
}

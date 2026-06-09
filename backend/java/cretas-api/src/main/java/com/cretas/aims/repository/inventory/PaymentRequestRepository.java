package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.enums.PaymentRequestStatus;
import com.cretas.aims.entity.inventory.PaymentRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 付款申请单 Repository（SP6）
 */
@Repository
public interface PaymentRequestRepository extends JpaRepository<PaymentRequest, String> {

    List<PaymentRequest> findByFactoryIdOrderByCreatedAtDesc(String factoryId);

    List<PaymentRequest> findByFactoryIdAndStatus(String factoryId, PaymentRequestStatus status);

    List<PaymentRequest> findByFactoryIdAndSupplierIdOrderByCreatedAtDesc(String factoryId, String supplierId);

    List<PaymentRequest> findByPurchaseOrderId(String purchaseOrderId);

    Optional<PaymentRequest> findByFactoryIdAndRequestNumber(String factoryId, String requestNumber);

    /**
     * 幂等性检查：同一 PO 是否已有处于活跃状态的付款申请。
     * 活跃状态 = PENDING / FINANCE_REVIEW / APPROVED。
     */
    @Query("SELECT pr FROM PaymentRequest pr WHERE pr.purchaseOrderId = :purchaseOrderId " +
           "AND pr.status IN (:activeStatuses) AND pr.deletedAt IS NULL")
    List<PaymentRequest> findActiveByPurchaseOrderId(
            @Param("purchaseOrderId") String purchaseOrderId,
            @Param("activeStatuses") List<PaymentRequestStatus> activeStatuses);

    /**
     * 出纳专用：仅查询 APPROVED 状态（等待付款）。
     */
    List<PaymentRequest> findByFactoryIdAndStatusOrderByApprovedAtAsc(
            String factoryId, PaymentRequestStatus status);
}

package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.inventory.SalesPriceAdjustmentRecord;
import com.cretas.aims.entity.inventory.SalesPriceAdjustmentRecord.ApprovalStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SalesPriceAdjustmentRecordRepository extends JpaRepository<SalesPriceAdjustmentRecord, String> {

    List<SalesPriceAdjustmentRecord> findByFactoryIdAndSalesOrderIdOrderByCreatedAtDesc(
            String factoryId, String salesOrderId);

    List<SalesPriceAdjustmentRecord> findByFactoryIdAndSalesOrderLineIdOrderByCreatedAtDesc(
            String factoryId, Long salesOrderLineId);

    /** 幂等校验: 同一行是否已有 PENDING 审批记录 (fool-proof Rule 4) */
    Optional<SalesPriceAdjustmentRecord> findFirstBySalesOrderLineIdAndApprovalStatusOrderByCreatedAtDesc(
            Long salesOrderLineId, ApprovalStatus approvalStatus);
}

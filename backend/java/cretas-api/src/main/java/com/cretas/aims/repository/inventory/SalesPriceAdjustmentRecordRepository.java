package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.inventory.SalesPriceAdjustmentRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface SalesPriceAdjustmentRecordRepository extends JpaRepository<SalesPriceAdjustmentRecord, String> {

    List<SalesPriceAdjustmentRecord> findByFactoryIdAndSalesOrderIdOrderByCreatedAtDesc(
            String factoryId, String salesOrderId);

    List<SalesPriceAdjustmentRecord> findByFactoryIdAndSalesOrderLineIdOrderByCreatedAtDesc(
            String factoryId, Long salesOrderLineId);

    /**
     * 幂等校验: 5 分钟窗口内同一行是否已有相同目标价格的记录 (fool-proof Rule 4).
     *
     * <p>返回最近一条匹配记录, 调用方决定是否重用.
     */
    @Query("SELECT r FROM SalesPriceAdjustmentRecord r " +
           "WHERE r.salesOrderLineId = :lineId " +
           "AND r.newUnitPrice = :newPrice " +
           "AND r.createdAt >= :since " +
           "ORDER BY r.createdAt DESC")
    List<SalesPriceAdjustmentRecord> findRecentDuplicates(
            @Param("lineId") Long lineId,
            @Param("newPrice") BigDecimal newPrice,
            @Param("since") LocalDateTime since);

    /** 审计: 工厂内全部 flagged=true 的记录 (超阈值改价), 供财务/审计复核 */
    List<SalesPriceAdjustmentRecord> findByFactoryIdAndFlaggedTrueOrderByCreatedAtDesc(String factoryId);
}

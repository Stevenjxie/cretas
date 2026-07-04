package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;

@Repository
public interface SalesDeliveryItemRepository extends JpaRepository<SalesDeliveryItem, Long> {

    /**
     * 结转成本 (COGS) 聚合: 期内已发货成品的销售成本 = Σ 发货数量 × 批次单位成本。
     *
     * <p>只计已实际扣减库存的发货 (status ∈ SHIPPED/DELIVERED); DRAFT/待确认/已退回不计。
     * 按发货单的 {@code delivery_date} 归期 (单张发货单 delivery_date 定死, 只落一个自然月,
     * SHIPPED→DELIVERED 状态流转不改 delivery_date → 不跨月双计)。
     *
     * <p><b>诚实 null</b>: 仅计 {@code b.unitCost IS NOT NULL} 的行 (JOIN 成品批次)。无成本的行由
     * {@link #aggregateMissingCost} 单独统计并在结转日志暴露, 绝不按 ¥0 伪造。
     *
     * <p>返回 {@code Object[]{ SUM(qty*unitCost):BigDecimal, SUM(qty):BigDecimal, COUNT:Long }} 单行。
     */
    @Query("SELECT COALESCE(SUM(i.deliveredQuantity * b.unitCost), 0), " +
            "       COALESCE(SUM(i.deliveredQuantity), 0), " +
            "       COUNT(i) " +
            "FROM SalesDeliveryItem i " +
            "  JOIN i.deliveryRecord r " +
            "  JOIN i.finishedGoodsBatch b " +
            "WHERE r.factoryId = :factoryId " +
            "  AND r.deliveryDate BETWEEN :startDate AND :endDate " +
            "  AND r.status IN (com.cretas.aims.entity.enums.SalesDeliveryStatus.SHIPPED, " +
            "                   com.cretas.aims.entity.enums.SalesDeliveryStatus.DELIVERED) " +
            "  AND b.unitCost IS NOT NULL " +
            "  AND i.deletedAt IS NULL AND r.deletedAt IS NULL")
    Object[] aggregateShippedCogs(@Param("factoryId") String factoryId,
                                  @Param("startDate") LocalDate startDate,
                                  @Param("endDate") LocalDate endDate);

    /**
     * 结转成本 诚实 null 统计: 期内已发货 (SHIPPED/DELIVERED) 但<b>无单位成本</b>的成品行
     * (无批次关联 或 批次 unitCost 为 null — G3 成本传导诚实 null)。这些行不进 COGS 结转,
     * 由 CostCarryoverService 记 WARN + 在结转小结暴露 "N 笔无成本未结转"。
     *
     * <p>返回 {@code Object[]{ SUM(qty):BigDecimal, COUNT:Long }} 单行。
     */
    @Query("SELECT COALESCE(SUM(i.deliveredQuantity), 0), " +
            "       COUNT(i) " +
            "FROM SalesDeliveryItem i " +
            "  JOIN i.deliveryRecord r " +
            "  LEFT JOIN i.finishedGoodsBatch b " +
            "WHERE r.factoryId = :factoryId " +
            "  AND r.deliveryDate BETWEEN :startDate AND :endDate " +
            "  AND r.status IN (com.cretas.aims.entity.enums.SalesDeliveryStatus.SHIPPED, " +
            "                   com.cretas.aims.entity.enums.SalesDeliveryStatus.DELIVERED) " +
            "  AND (i.finishedGoodsBatchId IS NULL OR b.unitCost IS NULL) " +
            "  AND i.deletedAt IS NULL AND r.deletedAt IS NULL")
    Object[] aggregateMissingCost(@Param("factoryId") String factoryId,
                                  @Param("startDate") LocalDate startDate,
                                  @Param("endDate") LocalDate endDate);
}

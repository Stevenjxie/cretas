package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;

@Repository
public interface SalesDeliveryItemRepository extends JpaRepository<SalesDeliveryItem, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM SalesDeliveryItem i WHERE i.id = :id")
    Optional<SalesDeliveryItem> findByIdForUpdate(@Param("id") Long id);

    /** Locks every line of one delivery in deterministic id order before ship/cancel mutations. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM SalesDeliveryItem i WHERE i.deliveryRecordId = :deliveryRecordId ORDER BY i.id")
    List<SalesDeliveryItem> findByDeliveryRecordIdForUpdate(
            @Param("deliveryRecordId") String deliveryRecordId);

    /**
     * 收发存(数量金额账)成品发出流水: 期内实际发货的<b>逐笔</b>发货明细, 按发货单 {@code delivery_date}
     * 归期 (每笔发货的真实日期), 用于成品的期内发出流水。
     *
     * <p>取代旧实现直接读 {@code FinishedGoodsBatch.shippedQuantity}(生命周期累计) 并盖在
     * 生产日期上的错误做法 —— 那样会把一个批次跨月的全部发货历史都记到生产当月, 破坏逐期收发存。
     *
     * <p><b>成本口径 (F006 财务审计 Bug 7)</b>: 金额按成品单位<b>成本</b> {@code b.unitCost}
     * (G3 成本传导, 诚实 null), <b>不是</b>售价 {@code unitPrice}。这里<b>不过滤</b> unitCost IS NULL —
     * 物理发出是真实发生的, 成本未知时以诚实 null(金额留空)呈现, 绝不按售价或 ¥0 伪造。
     *
     * <p>只计已实际扣减库存的发货 (status ∈ SHIPPED/DELIVERED); DRAFT/待确认/已退回不计。
     * {@code LEFT JOIN} 成品批次: 即使发货行未关联批次(batchId 为空)也保留该笔发出(成本诚实 null)。
     *
     * <p>返回列: {@code [deliveryDate:LocalDate, deliveredQuantity:BigDecimal, unitCost:BigDecimal(可空),
     * productTypeId:String, batchNumber:String, productName:String, deliveryNumber:String]}。
     */
    @Query("SELECT r.deliveryDate, i.deliveredQuantity, b.unitCost, " +
            "       COALESCE(b.productTypeId, i.productTypeId), " +
            "       b.batchNumber, " +
            "       COALESCE(i.productName, b.productName), " +
            "       r.deliveryNumber " +
            "FROM SalesDeliveryItem i " +
            "  JOIN i.deliveryRecord r " +
            "  LEFT JOIN i.finishedGoodsBatch b " +
            "WHERE r.factoryId = :factoryId " +
            "  AND r.deliveryDate BETWEEN :startDate AND :endDate " +
            "  AND r.status IN (com.cretas.aims.entity.enums.SalesDeliveryStatus.SHIPPED, " +
            "                   com.cretas.aims.entity.enums.SalesDeliveryStatus.DELIVERED) " +
            "  AND i.deletedAt IS NULL AND r.deletedAt IS NULL " +
            "ORDER BY r.deliveryDate ASC")
    List<Object[]> findShippedMovementsForLedger(@Param("factoryId") String factoryId,
                                                 @Param("startDate") LocalDate startDate,
                                                 @Param("endDate") LocalDate endDate);

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

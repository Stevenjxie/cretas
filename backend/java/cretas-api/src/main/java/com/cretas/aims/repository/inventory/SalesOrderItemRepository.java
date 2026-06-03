package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

@Repository
public interface SalesOrderItemRepository extends JpaRepository<SalesOrderItem, Long> {

    List<SalesOrderItem> findBySalesOrderId(String salesOrderId);

    List<SalesOrderItem> findByProductTypeId(String productTypeId);

    /**
     * 历史均价聚合 — Sprint 4 Wave 2 S-PROFIT-DETAIL-1.
     *
     * <p>Returns rows of [productTypeId, avgUnitPrice] for the given products
     * across the factory within [startDate, endDate]. Joined via SalesOrder to
     * enforce factory scoping (SalesOrderItem itself has no factoryId column).
     *
     * <p>Excludes price-sensitive null rows (warehouse_manager rows where
     * unit_price was stripped) by virtue of AVG ignoring NULLs in JPQL.
     */
    @Query("""
            SELECT soi.productTypeId AS productTypeId,
                   AVG(soi.unitPrice) AS avgUnitPrice
              FROM SalesOrderItem soi
              JOIN soi.salesOrder so
             WHERE so.factoryId = :factoryId
               AND soi.productTypeId IN :productTypeIds
               AND so.orderDate >= :startDate
               AND so.orderDate <= :endDate
               AND soi.unitPrice IS NOT NULL
             GROUP BY soi.productTypeId
            """)
    List<HistoricalAvgRow> findHistoricalAvgPrice(@Param("factoryId") String factoryId,
                                                  @Param("productTypeIds") Collection<String> productTypeIds,
                                                  @Param("startDate") LocalDate startDate,
                                                  @Param("endDate") LocalDate endDate);

    interface HistoricalAvgRow {
        String getProductTypeId();
        java.math.BigDecimal getAvgUnitPrice();
    }

    /**
     * 备货看板需求聚合 — 指定交货日期 + 有效订单状态。
     *
     * <p>按产品类型聚合当天需求量，同时捕获 MIN/MAX unit 用于 F2 单位不一致检测。
     * CANCELLED/DRAFT 订单通过 {@code statuses} 参数排除。
     *
     * @param factoryId 工厂 ID
     * @param date      要求交货日期
     * @param statuses  有效订单状态集合 (e.g. CONFIRMED, FINANCE_APPROVED, PROCESSING ...)
     * @return 每产品一行的需求投影列表
     */
    @Query("SELECT i.productTypeId AS productTypeId, " +
           "MIN(i.productName) AS productName, " +
           "MIN(i.unit) AS minUnit, " +
           "MAX(i.unit) AS maxUnit, " +
           "SUM(i.quantity) AS demand " +
           "FROM SalesOrderItem i " +
           "WHERE i.salesOrder.factoryId = :factoryId " +
           "AND i.salesOrder.requiredDeliveryDate = :date " +
           "AND i.salesOrder.status IN :statuses " +
           "GROUP BY i.productTypeId")
    List<ProductDemandProjection> sumDemandByProductForDeliveryDate(
            @Param("factoryId") String factoryId,
            @Param("date") LocalDate date,
            @Param("statuses") Collection<SalesOrderStatus> statuses);

    /**
     * P3 多仓备货看板需求聚合 — 按产品 + 目的仓分组。
     *
     * <p>COALESCE(destWarehouseCode, '未分仓') 确保旧行（未填目的仓）归入"未分仓"桶，
     * 不被丢弃，向后兼容普通订单。
     *
     * <p>MIN/MAX unit 检测 F2 单位不一致, 与 {@link #sumDemandByProductForDeliveryDate} 一致。
     *
     * @param factoryId 工厂 ID
     * @param date      要求交货日期
     * @param statuses  有效订单状态集合
     * @return 按产品+仓分组的需求投影列表
     */
    @Query("SELECT i.productTypeId AS productTypeId, " +
           "MIN(i.productName) AS productName, " +
           "COALESCE(i.destWarehouseCode, '未分仓') AS destWarehouseCode, " +
           "MIN(i.destWarehouseName) AS destWarehouseName, " +
           "MIN(i.unit) AS minUnit, " +
           "MAX(i.unit) AS maxUnit, " +
           "SUM(i.quantity) AS demand " +
           "FROM SalesOrderItem i " +
           "WHERE i.salesOrder.factoryId = :factoryId " +
           "AND i.salesOrder.requiredDeliveryDate = :date " +
           "AND i.salesOrder.status IN :statuses " +
           "GROUP BY i.productTypeId, COALESCE(i.destWarehouseCode, '未分仓')")
    List<ProductWarehouseDemandProjection> sumDemandByProductAndWarehouseForDeliveryDate(
            @Param("factoryId") String factoryId,
            @Param("date") LocalDate date,
            @Param("statuses") Collection<SalesOrderStatus> statuses);
}

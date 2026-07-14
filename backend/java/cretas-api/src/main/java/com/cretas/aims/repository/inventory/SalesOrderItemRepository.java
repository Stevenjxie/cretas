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
     * B3 售价趋势 — 财审辅助决策.
     *
     * <p>返回某工厂某产品最近 N 笔已成交（状态非 DRAFT/CANCELLED）销售订单的行级单价明细，
     * 按下单日期降序，最多取 {@code limit} 条。财务审核人员据此判断本单售价是否偏离历史均值。
     *
     * <p>用 JPQL 而非原生 SQL，兼容 H2 测试环境。
     *
     * @param factoryId     工厂 ID
     * @param productTypeId 产品类型 ID
     * @param excludedStatuses 排除的状态（DRAFT/CANCELLED）
     * @param limit         最多返回条数（排序后取前 N）
     * @return 最近成交记录，每行一个 projection
     */
    @Query("""
            SELECT so.orderNumber AS orderNumber,
                   so.orderDate   AS orderDate,
                   soi.unitPrice  AS unitPrice,
                   soi.quantity   AS quantity,
                   soi.unit       AS unit
              FROM SalesOrderItem soi
              JOIN soi.salesOrder so
             WHERE so.factoryId    = :factoryId
               AND soi.productTypeId = :productTypeId
               AND so.status NOT IN :excludedStatuses
               AND soi.unitPrice IS NOT NULL
               AND soi.unitPrice > 0
             ORDER BY so.orderDate DESC, so.createdAt DESC
            """)
    List<PriceTrendRow> findRecentPriceByProduct(@Param("factoryId") String factoryId,
                                                 @Param("productTypeId") String productTypeId,
                                                 @Param("excludedStatuses")
                                                 Collection<SalesOrderStatus> excludedStatuses,
                                                 org.springframework.data.domain.Pageable pageable);

    /** 售价趋势行投影 (B3). */
    interface PriceTrendRow {
        String getOrderNumber();
        java.time.LocalDate getOrderDate();
        java.math.BigDecimal getUnitPrice();
        java.math.BigDecimal getQuantity();
        String getUnit();
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
           "MIN(CASE WHEN i.packagingFactor IS NOT NULL AND i.unit = i.packagingUnit " +
           "THEN i.packagingBaseUnit ELSE i.unit END) AS minUnit, " +
           "MAX(CASE WHEN i.packagingFactor IS NOT NULL AND i.unit = i.packagingUnit " +
           "THEN i.packagingBaseUnit ELSE i.unit END) AS maxUnit, " +
           "SUM(CASE WHEN i.packagingFactor IS NOT NULL AND i.unit = i.packagingUnit " +
           "THEN i.quantity * i.packagingFactor ELSE i.quantity END) AS demand " +
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
           "MIN(CASE WHEN i.packagingFactor IS NOT NULL AND i.unit = i.packagingUnit " +
           "THEN i.packagingBaseUnit ELSE i.unit END) AS minUnit, " +
           "MAX(CASE WHEN i.packagingFactor IS NOT NULL AND i.unit = i.packagingUnit " +
           "THEN i.packagingBaseUnit ELSE i.unit END) AS maxUnit, " +
           "SUM(CASE WHEN i.packagingFactor IS NOT NULL AND i.unit = i.packagingUnit " +
           "THEN i.quantity * i.packagingFactor ELSE i.quantity END) AS demand " +
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

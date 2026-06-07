package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface FinishedGoodsBatchRepository extends JpaRepository<FinishedGoodsBatch, String> {

    Page<FinishedGoodsBatch> findByFactoryIdOrderByCreatedAtDesc(String factoryId, Pageable pageable);

    List<FinishedGoodsBatch> findByFactoryIdAndProductTypeIdAndStatus(String factoryId, String productTypeId, String status);

    Optional<FinishedGoodsBatch> findByFactoryIdAndBatchNumber(String factoryId, String batchNumber);

    /** 查询有可用库存的成品批次（FEFO 出库 — 先到期先出） */
    @Query("SELECT b FROM FinishedGoodsBatch b WHERE b.factoryId = :factoryId " +
            "AND b.productTypeId = :productTypeId AND b.status = 'AVAILABLE' " +
            "AND (b.producedQuantity - b.shippedQuantity - b.reservedQuantity) > 0 " +
            "ORDER BY b.expireDate ASC NULLS LAST, b.productionDate ASC")
    List<FinishedGoodsBatch> findAvailableBatches(
            @Param("factoryId") String factoryId,
            @Param("productTypeId") String productTypeId);

    /**
     * FEFO 出库（带 warehouse 过滤）。D1 双仓流转 (PR #309 A1=A, 2026-05-10 spec)。
     * 用途：销售批次预占/发货 (WH-LOG 固定)、调拨发货 (source warehouse)。
     */
    @Query("SELECT b FROM FinishedGoodsBatch b WHERE b.factoryId = :factoryId " +
            "AND b.productTypeId = :productTypeId " +
            "AND b.warehouseId = :warehouseId " +
            "AND b.status = 'AVAILABLE' " +
            "AND (b.producedQuantity - b.shippedQuantity - b.reservedQuantity) > 0 " +
            "ORDER BY b.expireDate ASC NULLS LAST, b.productionDate ASC")
    List<FinishedGoodsBatch> findAvailableBatchesByWarehouse(
            @Param("factoryId") String factoryId,
            @Param("productTypeId") String productTypeId,
            @Param("warehouseId") String warehouseId);

    /** FIFO 推荐：按生产日期升序返回可用成品批次（先进先出） */
    @Query("SELECT b FROM FinishedGoodsBatch b WHERE b.factoryId = :factoryId " +
            "AND b.productTypeId = :productTypeId AND b.status = 'AVAILABLE' " +
            "AND (b.producedQuantity - b.shippedQuantity - b.reservedQuantity) > 0 " +
            "ORDER BY b.productionDate ASC NULLS LAST")
    List<FinishedGoodsBatch> findAvailableBatchesFifo(
            @Param("factoryId") String factoryId,
            @Param("productTypeId") String productTypeId);

    /**
     * FIFO 推荐（带 warehouse 过滤）。D1 双仓流转 (PR #309 A1=A, 2026-05-10 spec)。
     */
    @Query("SELECT b FROM FinishedGoodsBatch b WHERE b.factoryId = :factoryId " +
            "AND b.productTypeId = :productTypeId " +
            "AND b.warehouseId = :warehouseId " +
            "AND b.status = 'AVAILABLE' " +
            "AND (b.producedQuantity - b.shippedQuantity - b.reservedQuantity) > 0 " +
            "ORDER BY b.productionDate ASC NULLS LAST")
    List<FinishedGoodsBatch> findAvailableBatchesFifoByWarehouse(
            @Param("factoryId") String factoryId,
            @Param("productTypeId") String productTypeId,
            @Param("warehouseId") String warehouseId);

    /** 汇总指定产品类型的可用成品库存总量（用于销售订单库存检查） */
    @Query("SELECT COALESCE(SUM(b.producedQuantity - b.shippedQuantity - b.reservedQuantity), 0) " +
            "FROM FinishedGoodsBatch b WHERE b.factoryId = :factoryId " +
            "AND b.productTypeId = :productTypeId AND b.status = 'AVAILABLE' " +
            "AND (b.producedQuantity - b.shippedQuantity - b.reservedQuantity) > 0")
    BigDecimal sumAvailableQuantityByProductType(
            @Param("factoryId") String factoryId,
            @Param("productTypeId") String productTypeId);

    /**
     * P4 防御: 仅汇总指定单位的可用成品库存 (备货看板用; 防止 kg/盒 混单位加和).
     * 在 P4 换算完成后, 只有 unit='盒' 的行参与备货看板成品列计算.
     */
    @Query("SELECT COALESCE(SUM(b.producedQuantity - b.shippedQuantity - b.reservedQuantity), 0) " +
            "FROM FinishedGoodsBatch b WHERE b.factoryId = :factoryId " +
            "AND b.productTypeId = :productTypeId AND b.status = 'AVAILABLE' " +
            "AND b.unit = :unit " +
            "AND (b.producedQuantity - b.shippedQuantity - b.reservedQuantity) > 0")
    BigDecimal sumAvailableQuantityByProductTypeAndUnit(
            @Param("factoryId") String factoryId,
            @Param("productTypeId") String productTypeId,
            @Param("unit") String unit);

    /**
     * 按 warehouse 过滤的可用成品库存汇总。D1 双仓流转 (PR #309 A1=A, 2026-05-10 spec)。
     * 用途：销售订单库存检查 (WH-LOG)、调拨单库存检查 (source warehouse)。
     */
    @Query("SELECT COALESCE(SUM(b.producedQuantity - b.shippedQuantity - b.reservedQuantity), 0) " +
            "FROM FinishedGoodsBatch b WHERE b.factoryId = :factoryId " +
            "AND b.productTypeId = :productTypeId " +
            "AND b.warehouseId = :warehouseId " +
            "AND b.status = 'AVAILABLE' " +
            "AND (b.producedQuantity - b.shippedQuantity - b.reservedQuantity) > 0")
    BigDecimal sumAvailableQuantityByProductTypeAndWarehouse(
            @Param("factoryId") String factoryId,
            @Param("productTypeId") String productTypeId,
            @Param("warehouseId") String warehouseId);

    /**
     * 查找 plan + warehouse 范围内的所有可用成品批次。D1 反向调拨触发 (PR #309 A3=A, 2026-05-10 spec)。
     *
     * <p>用途：报工触发计划完成后, 反向调拨编排查询该 plan 在 WH-WKS 生成的成品批次,
     * 聚合后作为 BRANCH_TO_HQ 调拨单 items.
     */
    @Query("SELECT b FROM FinishedGoodsBatch b WHERE b.factoryId = :factoryId " +
            "AND b.productionPlanId = :planId " +
            "AND b.warehouseId = :warehouseId " +
            "AND b.status = 'AVAILABLE' " +
            "AND (b.producedQuantity - b.shippedQuantity - b.reservedQuantity) > 0 " +
            "ORDER BY b.productTypeId ASC, b.expireDate ASC NULLS LAST, b.productionDate ASC")
    List<FinishedGoodsBatch> findAvailableByPlanAndWarehouse(
            @Param("factoryId") String factoryId,
            @Param("planId") String planId,
            @Param("warehouseId") String warehouseId);

    /**
     * A5 集团联销 (cross-factory sales) — 跨工厂可用成品库存汇总。
     *
     * <p>当 feature flag {@code cretas.sales.cross-factory.enabled=true} 时启用。
     * 当前实现：去掉 factoryId 过滤，允许所有工厂的批次参与库存匹配
     * （等价于"集团池"语义）。
     *
     * <p>未来当 {@code factory_network} 表落地后，此方法可以替换为
     * {@code WHERE b.factoryId IN (SELECT f.factoryId FROM FactoryNetwork f WHERE f.parent = :soFactoryId)}
     * 实现按销售组织受控的跨工厂汇总。
     *
     * @see com.cretas.aims.service.orchestration.InventoryMatchingService
     * @since 2026-05-10 PR #309 A5=C feature flag introduction
     */
    @Query("SELECT COALESCE(SUM(b.producedQuantity - b.shippedQuantity - b.reservedQuantity), 0) " +
            "FROM FinishedGoodsBatch b WHERE b.productTypeId = :productTypeId " +
            "AND b.status = 'AVAILABLE' " +
            "AND (b.producedQuantity - b.shippedQuantity - b.reservedQuantity) > 0")
    BigDecimal sumAvailableQuantityByProductTypeAllFactories(
            @Param("productTypeId") String productTypeId);

    /**
     * A5 集团联销 (cross-factory sales) — 跨工厂可用成品批次（FEFO）。
     *
     * <p>当 feature flag {@code cretas.sales.cross-factory.enabled=true} 时启用，
     * 跳过 factoryId 过滤。订单 FEFO 预留时跨工厂池中按到期日最早依次取。
     *
     * @see com.cretas.aims.service.orchestration.InventoryMatchingService
     * @since 2026-05-10 PR #309 A5=C feature flag introduction
     */
    @Query("SELECT b FROM FinishedGoodsBatch b WHERE b.productTypeId = :productTypeId " +
            "AND b.status = 'AVAILABLE' " +
            "AND (b.producedQuantity - b.shippedQuantity - b.reservedQuantity) > 0 " +
            "ORDER BY b.expireDate ASC NULLS LAST, b.productionDate ASC")
    List<FinishedGoodsBatch> findAvailableBatchesAllFactories(
            @Param("productTypeId") String productTypeId);

    /**
     * D5 (cross-factory sales + WH-LOG filter) — 跨工厂的 WH-LOG 可用成品库存汇总。
     *
     * <p>当 A5 feature flag {@code cretas.sales.cross-factory.enabled=true} 时使用：
     * 销售订单跨工厂匹配,但**仅**从各工厂总仓 (WH-LOG) 出货 (per D5 spec, 2026-05-11)。
     * 排除 WH-WKS 鲜棉仓（当天清仓,不参与销售）。
     *
     * <p>实现：JOIN {@code factory_warehouses} 表用 code='WH-LOG' 过滤,每个 factory
     * 都有 WH-LOG seed (V20260411_03 保证)。
     *
     * @see com.cretas.aims.service.orchestration.InventoryMatchingService
     * @since 2026-05-11 PR #316 D5 (sales from WH-LOG)
     */
    @Query("SELECT COALESCE(SUM(b.producedQuantity - b.shippedQuantity - b.reservedQuantity), 0) " +
            "FROM FinishedGoodsBatch b, com.cretas.aims.entity.factory.FactoryWarehouse w " +
            "WHERE b.warehouseId = w.id AND w.code = :warehouseCode " +
            "AND b.productTypeId = :productTypeId " +
            "AND b.status = 'AVAILABLE' " +
            "AND (b.producedQuantity - b.shippedQuantity - b.reservedQuantity) > 0")
    BigDecimal sumAvailableQuantityByProductTypeAllFactoriesAndWarehouseCode(
            @Param("productTypeId") String productTypeId,
            @Param("warehouseCode") String warehouseCode);

    /**
     * D5 (cross-factory sales + WH-LOG filter) — 跨工厂的 WH-LOG 可用成品批次（FEFO）。
     *
     * <p>当 A5 feature flag {@code cretas.sales.cross-factory.enabled=true} 时使用：
     * 销售订单 FEFO 预留时跨工厂池但**仅**取各工厂总仓 (WH-LOG) 批次。
     *
     * @see com.cretas.aims.service.orchestration.InventoryMatchingService
     * @since 2026-05-11 PR #316 D5 (sales from WH-LOG)
     */
    @Query("SELECT b FROM FinishedGoodsBatch b, com.cretas.aims.entity.factory.FactoryWarehouse w " +
            "WHERE b.warehouseId = w.id AND w.code = :warehouseCode " +
            "AND b.productTypeId = :productTypeId " +
            "AND b.status = 'AVAILABLE' " +
            "AND (b.producedQuantity - b.shippedQuantity - b.reservedQuantity) > 0 " +
            "ORDER BY b.expireDate ASC NULLS LAST, b.productionDate ASC")
    List<FinishedGoodsBatch> findAvailableBatchesAllFactoriesByWarehouseCode(
            @Param("productTypeId") String productTypeId,
            @Param("warehouseCode") String warehouseCode);

    /**
     * 分仓库存查询 (PR #309 B2=B, 2026-05-11 spec).
     * factory_id × warehouse_id composite index (idx_finished_batch_warehouse).
     * 默认 @Where(deleted_at IS NULL) 已在 entity 起作用。
     */
    List<FinishedGoodsBatch> findByFactoryIdAndWarehouseId(String factoryId, String warehouseId);

    /**
     * WH-LOG 可发量汇总（单工厂，按 warehouse code + unit 双过滤）。备货看板 T134 用。
     *
     * <p>物流仓（WH-LOG）是唯一参与销售发货的仓库（InventoryMatchingService 固定 WH-LOG）。
     * 车间仓（WH-WKS）成品不能直接发货，需先通过反向调拨转入 WH-LOG。此方法仅统计
     * WH-LOG 中单位为 '盒' 的可用成品，作为「可发量」展示给运营人员判断是否需要催调拨。
     *
     * <p>注意：此方法 {@code NOT} 替代 {@link #sumAvailableQuantityByProductTypeAndUnit} —— 后者
     * 统计全仓库（用于覆盖率计算），此方法仅统计 WH-LOG（用于可发量展示）。两个字段并存。
     *
     * @param factoryId      工厂 ID
     * @param productTypeId  产品类型 ID
     * @param warehouseCode  仓库 code，传 {@link com.cretas.aims.entity.factory.WarehouseCodes#WH_LOG}
     * @param unit           库存单位，传 {@code "盒"}
     * @since T134 (2026-06-08, feat/restock-wh-log-split)
     */
    @Query("SELECT COALESCE(SUM(b.producedQuantity - b.shippedQuantity - b.reservedQuantity), 0) " +
            "FROM FinishedGoodsBatch b, com.cretas.aims.entity.factory.FactoryWarehouse w " +
            "WHERE b.warehouseId = w.id AND w.factoryId = :factoryId AND w.code = :warehouseCode " +
            "AND b.factoryId = :factoryId " +
            "AND b.productTypeId = :productTypeId " +
            "AND b.unit = :unit " +
            "AND b.status = 'AVAILABLE' " +
            "AND (b.producedQuantity - b.shippedQuantity - b.reservedQuantity) > 0")
    BigDecimal sumShippableQuantityByProductTypeAndWarehouseCodeAndUnit(
            @Param("factoryId") String factoryId,
            @Param("productTypeId") String productTypeId,
            @Param("warehouseCode") String warehouseCode,
            @Param("unit") String unit);
}

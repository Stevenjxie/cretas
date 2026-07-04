package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface FinishedGoodsBatchRepository extends JpaRepository<FinishedGoodsBatch, String> {

    Page<FinishedGoodsBatch> findByFactoryIdOrderByCreatedAtDesc(String factoryId, Pageable pageable);

    List<FinishedGoodsBatch> findByFactoryIdAndProductTypeIdAndStatus(String factoryId, String productTypeId, String status);

    Optional<FinishedGoodsBatch> findByFactoryIdAndBatchNumber(String factoryId, String batchNumber);

    /**
     * ①c 成品作投料来源 — 悲观写锁按 (factory, batchNumber) 取单个成品批次, 供小结时严格扣减 (loud-fail)。
     *
     * <p>与 {@link #findByFactoryIdAndBatchNumber} 不同: PESSIMISTIC_WRITE 行锁串行化并发扣减,
     * 防超扣 (禁止降级)。必须在调用方 {@code @Transactional} 内使用。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM FinishedGoodsBatch b WHERE b.factoryId = :factoryId AND b.batchNumber = :batchNumber")
    Optional<FinishedGoodsBatch> findByFactoryIdAndBatchNumberForUpdate(
            @Param("factoryId") String factoryId,
            @Param("batchNumber") String batchNumber);

    /**
     * 撤销小结连带退库 — 悲观写锁按 (id, factory) 取单个成品批次 (TRF-child 同厂调拨子批)。
     *
     * <p>撤销小结时若源批次的一部分已被同厂调拨搬到别的仓 (生成 TRF-FG-* 子批, 见
     * {@code TransferServiceImpl#createTargetInventory}), 须锁定并一并退库 (拉回搬出量),
     * 否则撤销后物流仓残留孤儿库存。必须在调用方 {@code @Transactional} 内使用。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM FinishedGoodsBatch b WHERE b.id = :id AND b.factoryId = :factoryId")
    Optional<FinishedGoodsBatch> findByIdAndFactoryIdForUpdate(
            @Param("id") String id,
            @Param("factoryId") String factoryId);

    /**
     * ①c 成品作投料来源 — 该工厂全部 <b>可投料</b> 成品批次 (AVAILABLE 且可用量 &gt; 0), 供逐道录入 FG 投料下拉。
     *
     * <p>返回全 productType (调用方按产品族过滤); 按到期日 FEFO 排序 (先到期先用, 与销售出库一致)。
     * 可用量 = producedQuantity − shippedQuantity − reservedQuantity。
     */
    @Query("SELECT b FROM FinishedGoodsBatch b WHERE b.factoryId = :factoryId " +
            "AND b.status = 'AVAILABLE' " +
            "AND (b.producedQuantity - b.shippedQuantity - b.reservedQuantity) > 0 " +
            "ORDER BY b.expireDate ASC NULLS LAST, b.productionDate ASC")
    List<FinishedGoodsBatch> findAvailableForFeedByFactory(@Param("factoryId") String factoryId);

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

    /**
     * R6 #6 (2026-06-22): 发货可出库批次 (FEFO) —— 过滤条件用<b>物理未发量</b>
     * {@code (producedQuantity - shippedQuantity) > 0}, <b>不</b>减 reservedQuantity。
     *
     * <p>区别于 {@link #findAvailableBatchesByWarehouse} (后者减 reserved, 用于"还能预占/可卖给新订单"语义):
     * 发货时本 SO 自己预留的批次即使 {@code available=0} (被预留耗尽) 也应能发出 —— 发货会把 reserved 转 shipped。
     * 用于销售发货扣减, 让 {@code deductFinishedGoodsInventory} 先扣未预留 available、再动用本批预留物理库存。
     */
    @Query("SELECT b FROM FinishedGoodsBatch b WHERE b.factoryId = :factoryId " +
            "AND b.productTypeId = :productTypeId " +
            "AND b.warehouseId = :warehouseId " +
            "AND b.status = 'AVAILABLE' " +
            "AND (b.producedQuantity - b.shippedQuantity) > 0 " +
            "ORDER BY b.expireDate ASC NULLS LAST, b.productionDate ASC")
    List<FinishedGoodsBatch> findShippableBatchesByWarehouse(
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

    /**
     * 🔴 G1 (2026-07-03): 发货批次推荐 <b>跨全部可售仓库</b> (FEFO), 仅排除研发/中试库 (WH-RD)。
     *
     * <p><b>为什么</b>: 发货行 {@code sourceWarehouseCode} 为空 (最常见) 时, 旧逻辑硬默认单一 WH-LOG 仓,
     * 但成品实际可能在车间仓 (WH-WKS, 生产落点)、成品仓 (FINISHED)、物流仓 (WH-LOG, 调拨落点) 等任一仓 —
     * 单仓严格过滤 → 明明有货却返回空 → 用户无法出货 (Steve #1 named bug)。
     * 未声明来源仓 = 无显式约束 → 应在所有<b>可售</b>仓库中发现库存。
     *
     * <p>排除 WH-RD: 试制批次 (is_trial) 不混入可售库存 (SP10 §RD-1, 与
     * {@link #sumSaleableQuantityByProductTypeAndUnitExcludeRd} 口径一致)。
     *
     * <p>可用量 = producedQuantity − shippedQuantity − reservedQuantity (与单仓推荐口径一致)。
     * FEFO 排序 (先到期先出), 跨仓统一排序。
     */
    @Query("SELECT b FROM FinishedGoodsBatch b, com.cretas.aims.entity.factory.FactoryWarehouse w " +
            "WHERE b.warehouseId = w.id AND w.factoryId = :factoryId AND w.code <> :excludeWarehouseCode " +
            "AND b.factoryId = :factoryId " +
            "AND b.productTypeId = :productTypeId " +
            "AND b.status = 'AVAILABLE' " +
            "AND (b.producedQuantity - b.shippedQuantity - b.reservedQuantity) > 0 " +
            "ORDER BY b.expireDate ASC NULLS LAST, b.productionDate ASC")
    List<FinishedGoodsBatch> findAvailableBatchesFefoAllWarehousesExcluding(
            @Param("factoryId") String factoryId,
            @Param("productTypeId") String productTypeId,
            @Param("excludeWarehouseCode") String excludeWarehouseCode);

    /**
     * 🔴 G1 (2026-07-03): 发货扣减候选批次 <b>跨全部可售仓库</b> (FEFO), 仅排除 WH-RD。
     *
     * <p>{@code deductFinishedGoodsInventory} 在发货行来源仓为空时的候选集 —— 与
     * {@link #findAvailableBatchesFefoAllWarehousesExcluding} (推荐) 同仓范围, 保证
     * 「推荐能选 → 分配能过 → 发货能扣」三段一致, 不再出现推荐列出却扣减失败。
     *
     * <p>过滤用<b>物理未发量</b> {@code (producedQuantity - shippedQuantity) > 0} (不减 reserved),
     * 与 {@link #findShippableBatchesByWarehouse} 一致 (R6 #6: 让被本 SO 预留耗尽的批次也能发出)。
     */
    @Query("SELECT b FROM FinishedGoodsBatch b, com.cretas.aims.entity.factory.FactoryWarehouse w " +
            "WHERE b.warehouseId = w.id AND w.factoryId = :factoryId AND w.code <> :excludeWarehouseCode " +
            "AND b.factoryId = :factoryId " +
            "AND b.productTypeId = :productTypeId " +
            "AND b.status = 'AVAILABLE' " +
            "AND (b.producedQuantity - b.shippedQuantity) > 0 " +
            "ORDER BY b.expireDate ASC NULLS LAST, b.productionDate ASC")
    List<FinishedGoodsBatch> findShippableBatchesAllWarehousesExcluding(
            @Param("factoryId") String factoryId,
            @Param("productTypeId") String productTypeId,
            @Param("excludeWarehouseCode") String excludeWarehouseCode);

    /**
     * 🔴 G1 (2026-07-03): 该产品有可用成品库存的<b>仓库 code 清单</b> (去重, 排除 WH-RD)。
     *
     * <p>诚实空态用: 当发货行<b>显式</b>声明的来源仓无货、但成品在其他仓库时, 前端据此提示
     * 「成品在 X 仓, 当前来源仓 Y — 请改选来源仓或调拨」而不是误导的「请先生产入库」(fool-proof Rule 5)。
     */
    @Query("SELECT DISTINCT w.code FROM FinishedGoodsBatch b, com.cretas.aims.entity.factory.FactoryWarehouse w " +
            "WHERE b.warehouseId = w.id AND w.factoryId = :factoryId AND w.code <> :excludeWarehouseCode " +
            "AND b.factoryId = :factoryId " +
            "AND b.productTypeId = :productTypeId " +
            "AND b.status = 'AVAILABLE' " +
            "AND (b.producedQuantity - b.shippedQuantity - b.reservedQuantity) > 0")
    List<String> findWarehouseCodesWithAvailableStock(
            @Param("factoryId") String factoryId,
            @Param("productTypeId") String productTypeId,
            @Param("excludeWarehouseCode") String excludeWarehouseCode);

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
     * SP2 整单撤回 Guard #2: 检查某 plan 是否已有成品被出货 (shippedQuantity > 0).
     * FGB 通过 productionPlanId (String) 关联生产计划.
     * 注意: 使用列侧 IS NULL check, 不用 CAST (列类型 PG 已知, 非参数侧).
     */
    @Query("SELECT CASE WHEN COUNT(b) > 0 THEN true ELSE false END " +
            "FROM FinishedGoodsBatch b WHERE b.factoryId = :factoryId " +
            "AND b.productionPlanId = :planId AND b.shippedQuantity > 0 " +
            "AND b.deletedAt IS NULL")
    boolean existsShippedByFactoryIdAndProductionPlanId(
            @Param("factoryId") String factoryId,
            @Param("planId") String planId);

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
     * SP2 撤回: 查找某计划下的所有成品批次（用于标记 REVERSED 状态）.
     * FinishedGoodsBatch 继承 BaseEntity, @Where(deleted_at IS NULL) 已生效,
     * 但 SP2 需要精确控制所以用 @Query 显式加 deletedAt IS NULL 守护.
     *
     * @since SP2 (2026-06-10, feat/liushanmen-sp2-reversal)
     */
    @Query("SELECT b FROM FinishedGoodsBatch b WHERE b.factoryId = :factoryId " +
            "AND b.productionPlanId = :planId AND b.deletedAt IS NULL")
    List<FinishedGoodsBatch> findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(
            @Param("factoryId") String factoryId,
            @Param("planId") String planId);

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

    /**
     * SP10 §RD-1: 汇总可售成品库存，排除研发/中试库 (warehouse code = WH-RD).
     *
     * <p>备货看板覆盖率计算用。仅统计非研发仓库的指定单位可用量，确保试制产出
     * (进研发库) 不干扰生产计划决策。
     *
     * <p>注意: {@link #sumAvailableQuantityByProductTypeAndUnit} 不过滤研发库，
     * 保留给研发查阅；此方法是备货看板入口的正确调用。
     *
     * @param factoryId      工厂 ID
     * @param productTypeId  产品类型 ID
     * @param unit           库存单位，传 {@code "盒"}
     * @param rdWarehouseCode 研发库 code（传 {@link com.cretas.aims.entity.factory.WarehouseCodes#WH_RD}），用于 JOIN 排除
     * @since SP10 §RD-1 (V20261023_01)
     */
    @Query("SELECT COALESCE(SUM(b.producedQuantity - b.shippedQuantity - b.reservedQuantity), 0) " +
            "FROM FinishedGoodsBatch b, com.cretas.aims.entity.factory.FactoryWarehouse w " +
            "WHERE b.warehouseId = w.id AND w.factoryId = :factoryId AND w.code <> :rdWarehouseCode " +
            "AND b.factoryId = :factoryId " +
            "AND b.productTypeId = :productTypeId " +
            "AND b.unit = :unit " +
            "AND b.status = 'AVAILABLE' " +
            "AND (b.producedQuantity - b.shippedQuantity - b.reservedQuantity) > 0")
    BigDecimal sumSaleableQuantityByProductTypeAndUnitExcludeRd(
            @Param("factoryId") String factoryId,
            @Param("productTypeId") String productTypeId,
            @Param("unit") String unit,
            @Param("rdWarehouseCode") String rdWarehouseCode);
}

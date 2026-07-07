package com.cretas.aims.repository;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Optional;

/**
 * 生产批次数据访问接口
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Repository
public interface ProductionBatchRepository extends JpaRepository<ProductionBatch, Long> {

    /**
     * 根据工厂ID和批次号查找
     */
    Optional<ProductionBatch> findByFactoryIdAndBatchNumber(String factoryId, String batchNumber);

    /**
     * 根据ID和工厂ID查找
     */
    Optional<ProductionBatch> findByIdAndFactoryId(Long id, String factoryId);

    /**
     * 检查批次号是否存在
     */
    boolean existsByFactoryIdAndBatchNumber(String factoryId, String batchNumber);

    /**
     * 分页查找工厂的生产批次 (排除 CLERK_WIP 内部工件批次).
     * SP-D Fix 1a: 批次列表不展示文员录入产生的中间 WIP 批次, 防止用户混淆.
     */
    @Query("SELECT p FROM ProductionBatch p WHERE p.factoryId = :factoryId AND p.batchType <> 'CLERK_WIP' " +
           "ORDER BY p.createdAt DESC")
    Page<ProductionBatch> findByFactoryId(@Param("factoryId") String factoryId, Pageable pageable);

    @Query("SELECT p FROM ProductionBatch p WHERE p.factoryId = :factoryId " +
           "ORDER BY p.createdAt DESC")
    Page<ProductionBatch> findByFactoryIdIncludingClerkWip(@Param("factoryId") String factoryId, Pageable pageable);

    /**
     * 根据状态分页查找 (排除 CLERK_WIP 内部工件批次, 与 {@link #findByFactoryId} 口径统一).
     * SP-D Fix 1b: 有状态过滤时同样不展示文员录入产生的中间 WIP 批次, 避免切换状态筛选时行为不一致.
     */
    @Query("SELECT p FROM ProductionBatch p WHERE p.factoryId = :factoryId AND p.status = :status " +
           "AND p.batchType <> 'CLERK_WIP'")
    Page<ProductionBatch> findByFactoryIdAndStatus(@Param("factoryId") String factoryId,
            @Param("status") ProductionBatchStatus status, Pageable pageable);

    @Query("SELECT p FROM ProductionBatch p WHERE p.factoryId = :factoryId AND p.status = :status")
    Page<ProductionBatch> findByFactoryIdAndStatusIncludingClerkWip(@Param("factoryId") String factoryId,
            @Param("status") ProductionBatchStatus status, Pageable pageable);

    /**
     * SP10: 按试制标记分页查找 (中报价试制批次下拉)
     */
    Page<ProductionBatch> findByFactoryIdAndIsTrial(String factoryId, Boolean isTrial, Pageable pageable);

    /**
     * SP10: 按状态 + 试制标记分页查找
     */
    Page<ProductionBatch> findByFactoryIdAndStatusAndIsTrial(String factoryId, ProductionBatchStatus status, Boolean isTrial, Pageable pageable);

    /**
     * 根据工厂ID和主管ID分页查找
     */
    Page<ProductionBatch> findByFactoryIdAndSupervisorId(String factoryId, Long supervisorId, Pageable pageable);

    @Query("SELECT p FROM ProductionBatch p WHERE p.factoryId = :factoryId AND p.supervisorId = :supervisorId")
    Page<ProductionBatch> findByFactoryIdAndSupervisorIdIncludingClerkWip(
            @Param("factoryId") String factoryId,
            @Param("supervisorId") Long supervisorId,
            Pageable pageable);

    /**
     * 根据工厂ID、主管ID和状态分页查找
     */
    Page<ProductionBatch> findByFactoryIdAndSupervisorIdAndStatus(String factoryId, Long supervisorId, ProductionBatchStatus status, Pageable pageable);

    @Query("SELECT p FROM ProductionBatch p WHERE p.factoryId = :factoryId AND p.supervisorId = :supervisorId AND p.status = :status")
    Page<ProductionBatch> findByFactoryIdAndSupervisorIdAndStatusIncludingClerkWip(
            @Param("factoryId") String factoryId,
            @Param("supervisorId") Long supervisorId,
            @Param("status") ProductionBatchStatus status,
            Pageable pageable);

    /**
     * 查询时间范围内的批次
     */
    java.util.List<ProductionBatch> findByFactoryIdAndCreatedAtBetween(
            String factoryId,
            LocalDateTime startDate,
            LocalDateTime endDate);

    /**
     * 统计某时间后的批次数 (排除 CLERK_WIP 内部工件批次).
     * SP-D Fix 1a: CLERK_WIP 批次不应计入今日批次数等仪表盘指标.
     */
    @Query("SELECT COUNT(p) FROM ProductionBatch p WHERE p.factoryId = :factoryId " +
           "AND p.createdAt > :createdAt AND p.batchType <> 'CLERK_WIP'")
    long countByFactoryIdAndCreatedAtAfter(@Param("factoryId") String factoryId,
                                           @Param("createdAt") LocalDateTime createdAt);

    /**
     * 查询某时间后的所有批次
     */
    java.util.List<ProductionBatch> findByFactoryIdAndCreatedAtAfter(String factoryId, LocalDateTime createdAt);

    /**
     * 统计某状态的批次数 (排除 CLERK_WIP 内部工件批次, 避免虚增仪表盘指标).
     * SP-D Fix 1a: CLERK_WIP 批次是文员录入的中间半成品, 不应计入面向用户的统计.
     */
    @Query("SELECT COUNT(p) FROM ProductionBatch p WHERE p.factoryId = :factoryId " +
           "AND p.status = :status AND p.batchType <> 'CLERK_WIP'")
    long countByFactoryIdAndStatus(@Param("factoryId") String factoryId,
                                   @Param("status") ProductionBatchStatus status);

    /**
     * 统计指定时间范围内的批次数
     */
    long countByFactoryIdAndCreatedAtBetween(String factoryId, LocalDateTime startTime, LocalDateTime endTime);

    /**
     * 统计指定工厂、指定状态、指定时间范围内的批次数量
     */
    long countByFactoryIdAndStatusAndCreatedAtBetween(String factoryId, ProductionBatchStatus status,
                                                       LocalDateTime startTime, LocalDateTime endTime);

    /**
     * 计算指定时间范围内的总产量
     */
    @Query("SELECT SUM(p.actualQuantity) FROM ProductionBatch p WHERE p.factoryId = :factoryId " +
           "AND p.createdAt >= :startTime AND p.createdAt < :endTime AND p.status = 'COMPLETED'")
    BigDecimal calculateTotalOutputBetween(@Param("factoryId") String factoryId,
                                           @Param("startTime") LocalDateTime startTime,
                                           @Param("endTime") LocalDateTime endTime);

    /**
     * 计算指定时间范围内已完成批次的平均生产周期(分钟).
     * 仅统计 start_time/end_time 都非空的 COMPLETED 批次. 无符合批次返 null.
     * native query: PostgreSQL EXTRACT(EPOCH FROM interval) 在 JPQL 里不稳定, 用 native.
     */
    @Query(value = "SELECT AVG(EXTRACT(EPOCH FROM (end_time - start_time)) / 60.0) " +
           "FROM production_batches WHERE factory_id = :factoryId AND status = 'COMPLETED' " +
           "AND start_time IS NOT NULL AND end_time IS NOT NULL " +
           "AND end_time >= start_time " +
           "AND start_time >= :startTime AND start_time < :endTime", nativeQuery = true)
    Double calculateAvgCycleTimeMinutes(@Param("factoryId") String factoryId,
                                        @Param("startTime") LocalDateTime startTime,
                                        @Param("endTime") LocalDateTime endTime);

    /**
     * 统计指定工厂、指定状态、指定时间后创建的批次数量
     * @param factoryId 工厂ID
     * @param status 批次状态（枚举）
     * @param createdAt 创建时间起点
     * @return 批次数量
     */
    long countByFactoryIdAndStatusAndCreatedAtAfter(String factoryId, ProductionBatchStatus status, LocalDateTime createdAt);

    /**
     * 计算某月产量
     */
    @Query("SELECT SUM(p.actualQuantity) FROM ProductionBatch p WHERE p.factoryId = :factoryId " +
           "AND p.createdAt >= :startDate AND p.status = 'COMPLETED'")
    BigDecimal calculateMonthlyOutput(@Param("factoryId") String factoryId,
                                      @Param("startDate") LocalDateTime startDate);

    /**
     * 计算平均良品率
     */
    @Query("SELECT AVG(p.yieldRate) FROM ProductionBatch p WHERE p.factoryId = :factoryId " +
           "AND p.createdAt >= :startDate AND p.status = 'COMPLETED'")
    BigDecimal calculateAverageYieldRate(@Param("factoryId") String factoryId,
                                         @Param("startDate") LocalDateTime startDate);

    /**
     * 计算某时间后的总产量 (排除 CLERK_WIP 内部工件批次).
     * SP-D Fix 1a: CLERK_WIP 中间批次的 actualQuantity 是 WIP 产出, 不是最终产品产量, 不应纳入汇总.
     */
    @Query("SELECT SUM(p.actualQuantity) FROM ProductionBatch p WHERE p.factoryId = :factoryId " +
           "AND p.createdAt >= :startDate AND p.batchType <> 'CLERK_WIP'")
    BigDecimal calculateTotalOutputAfter(@Param("factoryId") String factoryId,
                                         @Param("startDate") LocalDateTime startDate);

    /**
     * 计算某时间后的总成本
     */
    @Query("SELECT SUM(p.totalCost) FROM ProductionBatch p WHERE p.factoryId = :factoryId " +
           "AND p.createdAt >= :startDate")
    BigDecimal calculateTotalCostAfter(@Param("factoryId") String factoryId,
                                       @Param("startDate") LocalDateTime startDate);

    /**
     * 计算平均效率
     */
    @Query("SELECT AVG(p.efficiency) FROM ProductionBatch p WHERE p.factoryId = :factoryId " +
           "AND p.createdAt >= :startDate AND p.status = 'COMPLETED'")
    BigDecimal calculateAverageEfficiency(@Param("factoryId") String factoryId,
                                          @Param("startDate") LocalDateTime startDate);

    /**
     * 计算每日产量
     */
    @Query("SELECT SUM(p.actualQuantity) FROM ProductionBatch p WHERE p.factoryId = :factoryId " +
           "AND p.createdAt >= :startDate AND p.createdAt <= :endDate")
    BigDecimal calculateDailyOutput(@Param("factoryId") String factoryId,
                                   @Param("startDate") LocalDateTime startDate,
                                   @Param("endDate") LocalDateTime endDate);

    /**
     * 计算每日良品率
     */
    @Query("SELECT AVG(p.yieldRate) FROM ProductionBatch p WHERE p.factoryId = :factoryId " +
           "AND p.createdAt >= :startDate AND p.createdAt <= :endDate AND p.status = 'COMPLETED'")
    BigDecimal calculateDailyYieldRate(@Param("factoryId") String factoryId,
                                       @Param("startDate") LocalDateTime startDate,
                                       @Param("endDate") LocalDateTime endDate);

    /**
     * 计算每日成本
     */
    @Query("SELECT SUM(p.totalCost) FROM ProductionBatch p WHERE p.factoryId = :factoryId " +
           "AND p.createdAt >= :startDate AND p.createdAt <= :endDate")
    BigDecimal calculateDailyCost(@Param("factoryId") String factoryId,
                                  @Param("startDate") LocalDateTime startDate,
                                  @Param("endDate") LocalDateTime endDate);

    // ========== 扩展查询方法 ==========

    /**
     * 按工厂ID查询（不分页）
     */
    java.util.List<ProductionBatch> findByFactoryId(String factoryId);

    /**
     * 统计指定工厂的批次数量
     */
    long countByFactoryId(String factoryId);

    /**
     * 按创建时间范围查询（跨所有工厂，用于平台统计）
     */
    java.util.List<ProductionBatch> findByCreatedAtBetween(LocalDateTime startTime, LocalDateTime endTime);

    /**
     * 统计指定状态的批次数量（跨所有工厂）
     */
    long countByStatus(ProductionBatchStatus status);

    /**
     * 获取时间范围内的批次（用于成本分析和绩效计算）
     */
    @Query("SELECT b FROM ProductionBatch b WHERE b.factoryId = :factoryId AND b.startTime BETWEEN :startTime AND :endTime ORDER BY b.startTime DESC")
    java.util.List<ProductionBatch> findBatchesInDateRange(@Param("factoryId") String factoryId,
                                                           @Param("startTime") LocalDateTime startTime,
                                                           @Param("endTime") LocalDateTime endTime);

    /**
     * 查询已完成且有开始和结束时间的批次（用于计算平均生产周期）
     */
    @Query("SELECT b FROM ProductionBatch b WHERE b.factoryId = :factoryId AND b.status = 'COMPLETED' " +
           "AND b.startTime IS NOT NULL AND b.endTime IS NOT NULL AND b.createdAt >= :startDate")
    java.util.List<ProductionBatch> findCompletedBatchesWithTimes(@Param("factoryId") String factoryId,
                                                                   @Param("startDate") LocalDateTime startDate);

    /**
     * 根据批次号查找（**仅用于公开溯源查询**, trace_public tool 等）.
     *
     * @deprecated 对于内部业务 tool, 请使用 L30 的 {@code findByFactoryIdAndBatchNumber}
     *             (Spring Data derived method) 避免跨工厂数据泄露. 保留此 method
     *             仅为兼容 trace_public (消费者扫码 C 端).
     */
    @Deprecated
    @Query("SELECT b FROM ProductionBatch b WHERE b.batchNumber = :batchNumber")
    Optional<ProductionBatch> findByBatchNumber(@Param("batchNumber") String batchNumber);

    /**
     * 批量查询多个生产批次 - 解决 N+1 查询问题
     * @param ids 生产批次ID集合
     * @return 生产批次列表
     */
    @Query("SELECT b FROM ProductionBatch b WHERE b.id IN :ids")
    java.util.List<ProductionBatch> findByIdIn(@Param("ids") Collection<Long> ids);

    /**
     * 批量查询多个生产批次（带工厂ID过滤）- 解决 N+1 查询问题
     * @param ids 生产批次ID集合
     * @param factoryId 工厂ID
     * @return 生产批次列表
     */
    @Query("SELECT b FROM ProductionBatch b WHERE b.id IN :ids AND b.factoryId = :factoryId")
    java.util.List<ProductionBatch> findByIdInAndFactoryId(@Param("ids") Collection<Long> ids, @Param("factoryId") String factoryId);

    /**
     * 单元 F (F006 REQ-21): 查工厂下属于一组生产计划的全部批次 (按订单聚合出成率用).
     * 调用方先用 {@code ProductionPlanRepository.findByFactoryIdAndSourceOrderId} 取订单的计划 ids,
     * 再用本方法取这些计划下的全部批次。空集合 → 空列表 (Spring Data IN-空 行为)。
     *
     * @param factoryId 工厂ID (工厂隔离)
     * @param planIds   生产计划ID集合
     * @return 这些计划下的全部生产批次
     */
    @Query("SELECT b FROM ProductionBatch b WHERE b.factoryId = :factoryId AND b.productionPlanId IN :planIds")
    java.util.List<ProductionBatch> findByFactoryIdAndProductionPlanIdIn(
            @Param("factoryId") String factoryId, @Param("planIds") Collection<String> planIds);

    /**
     * 完工链 GAP 6 (F006 — 2026-06-02): 查某工厂下关联指定生产计划的全部批次.
     * 计划级 {@code completeProduction} 用此方法找到关联批次并级联完成 + 发 BatchCompletedEvent (建成品).
     *
     * <p>命名遵循本仓库既有约定 (其它 finder 均不带 {@code AndDeletedAtIsNull} 后缀);
     * 调用方依赖批次 status 守卫, 不依赖软删除过滤。空 → 空列表。
     *
     * @param factoryId        工厂ID (工厂隔离)
     * @param productionPlanId 生产计划ID
     * @return 关联该计划的全部生产批次
     */
    java.util.List<ProductionBatch> findByFactoryIdAndProductionPlanId(
            String factoryId, String productionPlanId);

    /**
     * 三价对比看板 (per-SKU): 某产品最近一条已算出 {@code unitCost} 的批次 (按创建时间倒序).
     *
     * <p>{@code unitCost} 由 {@link ProductionBatch#calculateMetrics()} 诚实计算 (跨单位/无成本
     * 输入时留 null, 见实体注释) — 本查询只取有值的行, 调用方用最新一条作为「实际成本」看板取数,
     * 空列表即诚实缺数据 (不臆造 0)。排除 CLERK_WIP 内部工件批次, 与 {@link #findByFactoryId} 口径统一
     * (batchType 允许 null, 与既有排除写法不同 — 避免历史空 batchType 记录被误排除)。
     *
     * @param factoryId     工厂ID
     * @param productTypeId 产品类型ID
     * @param pageable      调用方传 {@code PageRequest.of(0, 1)} 只取最近一条
     * @return 按 createdAt 倒序的批次列表 (通常调用方只用第 0 条)
     */
    @Query("SELECT p FROM ProductionBatch p WHERE p.factoryId = :factoryId AND p.productTypeId = :productTypeId " +
           "AND p.unitCost IS NOT NULL AND (p.batchType IS NULL OR p.batchType <> 'CLERK_WIP') " +
           "ORDER BY p.createdAt DESC")
    java.util.List<ProductionBatch> findRecentPricedBatches(
            @Param("factoryId") String factoryId,
            @Param("productTypeId") String productTypeId,
            Pageable pageable);

    /**
     * 统计关联生产计划中未完成的批次数量
     * 用于供应链联动：批次报工后判断PP是否全部完成
     *
     * @param productionPlanId 生产计划ID
     * @param status 排除的状态（通常为 COMPLETED）
     * @return 未处于指定状态的批次数量
     */
    long countByProductionPlanIdAndStatusNot(String productionPlanId, ProductionBatchStatus status);

    /**
     * 按产品类型统计生产数量
     * 返回: [productTypeId, productName, SUM(actualQuantity), unit]
     *
     * @param factoryId 工厂ID
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 统计结果列表
     */
    @Query("SELECT b.productTypeId, b.productName, SUM(b.actualQuantity), b.unit " +
           "FROM ProductionBatch b " +
           "WHERE b.factoryId = :factoryId " +
           "AND b.status = 'COMPLETED' " +
           "AND b.createdAt >= :startTime " +
           "AND b.createdAt < :endTime " +
           "GROUP BY b.productTypeId, b.productName, b.unit " +
           "ORDER BY SUM(b.actualQuantity) DESC")
    java.util.List<Object[]> findProductionByProduct(
            @Param("factoryId") String factoryId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * 查询工厂最近已完成的生产批次（最多50条）
     */
    @Query("SELECT b FROM ProductionBatch b WHERE b.factoryId = :factoryId AND b.status = 'COMPLETED' " +
           "ORDER BY b.endTime DESC NULLS LAST")
    java.util.List<ProductionBatch> findRecentCompletedByFactory(
            @Param("factoryId") String factoryId,
            org.springframework.data.domain.Pageable pageable);

    default java.util.List<ProductionBatch> findRecentCompletedByFactory(String factoryId) {
        return findRecentCompletedByFactory(factoryId,
                org.springframework.data.domain.PageRequest.of(0, 50));
    }

    /**
     * 查询工厂下某产品最近 N 个已完成批次 (按 endTime 倒序, endTime null 降最后).
     *
     * <p>BOM 出成率评估服务 ({@link com.cretas.aims.service.bom.impl.BomYieldEstimateServiceImpl})
     * 用此方法取最近 10 个批次计算 P50 中位数建议出成率.
     *
     * @param factoryId     工厂 ID (租户隔离)
     * @param productTypeId 产品类型 ID
     * @param pageable      分页 (通常 PageRequest.of(0, 10))
     * @return 已完成批次列表, 按 endTime DESC NULLS LAST
     */
    @Query("SELECT b FROM ProductionBatch b WHERE b.factoryId = :factoryId "
            + "AND b.productTypeId = :productTypeId AND b.status = 'COMPLETED' "
            + "ORDER BY b.endTime DESC NULLS LAST")
    java.util.List<ProductionBatch> findRecentCompletedByFactoryAndProductType(
            @Param("factoryId") String factoryId,
            @Param("productTypeId") String productTypeId,
            org.springframework.data.domain.Pageable pageable);

    /**
     * SP9-M3: 查询时间区间内已完工批次, 可选产品类型过滤.
     *
     * <p>CAST(:productTypeId AS string) IS NULL 处理可选过滤参数 (PG 严格类型推断兜底).
     * 结果按 createdAt 倒序.</p>
     *
     * @param factoryId     工厂 ID (租户隔离)
     * @param startDate     区间开始 (inclusive)
     * @param endDate       区间结束 (inclusive, end-of-day)
     * @param productTypeId 产品类型 ID 过滤 (null = 全部)
     */
    @Query("SELECT b FROM ProductionBatch b WHERE b.factoryId = :factoryId "
            + "AND b.status = 'COMPLETED' "
            + "AND b.createdAt >= :startDate AND b.createdAt <= :endDate "
            + "AND (CAST(:productTypeId AS string) IS NULL OR b.productTypeId = :productTypeId) "
            + "ORDER BY b.createdAt DESC")
    java.util.List<ProductionBatch> findCompletedBatchesForLaborComparison(
            @Param("factoryId") String factoryId,
            @Param("startDate") java.time.LocalDateTime startDate,
            @Param("endDate") java.time.LocalDateTime endDate,
            @Param("productTypeId") String productTypeId);

    /**
     * 成品出厂核算 — 已完工批次列表 (含关联订单 ID).
     *
     * <p>LEFT JOIN production_plans 获取 source_order_id (存货生产无关联订单时为 null).
     * 只返回非试产 (is_trial=false)、非文员WIP (batchType &lt;&gt; 'CLERK_WIP') 的正式批次.
     * 可选按 productTypeId 过滤.
     *
     * @param factoryId     工厂 ID (租户隔离)
     * @param since         完工时间下限 (endTime &gt;= since)
     * @param productTypeId 产品类型过滤 (null = 全部)
     * @return Object[] 每行: [batchNumber, orderId, productName, plannedQty, actualQty, unit, endTime, totalCost]
     */
    @Query(value = "SELECT b.batch_number, pp.source_order_id, b.product_name, " +
                   "       b.planned_quantity, b.actual_quantity, b.unit, b.end_time, b.total_cost " +
                   "FROM production_batches b " +
                   "LEFT JOIN production_plans pp ON pp.id::text = b.production_plan_id " +
                   "WHERE b.factory_id = :factoryId " +
                   "  AND b.status = 'COMPLETED' " +
                   "  AND b.batch_type <> 'CLERK_WIP' " +
                   "  AND b.is_trial = false " +
                   "  AND b.deleted_at IS NULL " +
                   "  AND b.end_time >= :since " +
                   "  AND (CAST(:productTypeId AS text) IS NULL OR b.product_type_id = :productTypeId) " +
                   "ORDER BY b.end_time DESC NULLS LAST",
           nativeQuery = true)
    java.util.List<Object[]> findFinishedBatchSummaries(
            @Param("factoryId") String factoryId,
            @Param("since") java.time.LocalDateTime since,
            @Param("productTypeId") String productTypeId);
}

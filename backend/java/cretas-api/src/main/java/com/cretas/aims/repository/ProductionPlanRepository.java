package com.cretas.aims.repository;

import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.enums.ProductionPlanType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 生产计划数据访问接口
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Repository
public interface ProductionPlanRepository extends JpaRepository<ProductionPlan, String> {

    /**
     * 根据计划编号查找
     */
    Optional<ProductionPlan> findByPlanNumber(String planNumber);

    /**
     * 根据工厂ID和计划编号查找（工厂隔离）
     */
    Optional<ProductionPlan> findByFactoryIdAndPlanNumber(String factoryId, String planNumber);

    /**
     * 根据ID和工厂ID查找（工厂隔离）
     */
    Optional<ProductionPlan> findByIdAndFactoryId(String id, String factoryId);

    /**
     * 查找工厂的所有生产计划
     */
    List<ProductionPlan> findByFactoryId(String factoryId);

    /**
     * 查找工厂的生产计划（分页）
     */
    Page<ProductionPlan> findByFactoryId(String factoryId, Pageable pageable);

    /**
     * 根据状态查找生产计划
     */
    List<ProductionPlan> findByFactoryIdAndStatus(String factoryId, ProductionPlanStatus status);

    /**
     * 根据状态查找生产计划（分页）
     */
    Page<ProductionPlan> findByFactoryIdAndStatus(String factoryId, ProductionPlanStatus status, Pageable pageable);

    /**
     * 查找指定日期范围内的生产计划
     * 暂时注释 - 数据库表中没有planned_date字段
     */
    // @Query("SELECT p FROM ProductionPlan p WHERE p.factoryId = :factoryId " +
    //        "AND p.plannedDate BETWEEN :startDate AND :endDate")
    // List<ProductionPlan> findByDateRange(@Param("factoryId") String factoryId,
    //                                      @Param("startDate") LocalDate startDate,
    //                                      @Param("endDate") LocalDate endDate);

    /**
     * 查找今日的生产计划
     * 暂时注释 - 数据库表中没有planned_date字段
     */
    // @Query("SELECT p FROM ProductionPlan p WHERE p.factoryId = :factoryId " +
    //        "AND p.plannedDate = CURRENT_DATE")
    // List<ProductionPlan> findTodayPlans(@Param("factoryId") String factoryId);

    /**
     * 根据产品类型查找生产计划
     */
    List<ProductionPlan> findByFactoryIdAndProductTypeId(String factoryId, String productTypeId);

    /**
     * 根据客户订单号查找
     */
    Optional<ProductionPlan> findByCustomerOrderNumber(String customerOrderNumber);

    /**
     * 统计生产计划状态
     */
    /**
     * 交货预警查询 (M-DELIVERY-WARN-1, Sprint 4 W2).
     * 查找当前未完成且 expectedCompletionDate &lt; today + windowDays 的生产计划。
     * 包括已超期 (expectedCompletionDate &lt; today) — 由 Service 分级。
     */
    @Query("SELECT p FROM ProductionPlan p " +
           "WHERE p.factoryId = :factoryId " +
           "AND p.expectedCompletionDate IS NOT NULL " +
           "AND p.expectedCompletionDate < :upperBound " +
           "AND p.status NOT IN ('COMPLETED', 'CANCELLED') " +
           "ORDER BY p.expectedCompletionDate ASC NULLS LAST")
    List<ProductionPlan> findDeliveryWarnPlans(@Param("factoryId") String factoryId,
                                                @Param("upperBound") java.time.LocalDate upperBound);

    @Query("SELECT p.status, COUNT(p) FROM ProductionPlan p " +
           "WHERE p.factoryId = :factoryId GROUP BY p.status")
    List<Object[]> countByStatus(@Param("factoryId") String factoryId);

    /**
     * 获取需要执行的计划（待处理且到达计划日期）
     * 暂时注释 - 数据库表中没有planned_date字段
     */
    // @Query("SELECT p FROM ProductionPlan p WHERE p.factoryId = :factoryId " +
    //        "AND p.status = 'PENDING' AND p.plannedDate <= CURRENT_DATE " +
    //        "ORDER BY p.priority DESC, p.plannedDate ASC")
    // List<ProductionPlan> findPendingPlansToExecute(@Param("factoryId") String factoryId);

    /**
     * 计算总成本
     */
    @Query("SELECT SUM(COALESCE(p.actualMaterialCost, 0) + " +
           "COALESCE(p.actualLaborCost, 0) + " +
           "COALESCE(p.actualEquipmentCost, 0) + " +
           "COALESCE(p.actualOtherCost, 0)) " +
           "FROM ProductionPlan p WHERE p.factoryId = :factoryId " +
           "AND p.status = 'COMPLETED'")
    Double calculateTotalCost(@Param("factoryId") String factoryId);

    /**
     * 统计工厂的生产计划数量
     */
    long countByFactoryId(String factoryId);

    /**
     * 查找待匹配的未来计划
     * 条件：PENDING状态 + FUTURE类型 + 指定产品类型 + 创建时间早于批次入库时间 + 未完全匹配
     */
    @Query("SELECT p FROM ProductionPlan p WHERE p.factoryId = :factoryId " +
           "AND p.planType = :planType " +
           "AND p.status = :status " +
           "AND p.productTypeId IN :productTypeIds " +
           "AND p.createdAt < :batchCreatedAt " +
           "AND (p.isFullyMatched = false OR p.isFullyMatched IS NULL) " +
           "ORDER BY p.createdAt ASC")
    List<ProductionPlan> findPendingFuturePlansForMatching(
            @Param("factoryId") String factoryId,
            @Param("planType") ProductionPlanType planType,
            @Param("status") ProductionPlanStatus status,
            @Param("productTypeIds") List<String> productTypeIds,
            @Param("batchCreatedAt") LocalDateTime batchCreatedAt);

    /**
     * 统计工厂指定状态的生产计划数量
     */
    long countByFactoryIdAndStatus(String factoryId, ProductionPlanStatus status);

    /**
     * 计算工厂的总产量
     */
    @Query("SELECT SUM(p.actualQuantity) FROM ProductionPlan p WHERE p.factoryId = :factoryId")
    BigDecimal calculateTotalOutput(@Param("factoryId") String factoryId);

    /**
     * 计算指定日期范围内的产量
     */
    @Query("SELECT SUM(p.actualQuantity) FROM ProductionPlan p WHERE p.factoryId = :factoryId " +
           "AND p.endTime BETWEEN :startDate AND :endDate")
    BigDecimal calculateOutputBetweenDates(@Param("factoryId") String factoryId,
                                           @Param("startDate") LocalDateTime startDate,
                                           @Param("endDate") LocalDateTime endDate);

    /**
     * 计算指定日期范围内的计划产量
     * 用于 KPI 计算：产量完成率 = 实际产量 / 计划产量
     */
    @Query("SELECT SUM(p.plannedQuantity) FROM ProductionPlan p WHERE p.factoryId = :factoryId " +
           "AND p.endTime BETWEEN :startDate AND :endDate")
    BigDecimal calculatePlannedOutputBetweenDates(@Param("factoryId") String factoryId,
                                                   @Param("startDate") LocalDateTime startDate,
                                                   @Param("endDate") LocalDateTime endDate);

    /**
     * 计算指定日期范围内的总成本
     */
    @Query("SELECT SUM(COALESCE(p.actualMaterialCost, 0) + " +
           "COALESCE(p.actualLaborCost, 0) + " +
           "COALESCE(p.actualEquipmentCost, 0) + " +
           "COALESCE(p.actualOtherCost, 0)) " +
           "FROM ProductionPlan p WHERE p.factoryId = :factoryId " +
           "AND p.endTime BETWEEN :startDate AND :endDate")
    BigDecimal calculateTotalCostBetweenDates(@Param("factoryId") String factoryId,
                                              @Param("startDate") LocalDateTime startDate,
                                              @Param("endDate") LocalDateTime endDate);

    /**
     * 计算指定日期范围内的原材料成本
     */
    @Query("SELECT SUM(p.actualMaterialCost) FROM ProductionPlan p WHERE p.factoryId = :factoryId " +
           "AND p.endTime BETWEEN :startDate AND :endDate")
    BigDecimal calculateMaterialCostBetweenDates(@Param("factoryId") String factoryId,
                                                 @Param("startDate") LocalDateTime startDate,
                                                 @Param("endDate") LocalDateTime endDate);

    /**
     * 计算指定日期范围内的人工成本
     */
    @Query("SELECT SUM(p.actualLaborCost) FROM ProductionPlan p WHERE p.factoryId = :factoryId " +
           "AND p.endTime BETWEEN :startDate AND :endDate")
    BigDecimal calculateLaborCostBetweenDates(@Param("factoryId") String factoryId,
                                              @Param("startDate") LocalDateTime startDate,
                                              @Param("endDate") LocalDateTime endDate);

    /**
     * 计算指定日期范围内的设备成本
     */
    @Query("SELECT SUM(p.actualEquipmentCost) FROM ProductionPlan p WHERE p.factoryId = :factoryId " +
           "AND p.endTime BETWEEN :startDate AND :endDate")
    BigDecimal calculateEquipmentCostBetweenDates(@Param("factoryId") String factoryId,
                                                  @Param("startDate") LocalDateTime startDate,
                                                  @Param("endDate") LocalDateTime endDate);

    /**
     * 计算指定日期范围内的其他成本
     */
    @Query("SELECT SUM(p.actualOtherCost) FROM ProductionPlan p WHERE p.factoryId = :factoryId " +
           "AND p.endTime BETWEEN :startDate AND :endDate")
    BigDecimal calculateOtherCostBetweenDates(@Param("factoryId") String factoryId,
                                              @Param("startDate") LocalDateTime startDate,
                                              @Param("endDate") LocalDateTime endDate);

    /**
     * 统计指定日期范围内的生产计划数量
     * 暂时注释 - 数据库表中没有planned_date字段
     */
    // @Query("SELECT COUNT(p) FROM ProductionPlan p WHERE p.factoryId = :factoryId " +
    //        "AND p.plannedDate BETWEEN :startDate AND :endDate")
    // long countByFactoryIdAndDateRange(@Param("factoryId") String factoryId,
    //                                   @Param("startDate") LocalDate startDate,
    //                                   @Param("endDate") LocalDate endDate);

    /**
     * 统计指定日期范围内指定状态的生产计划数量
     * 暂时注释 - 数据库表中没有planned_date字段
     */
    // @Query("SELECT COUNT(p) FROM ProductionPlan p WHERE p.factoryId = :factoryId " +
    //        "AND p.status = :status AND p.plannedDate BETWEEN :startDate AND :endDate")
    // long countByFactoryIdAndStatusAndDateRange(@Param("factoryId") String factoryId,
    //                                            @Param("status") ProductionPlanStatus status,
    //                                            @Param("startDate") LocalDate startDate,
    //                                            @Param("endDate") LocalDate endDate);

    // ==================== 调度员模块扩展方法 ====================

    /**
     * 根据计划来源类型查找生产计划
     *
     * @param factoryId 工厂ID
     * @param sourceType 来源类型
     * @param pageable 分页参数
     * @return 生产计划分页数据
     * @since 2025-12-28
     */
    Page<ProductionPlan> findByFactoryIdAndSourceType(
            String factoryId,
            PlanSourceType sourceType,
            Pageable pageable);

    /**
     * 根据多个来源类型查找生产计划
     *
     * @param factoryId 工厂ID
     * @param sourceTypes 来源类型列表
     * @param pageable 分页参数
     * @return 生产计划分页数据
     * @since 2025-12-28
     */
    Page<ProductionPlan> findByFactoryIdAndSourceTypeIn(
            String factoryId,
            List<PlanSourceType> sourceTypes,
            Pageable pageable);

    /**
     * 查找混批计划
     *
     * @param factoryId 工厂ID
     * @param isMixedBatch 是否混批
     * @param pageable 分页参数
     * @return 生产计划分页数据
     * @since 2025-12-28
     */
    Page<ProductionPlan> findByFactoryIdAndIsMixedBatch(
            String factoryId,
            Boolean isMixedBatch,
            Pageable pageable);

    /**
     * 查找紧急计划 (CR < 1)
     *
     * @param factoryId 工厂ID
     * @param crThreshold CR阈值
     * @return 紧急计划列表
     * @since 2025-12-28
     */
    @Query("SELECT p FROM ProductionPlan p WHERE p.factoryId = :factoryId " +
           "AND p.crValue IS NOT NULL AND p.crValue < :crThreshold " +
           "AND p.status NOT IN ('COMPLETED', 'CANCELLED') " +
           "ORDER BY p.crValue ASC")
    List<ProductionPlan> findUrgentPlans(
            @Param("factoryId") String factoryId,
            @Param("crThreshold") BigDecimal crThreshold);

    /**
     * 按CR值排序查找待处理计划
     *
     * @param factoryId 工厂ID
     * @param status 状态
     * @param pageable 分页参数
     * @return 生产计划分页数据
     * @since 2025-12-28
     */
    @Query("SELECT p FROM ProductionPlan p WHERE p.factoryId = :factoryId " +
           "AND p.status = :status " +
           "ORDER BY CASE WHEN p.crValue IS NULL THEN 999 ELSE p.crValue END ASC, " +
           "CASE WHEN p.priority IS NULL THEN 0 ELSE p.priority END DESC")
    Page<ProductionPlan> findByFactoryIdAndStatusOrderByCrValue(
            @Param("factoryId") String factoryId,
            @Param("status") ProductionPlanStatus status,
            Pageable pageable);

    /**
     * 查找客户订单计划
     *
     * @param factoryId 工厂ID
     * @param sourceOrderId 订单ID
     * @return 关联的生产计划列表
     * @since 2025-12-28
     */
    List<ProductionPlan> findByFactoryIdAndSourceOrderId(
            String factoryId,
            String sourceOrderId);

    /**
     * 查找AI预测计划（按置信度筛选）
     *
     * @param factoryId 工厂ID
     * @param minConfidence 最低置信度
     * @return 满足条件的AI预测计划列表
     * @since 2025-12-28
     */
    @Query("SELECT p FROM ProductionPlan p WHERE p.factoryId = :factoryId " +
           "AND p.sourceType = 'AI_FORECAST' " +
           "AND p.aiConfidence >= :minConfidence " +
           "ORDER BY p.aiConfidence DESC")
    List<ProductionPlan> findAiForecastPlansWithMinConfidence(
            @Param("factoryId") String factoryId,
            @Param("minConfidence") Integer minConfidence);

    /**
     * 统计各来源类型的计划数量
     *
     * @param factoryId 工厂ID
     * @return 来源类型与数量的映射
     * @since 2025-12-28
     */
    @Query("SELECT p.sourceType, COUNT(p) FROM ProductionPlan p " +
           "WHERE p.factoryId = :factoryId " +
           "GROUP BY p.sourceType")
    List<Object[]> countBySourceType(@Param("factoryId") String factoryId);

    /**
     * 统计混批计划数量
     *
     * @param factoryId 工厂ID
     * @return 混批计划数量
     * @since 2025-12-28
     */
    long countByFactoryIdAndIsMixedBatch(String factoryId, Boolean isMixedBatch);

    /**
     * 统计紧急计划数量
     *
     * @param factoryId 工厂ID
     * @param crThreshold CR阈值
     * @return 紧急计划数量
     * @since 2025-12-28
     */
    @Query("SELECT COUNT(p) FROM ProductionPlan p WHERE p.factoryId = :factoryId " +
           "AND p.crValue IS NOT NULL AND p.crValue < :crThreshold " +
           "AND p.status NOT IN ('COMPLETED', 'CANCELLED')")
    long countUrgentPlans(
            @Param("factoryId") String factoryId,
            @Param("crThreshold") BigDecimal crThreshold);

    /**
     * 综合筛选查询（支持来源类型、状态、混批标记）
     *
     * @param factoryId 工厂ID
     * @param sourceType 来源类型（可为空）
     * @param status 状态（可为空）
     * @param isMixedBatch 是否混批（可为空）
     * @param pageable 分页参数
     * @return 生产计划分页数据
     * @since 2025-12-28
     */
    @Query("SELECT p FROM ProductionPlan p WHERE p.factoryId = :factoryId " +
           "AND (:sourceType IS NULL OR p.sourceType = :sourceType) " +
           "AND (:status IS NULL OR p.status = :status) " +
           "AND (:isMixedBatch IS NULL OR p.isMixedBatch = :isMixedBatch)")
    Page<ProductionPlan> findByFactoryIdWithFilters(
            @Param("factoryId") String factoryId,
            @Param("sourceType") PlanSourceType sourceType,
            @Param("status") ProductionPlanStatus status,
            @Param("isMixedBatch") Boolean isMixedBatch,
            Pageable pageable);

    /**
     * 查找预计完成日期在指定范围内的计划
     *
     * @param factoryId 工厂ID
     * @param startDate 开始日期
     * @param endDate 结束日期
     * @param pageable 分页参数
     * @return 生产计划分页数据
     * @since 2025-12-28
     */
    @Query("SELECT p FROM ProductionPlan p WHERE p.factoryId = :factoryId " +
           "AND p.expectedCompletionDate BETWEEN :startDate AND :endDate " +
           "ORDER BY p.expectedCompletionDate ASC")
    Page<ProductionPlan> findByFactoryIdAndExpectedCompletionDateBetween(
            @Param("factoryId") String factoryId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            Pageable pageable);

    /**
     * 查找指定状态和预计完成日期范围内的计划（用于紧急状态监控）
     * 不分页，返回所有符合条件的计划用于实时概率计算和紧急状态判断
     *
     * @param factoryId 工厂ID
     * @param status 计划状态
     * @param startDate 开始日期
     * @param endDate 结束日期
     * @return 符合条件的生产计划列表
     * @since 2025-12-29
     */
    List<ProductionPlan> findByFactoryIdAndStatusAndExpectedCompletionDateBetween(
            String factoryId,
            ProductionPlanStatus status,
            LocalDate startDate,
            LocalDate endDate);

    /**
     * 统计指定时间范围内创建的生产计划数量
     * 用于预测报表的置信度计算
     *
     * @param factoryId 工厂ID
     * @param startDateTime 开始时间
     * @param endDateTime 结束时间
     * @return 创建的计划数量
     */
    @Query("SELECT COUNT(p) FROM ProductionPlan p WHERE p.factoryId = :factoryId " +
           "AND p.createdAt >= :startDateTime AND p.createdAt < :endDateTime")
    long countByFactoryIdAndCreatedAtBetween(
            @Param("factoryId") String factoryId,
            @Param("startDateTime") LocalDateTime startDateTime,
            @Param("endDateTime") LocalDateTime endDateTime);

    // ==================== 强制插单审批查询 ====================

    /**
     * 查找待审批的强制插单计划
     * 替代 findAll() + stream filter，避免全表扫描
     *
     * @param factoryId 工厂ID
     * @param isForceInserted 是否强制插单
     * @param requiresApproval 是否需要审批
     * @param approvalStatus 审批状态
     * @return 待审批的强制插单计划列表
     * @since 2026-01-22
     */
    List<ProductionPlan> findByFactoryIdAndIsForceInsertedAndRequiresApprovalAndApprovalStatus(
            String factoryId,
            Boolean isForceInserted,
            Boolean requiresApproval,
            ProductionPlan.ApprovalStatus approvalStatus);

    /**
     * 备货看板已排产量聚合 — 按产品类型 + 状态集合汇总计划数量。
     *
     * <p>典型调用传入 {@code [PLANNED, PENDING]}，排除 IN_PROGRESS（产出已在 WIP/FG 中计算）、
     * COMPLETED 和 CANCELLED。COALESCE(..., 0) 保证空集返回 0。
     *
     * <p>注意：虽然 {@link ProductionPlan} 类级带有 {@code @Where(clause="deleted_at IS NULL")}，
     * 但 Hibernate 对自定义 {@code @Query} 不自动应用 {@code @Where} 过滤（仅标准派生查询生效）。
     * 因此在 JPQL 中显式加 {@code AND p.deletedAt IS NULL}（与同 repo 其他自定义 @Query 保持一致）。
     *
     * @param factoryId     工厂 ID
     * @param productTypeId 产品类型 ID
     * @param statuses      要纳入的计划状态集合
     * @return 符合条件的计划总量 (≥0)
     */
    @Query("SELECT COALESCE(SUM(p.plannedQuantity), 0) FROM ProductionPlan p " +
           "WHERE p.factoryId = :factoryId AND p.productTypeId = :productTypeId " +
           "AND p.status IN :statuses AND p.deletedAt IS NULL")
    BigDecimal sumPlannedQuantityByProductAndStatuses(
            @Param("factoryId") String factoryId,
            @Param("productTypeId") String productTypeId,
            @Param("statuses") Collection<ProductionPlanStatus> statuses);

    // ==================== SP5 多 SO 合并工单双向检索 ====================

    /**
     * SP5 双向检索 — 销售单 → 生产单 (精确 sourceOrderId).
     *
     * <p>兼容旧数据 (sourceOrderIds 未迁移): 查 sourceOrderId 单列。
     *
     * @param factoryId   工厂 ID
     * @param salesOrderId 销售订单 ID
     * @return 关联的生产计划列表 (含历史单 SO 工单)
     */
    @Query("SELECT p FROM ProductionPlan p " +
           "WHERE p.factoryId = :factoryId " +
           "AND p.sourceOrderId = :salesOrderId " +
           "AND p.deletedAt IS NULL " +
           "ORDER BY p.createdAt DESC")
    List<ProductionPlan> findByFactoryIdAndSourceOrderIdExact(
            @Param("factoryId") String factoryId,
            @Param("salesOrderId") String salesOrderId);

    /**
     * SP5 双向检索 — 销售单 → 合并生产单 (JSONB @> containment, 依赖 GIN 索引 V20261023_01).
     *
     * <p>此查询用原生 SQL 的 JSONB 包含运算符 {@code @>}; JPQL 不支持 JSONB 运算符,
     * 因此使用 nativeQuery=true。工厂隔离通过 AND factory_id = :factoryId 保证。
     * deleted_at IS NULL 对齐 @Where 过滤 (nativeQuery 不自动应用 @Where)。
     *
     * @param factoryId    工厂 ID
     * @param salesOrderId 销售订单 ID
     * @return 在 source_order_ids 列表中包含该 SO ID 的生产计划 (多 SO 合并场景)
     */
    @Query(value = "SELECT * FROM production_plans " +
                   "WHERE factory_id = :factoryId " +
                   "AND source_order_ids @> CAST(:soIdJson AS jsonb) " +
                   "AND deleted_at IS NULL " +
                   "ORDER BY created_at DESC",
           nativeQuery = true)
    List<ProductionPlan> findByFactoryIdAndSourceOrderIdsContaining(
            @Param("factoryId") String factoryId,
            @Param("soIdJson") String soIdJson);   // caller passes: "[\"<soId>\"]"
}
